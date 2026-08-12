package dev.ishaanko.worldarchive.storage.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads credential helpers from the system Git configuration, once per executable.
 *
 * <p>Backup commands run with {@code GIT_CONFIG_NOSYSTEM=1} so system configuration cannot change
 * how snapshots are written. On macOS and Windows, however, the stock credential helper
 * ({@code osxkeychain}, {@code manager}) is configured exactly there, so hiding that file also
 * hides the only non-interactive credential source and every HTTPS push fails once terminal
 * prompts are disabled. Only the {@code credential.helper} values are read back, to be re-injected
 * as command-scoped configuration on network commands.</p>
 */
final class SystemCredentialHelpers {
    private static final Map<String, List<String>> RESOLVED = new ConcurrentHashMap<>();

    private static final long PROBE_TIMEOUT_MILLIS = 10_000L;

    private static final int MAXIMUM_OUTPUT_BYTES = 16_384;

    private SystemCredentialHelpers() {
    }

    static List<String> resolve(String executable, Map<String, String> environment)
            throws InterruptedException {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(environment, "environment");
        List<String> resolved = RESOLVED.get(executable);
        if (resolved != null) {
            return resolved;
        }
        List<String> helpers = probe(executable, environment);
        if (helpers.isEmpty()) {
            helpers = platformDefault(System.getProperty("os.name", ""));
        }
        RESOLVED.putIfAbsent(executable, helpers);
        return helpers;
    }

    private static List<String> probe(String executable, Map<String, String> environment)
            throws InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                executable, "config", "--system", "--get-all", "credential.helper");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String, String> probeEnvironment = builder.environment();
        probeEnvironment.clear();
        probeEnvironment.putAll(environment);
        probeEnvironment.remove("GIT_CONFIG_NOSYSTEM");
        probeEnvironment.put("GIT_TERMINAL_PROMPT", "0");
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            return List.of();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().name("worldarchive-credential-probe").start(
                () -> copyBounded(process.getInputStream(), output));
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The probe never needs standard input.
        }
        try {
            if (!process.waitFor(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            reader.join(TimeUnit.SECONDS.toMillis(1L));
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            reader.interrupt();
            throw exception;
        }
        return parse(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private static void copyBounded(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[4_096];
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                int remaining = MAXIMUM_OUTPUT_BYTES - output.size();
                if (remaining <= 0) {
                    return;
                }
                output.write(buffer, 0, Math.min(remaining, count));
            }
        } catch (IOException ignored) {
            // A partial read parses as whatever helpers were seen.
        }
    }

    static List<String> parse(int exitCode, String output) {
        if (exitCode != 0) {
            return List.of();
        }
        List<String> helpers = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String helper = line.strip();
            if (!helper.isEmpty()) {
                helpers.add(helper);
            }
        }
        return List.copyOf(helpers);
    }

    static List<String> platformDefault(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("osxkeychain");
        }
        if (os.startsWith("windows")) {
            return List.of("manager");
        }
        return List.of();
    }
}
