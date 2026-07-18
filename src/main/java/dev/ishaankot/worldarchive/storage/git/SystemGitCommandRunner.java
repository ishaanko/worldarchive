package dev.ishaankot.worldarchive.storage.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Git directly with {@link ProcessBuilder}; no shell is ever involved.
 *
 * <p>The portable Java process API has no process-group or Windows Job Object primitive. Descendants
 * are therefore sampled aggressively and recursively, but a child that is spawned and reparented
 * entirely between samples cannot be proven killable without a platform-specific launcher.</p>
 */
public final class SystemGitCommandRunner implements GitCommandRunner {
    private static final Pattern URI_CREDENTIALS = Pattern.compile(
            "(?i)([a-z][a-z0-9+.-]*://)([^\\s/@]+(?::[^\\s/@]*)?@)");

    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(password|passwd|access[_-]?token|refresh[_-]?token|token|secret|"
                    + "api[_-]?key|access[_-]?key|credential)([=:]\\s*)([^\\s&;,]+)");

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*:\\s*(?:Bearer|Basic)\\s+)([A-Za-z0-9._~+/=-]{8,})");

    private static final Pattern KNOWN_TOKEN = Pattern.compile(
            "(?i)(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|"
                    + "glpat-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9_-]{20,}|"
                    + "xox[baprs]-[A-Za-z0-9-]{10,}|ya29\\.[A-Za-z0-9_-]{20,}|"
                    + "(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}|"
                    + "AKIA[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{30,})");

    private static final Pattern JSON_WEB_TOKEN = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    private static final long TERMINATION_GRACE_MILLIS = 500L;

    private static final long PROCESS_POLL_MILLIS = 10L;

    @Override
    public GitCommandResult run(GitCommand command) throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        long deadlineNanos = deadline(command);
        ProcessBuilder builder = new ProcessBuilder(command.arguments());
        builder.directory(command.workingDirectory().toFile());
        builder.redirectErrorStream(false);
        Map<String, String> environment = builder.environment();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "Never");
        environment.put("GIT_ASKPASS", "");
        environment.put("SSH_ASKPASS", "");
        environment.put("GIT_LFS_FORCE_PROGRESS", "0");
        environment.putAll(command.environment());

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new IOException("Unable to start the configured Git executable", exception);
        }

        BoundedOutput output = new BoundedOutput(command.maximumOutputBytes());
        BoundedOutput error = new BoundedOutput(command.maximumOutputBytes());
        Thread outputReader = Thread.ofVirtual().name("worldarchive-git-stdout").start(
                () -> output.read(process.getInputStream()));
        Thread errorReader = Thread.ofVirtual().name("worldarchive-git-stderr").start(
                () -> error.read(process.getErrorStream()));
        Set<ProcessHandle> observedDescendants = ConcurrentHashMap.newKeySet();
        AtomicBoolean observeDescendants = new AtomicBoolean(true);
        Thread descendantObserver = Thread.ofVirtual().name("worldarchive-git-descendants").start(() -> {
            while (observeDescendants.get() && process.isAlive()) {
                observeDescendantsRecursively(process, observedDescendants);
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            observeDescendantsRecursively(process, observedDescendants);
        });

        try {
            try (var input = process.getOutputStream()) {
                input.write(command.standardInput());
            }
            if (!waitForExit(process, observedDescendants, deadlineNanos)
                    || !joinReadersUntil(
                            process,
                            observedDescendants,
                            outputReader,
                            errorReader,
                            deadlineNanos)) {
                terminate(process, observedDescendants);
                closeProcessStreams(process);
                joinReadersAfterTermination(outputReader, errorReader);
                throw new GitCommandTimeoutException(command.timeout());
            }
        } catch (InterruptedException exception) {
            terminate(process, observedDescendants);
            closeProcessStreams(process);
            outputReader.interrupt();
            errorReader.interrupt();
            joinReadersAfterTermination(outputReader, errorReader);
            throw exception;
        } catch (IOException exception) {
            terminate(process, observedDescendants);
            closeProcessStreams(process);
            joinReadersAfterTermination(outputReader, errorReader);
            throw exception;
        } finally {
            observeDescendants.set(false);
            descendantObserver.interrupt();
            joinObserver(descendantObserver);
            closeProcessStreams(process);
        }

        return new GitCommandResult(
                process.exitValue(),
                redact(output.text(), command.secrets()),
                redact(error.text(), command.secrets()),
                output.truncated(),
                error.truncated(),
                output.byteCount(),
                output.sha256());
    }

    private static long deadline(GitCommand command) {
        long now = System.nanoTime();
        long timeout = command.timeout().toNanos();
        return now > Long.MAX_VALUE - timeout ? Long.MAX_VALUE : now + timeout;
    }

    private static boolean waitForExit(
            Process process,
            Set<ProcessHandle> observedDescendants,
            long deadlineNanos) throws InterruptedException {
        while (true) {
            observeDescendants(process, observedDescendants);
            if (!process.isAlive()) {
                return true;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            long waitMillis = Math.max(
                    1L,
                    Math.min(PROCESS_POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining)));
            process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean joinReadersUntil(
            Process process,
            Set<ProcessHandle> observedDescendants,
            Thread outputReader,
            Thread errorReader,
            long deadlineNanos) throws InterruptedException {
        while (outputReader.isAlive() || errorReader.isAlive()) {
            observeDescendants(process, observedDescendants);
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            long waitMillis = Math.max(
                    1L,
                    Math.min(PROCESS_POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining)));
            outputReader.join(waitMillis);
            errorReader.join(waitMillis);
        }
        return true;
    }

    private static void observeDescendants(Process process, Set<ProcessHandle> observedDescendants) {
        observeDescendantsRecursively(process, observedDescendants);
    }

    private static void observeDescendantsRecursively(
            Process process,
            Set<ProcessHandle> observedDescendants) {
        process.descendants().forEach(observedDescendants::add);
        for (ProcessHandle handle : List.copyOf(observedDescendants)) {
            if (handle.isAlive()) {
                handle.descendants().forEach(observedDescendants::add);
            }
        }
    }

    private static void terminate(Process process, Set<ProcessHandle> observedDescendants) {
        observeDescendants(process, observedDescendants);
        destroyObserved(observedDescendants, false);
        process.destroy();
        long stopDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TERMINATION_GRACE_MILLIS);
        boolean interrupted = false;
        while ((process.isAlive() || observedDescendants.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < stopDeadline) {
            observeDescendantsRecursively(process, observedDescendants);
            destroyObserved(observedDescendants, false);
            try {
                Thread.sleep(PROCESS_POLL_MILLIS);
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }
        observeDescendantsRecursively(process, observedDescendants);
        destroyObserved(observedDescendants, true);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void destroyObserved(Set<ProcessHandle> observedDescendants, boolean forcibly) {
        List<ProcessHandle> handles = new ArrayList<>(observedDescendants);
        handles.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                if (forcibly) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            }
        }
    }

    private static void joinObserver(Thread observer) {
        boolean interrupted = false;
        try {
            observer.join(TERMINATION_GRACE_MILLIS);
        } catch (InterruptedException exception) {
            interrupted = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinReadersAfterTermination(Thread outputReader, Thread errorReader) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TERMINATION_GRACE_MILLIS);
        boolean interrupted = false;
        while ((outputReader.isAlive() || errorReader.isAlive()) && System.nanoTime() < deadline) {
            try {
                outputReader.join(PROCESS_POLL_MILLIS);
                errorReader.join(PROCESS_POLL_MILLIS);
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }
        outputReader.interrupt();
        errorReader.interrupt();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeProcessStreams(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Closing is best-effort after bounded process handling.
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // Closing is best-effort after bounded process handling.
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Closing is best-effort after bounded process handling.
        }
    }

    static String redact(String value, Iterable<String> secrets) {
        String redacted = value;
        List<String> orderedSecrets = new ArrayList<>();
        secrets.forEach(secret -> {
            if (secret != null && !secret.isBlank()) {
                orderedSecrets.add(secret);
            }
        });
        orderedSecrets.sort(Comparator.comparingInt(String::length).reversed());
        for (String secret : orderedSecrets) {
            redacted = redacted.replace(secret, "[REDACTED]");
        }
        redacted = URI_CREDENTIALS.matcher(redacted).replaceAll("$1[REDACTED]@");
        Matcher matcher = NAMED_SECRET.matcher(redacted);
        redacted = matcher.replaceAll("$1$2[REDACTED]");
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = KNOWN_TOKEN.matcher(redacted).replaceAll("[REDACTED]");
        return JSON_WEB_TOKEN.matcher(redacted).replaceAll("[REDACTED]");
    }

    private static final class BoundedOutput {
        private final ByteArrayOutputStream bytes;

        private final int maximumBytes;

        private final MessageDigest digest = GitInventory.sha256();

        private long byteCount;

        private volatile boolean truncated;

        private volatile IOException failure;

        private BoundedOutput(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            bytes = new ByteArrayOutputStream(Math.min(maximumBytes, 8_192));
        }

        private void read(InputStream input) {
            byte[] buffer = new byte[8_192];
            try (input) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    try {
                        byteCount = Math.addExact(byteCount, count);
                    } catch (ArithmeticException exception) {
                        throw new IOException("Git process output size overflowed", exception);
                    }
                    digest.update(buffer, 0, count);
                    int remaining = maximumBytes - bytes.size();
                    if (remaining > 0) {
                        bytes.write(buffer, 0, Math.min(remaining, count));
                    }
                    if (count > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }

        private String text() throws IOException {
            if (failure != null) {
                throw new IOException("Unable to read bounded Git process output", failure);
            }
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private boolean truncated() {
            return truncated;
        }

        private long byteCount() {
            return byteCount;
        }

        private String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
