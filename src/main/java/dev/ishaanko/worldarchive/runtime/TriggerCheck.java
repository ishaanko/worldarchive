package dev.ishaanko.worldarchive.runtime;

import dev.ishaanko.worldarchive.config.DestinationTriggerConfig;
import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.core.CreateBackupRequest;
import dev.ishaanko.worldarchive.model.BackupTrigger;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import java.util.Objects;
import java.util.Optional;

/**
 * The one rule for whether a manual, scheduled or world-exit backup of a world may start now.
 * Triggers differ only in what they do with a refusal: a manual backup reports every refusal, while
 * a scheduled or world-exit backup stays silent when the player turned it off.
 */
public final class TriggerCheck {
    private TriggerCheck() {
    }

    /** Why a backup may not start. */
    public enum Reason {
        /** A backup folder is inside a known world. */
        STORAGE_PROBLEM,
        /** The settings link the world's identity to another folder. */
        IDENTITY_ELSEWHERE,
        /** The player turned backups off for this world. */
        WORLD_OFF,
        /** No destination takes backups from this trigger. */
        TRIGGER_OFF,
        /** Only Git takes this trigger, and Git or Git LFS is missing. */
        GIT_MISSING;

        /** True when the refusal follows the player's own settings, so the trigger reports nothing. */
        public boolean silentFor(BackupTrigger trigger) {
            return trigger != BackupTrigger.MANUAL && (this == WORLD_OFF || this == TRIGGER_OFF);
        }
    }

    /** The outcome of a check: the request to run, or why none may start. */
    public sealed interface Decision {
        /** The backup may start with this request. */
        record Proceed(CreateBackupRequest request) implements Decision {
            public Proceed {
                Objects.requireNonNull(request, "request");
            }
        }

        /** The backup may not start; {@code message} says why, for the player. */
        record Refused(Reason reason, String message) implements Decision {
            public Refused {
                Objects.requireNonNull(reason, "reason");
                Objects.requireNonNull(message, "message");
            }
        }
    }

    /**
     * Decides whether {@code trigger} may back up {@code world} with the services of {@code state}.
     *
     * @param storageIssue why backups are paused, from {@link WorldIdentityResolver#storageIssue()}
     */
    public static Decision evaluate(
            RuntimeState state,
            Optional<String> storageIssue,
            BackupWorldContext world,
            BackupTrigger trigger,
            Optional<String> label) {
        if (storageIssue.isPresent()) {
            return new Decision.Refused(Reason.STORAGE_PROBLEM, storageIssue.get());
        }
        CreateBackupRequest request = new CreateBackupRequest(
                world.worldId(), world.worldDirectory(), world.displayName(), label, trigger);
        boolean selected;
        try {
            selected = !state.selector().select(request).isEmpty();
        } catch (IllegalArgumentException identityElsewhere) {
            return new Decision.Refused(Reason.IDENTITY_ELSEWHERE, "WorldArchive settings link this world's backups"
                    + " to another folder. Open the world list once so WorldArchive can update them, then try again.");
        }
        if (selected) {
            return new Decision.Proceed(request);
        }
        if (state.config().world(world.worldId()).filter(configured -> !configured.enabled()).isPresent()) {
            return new Decision.Refused(Reason.WORLD_OFF, "Backups are off for this world in WorldArchive settings.");
        }
        if (state.selector().hasConfiguredDestination(request)) {
            return new Decision.Refused(Reason.GIT_MISSING, state.selector().warning()
                    .orElse("Git backups are off until WorldArchive has checked Git. Try again in a moment."));
        }
        return new Decision.Refused(Reason.TRIGGER_OFF, "No backup destination takes " + name(trigger)
                + " backups. Turn one on in WorldArchive settings.");
    }

    /** True when some destination takes backups from {@code trigger}, whatever the world. */
    public static boolean anyDestinationTakes(WorldArchiveConfig config, BackupTrigger trigger) {
        return config.triggers().enabledFor(trigger)
                && (takes(config.git().enabled(), config.git().triggers(), trigger)
                        || takes(config.zip().enabled(), config.zip().triggers(), trigger));
    }

    private static boolean takes(boolean enabled, DestinationTriggerConfig triggers, BackupTrigger trigger) {
        return enabled && triggers.enabledFor(trigger);
    }

    private static String name(BackupTrigger trigger) {
        return switch (trigger) {
            case MANUAL -> "manual";
            case SCHEDULED -> "scheduled";
            case WORLD_EXIT -> "world-exit";
        };
    }
}
