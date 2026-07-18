package dev.ishaankot.worldarchive.settings;

import java.util.Optional;

/** Pure lifecycle and revision gate for asynchronous settings-screen callbacks. */
public final class SettingsScreenState {
    private long lifecycleRevision = 1;

    private long validationRevision;

    private long healthRevision;

    private boolean active;

    private boolean closed;

    private boolean saving;

    public synchronized LifecycleToken lifecycleToken() {
        return new LifecycleToken(lifecycleRevision);
    }

    public synchronized void activate() {
        if (!closed) {
            active = true;
        }
    }

    public synchronized void deactivate() {
        active = false;
        lifecycleRevision++;
        validationRevision++;
        healthRevision++;
        saving = false;
    }

    public synchronized void close() {
        closed = true;
        deactivate();
    }

    public synchronized boolean acceptsBeforeInitialization(LifecycleToken token) {
        return !closed && token.revision() == lifecycleRevision;
    }

    public synchronized boolean acceptsActive(LifecycleToken token) {
        return active && acceptsBeforeInitialization(token);
    }

    public synchronized RevisionToken nextValidation() {
        return new RevisionToken(lifecycleRevision, ++validationRevision);
    }

    public synchronized boolean acceptsValidation(RevisionToken token) {
        return active
                && token.lifecycleRevision() == lifecycleRevision
                && token.operationRevision() == validationRevision;
    }

    public synchronized RevisionToken nextHealthProbe() {
        return new RevisionToken(lifecycleRevision, ++healthRevision);
    }

    public synchronized boolean acceptsHealthProbe(RevisionToken token) {
        return active
                && token.lifecycleRevision() == lifecycleRevision
                && token.operationRevision() == healthRevision;
    }

    public synchronized Optional<LifecycleToken> beginSave() {
        if (!active || closed || saving) {
            return Optional.empty();
        }
        saving = true;
        return Optional.of(lifecycleToken());
    }

    public synchronized void finishSave(LifecycleToken token) {
        if (token.revision() == lifecycleRevision) {
            saving = false;
        }
    }

    public synchronized boolean saving() {
        return saving;
    }

    public record LifecycleToken(long revision) {
        public LifecycleToken {
            if (revision < 1) {
                throw new IllegalArgumentException("Lifecycle revision must be positive");
            }
        }
    }

    public record RevisionToken(long lifecycleRevision, long operationRevision) {
        public RevisionToken {
            if (lifecycleRevision < 1 || operationRevision < 1) {
                throw new IllegalArgumentException("Async revisions must be positive");
            }
        }
    }
}
