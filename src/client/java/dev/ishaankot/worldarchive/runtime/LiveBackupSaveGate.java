package dev.ishaankot.worldarchive.runtime;

import java.util.Objects;
import java.util.Optional;

/** Pure owned-save state used to distinguish requested, exit, and unrelated vanilla saves. */
final class LiveBackupSaveGate<K, T> {
    private Entry<K, T> pending;

    synchronized boolean queueRequested(K owner, T value) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(value, "value");
        if (pending != null) {
            return false;
        }
        pending = new Entry<>(owner, value, Phase.REQUESTED);
        return true;
    }

    synchronized ExitInstall<T> installExit(K owner, T value) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(value, "value");
        if (pending != null
                && (pending.owner() != owner || pending.phase().exit())) {
            return ExitInstall.rejected();
        }
        T displaced = pending == null ? null : pending.value();
        pending = new Entry<>(owner, value, Phase.EXIT_WAITING);
        return ExitInstall.installed(displaced);
    }

    synchronized boolean armRequested(K owner, T value) {
        if (pending == null
                || pending.owner() != owner
                || pending.value() != value
                || pending.phase() != Phase.REQUESTED) {
            return false;
        }
        pending = new Entry<>(owner, value, Phase.REQUESTED_ARMED);
        return true;
    }

    synchronized Optional<T> observeRequestedSave(
            K owner,
            boolean flush,
            boolean force) {
        if (pending == null
                || pending.owner() != owner
                || pending.phase() != Phase.REQUESTED_ARMED
                || !flush
                || !force) {
            return Optional.empty();
        }
        T consumed = pending.value();
        pending = null;
        return Optional.of(consumed);
    }

    synchronized boolean observeExitSave(
            K owner,
            boolean flush,
            boolean force) {
        if (pending == null
                || pending.owner() != owner
                || !pending.phase().exit()
                || !flush
                || force) {
            return false;
        }
        pending = new Entry<>(owner, pending.value(), Phase.EXIT_SAVED);
        return true;
    }

    synchronized StopResult<T> stop(K owner) {
        if (pending == null || pending.owner() != owner) {
            return StopResult.none();
        }
        Entry<K, T> stopped = pending;
        pending = null;
        return switch (stopped.phase()) {
            case REQUESTED, REQUESTED_ARMED -> StopResult.of(
                    StopKind.REQUEST_ABANDONED, stopped.value());
            case EXIT_WAITING -> StopResult.of(
                    StopKind.EXIT_MISSING_SAVE, stopped.value());
            case EXIT_SAVED -> StopResult.of(
                    StopKind.EXIT_READY, stopped.value());
        };
    }

    synchronized Optional<T> clear(K owner, T value) {
        if (pending == null || pending.owner() != owner || pending.value() != value) {
            return Optional.empty();
        }
        T cleared = pending.value();
        pending = null;
        return Optional.of(cleared);
    }

    synchronized Optional<T> clear() {
        if (pending == null) {
            return Optional.empty();
        }
        T cleared = pending.value();
        pending = null;
        return Optional.of(cleared);
    }

    synchronized Optional<T> pending() {
        return pending == null ? Optional.empty() : Optional.of(pending.value());
    }

    synchronized boolean ownedBy(K owner) {
        return pending != null && pending.owner() == owner;
    }

    synchronized boolean hasPending() {
        return pending != null;
    }

    enum StopKind {
        NONE,
        REQUEST_ABANDONED,
        EXIT_MISSING_SAVE,
        EXIT_READY
    }

    record ExitInstall<T>(boolean installed, Optional<T> displaced) {
        ExitInstall {
            displaced = Objects.requireNonNull(displaced, "displaced");
            if (!installed && displaced.isPresent()) {
                throw new IllegalArgumentException("Rejected exit installation cannot displace a save");
            }
        }

        private static <T> ExitInstall<T> installed(T displaced) {
            return new ExitInstall<>(true, Optional.ofNullable(displaced));
        }

        private static <T> ExitInstall<T> rejected() {
            return new ExitInstall<>(false, Optional.empty());
        }
    }

    record StopResult<T>(StopKind kind, Optional<T> value) {
        StopResult {
            kind = Objects.requireNonNull(kind, "kind");
            value = Objects.requireNonNull(value, "value");
            if ((kind == StopKind.NONE) != value.isEmpty()) {
                throw new IllegalArgumentException("Only an empty stop result may omit its value");
            }
        }

        private static <T> StopResult<T> none() {
            return new StopResult<>(StopKind.NONE, Optional.empty());
        }

        private static <T> StopResult<T> of(StopKind kind, T value) {
            return new StopResult<>(kind, Optional.of(value));
        }
    }

    private enum Phase {
        REQUESTED(false),
        REQUESTED_ARMED(false),
        EXIT_WAITING(true),
        EXIT_SAVED(true);

        private final boolean exit;

        Phase(boolean exit) {
            this.exit = exit;
        }

        private boolean exit() {
            return exit;
        }
    }

    private record Entry<K, T>(K owner, T value, Phase phase) {
        private Entry {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(phase, "phase");
        }
    }
}
