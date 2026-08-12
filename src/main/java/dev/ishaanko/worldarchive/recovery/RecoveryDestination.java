package dev.ishaanko.worldarchive.recovery;

import dev.ishaanko.worldarchive.model.BackupRecord;
import dev.ishaanko.worldarchive.model.DestinationHealth;
import dev.ishaanko.worldarchive.model.DestinationResult;
import dev.ishaanko.worldarchive.model.DestinationType;
import dev.ishaanko.worldarchive.model.WorldId;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Optional;

/** Blocking worker-thread adapter for the independent destination implementations. */
interface RecoveryDestination {
    DestinationType destinationType();

    VerificationOutcome verify(BackupRecord record, DestinationResult destination) throws Exception;

    default VerificationOutcome verifyForRestore(
            BackupRecord record,
            DestinationResult destination) throws Exception {
        return verify(record, destination);
    }

    Materialization materialize(
            BackupRecord record,
            DestinationResult destination,
            Path emptyTarget)
            throws Exception;

    /** Returns true when the exact artifact was removed or its exact absence was confirmed. */
    boolean delete(BackupRecord record, DestinationResult destination) throws Exception;

    DestinationResult sync(BackupRecord record, DestinationResult destination) throws Exception;

    DestinationHealth health(Optional<WorldId> worldId) throws Exception;

    record Materialization(
            Path path,
            boolean preservesDirectoryIdentity,
            Object fileKey,
            FileTime creationTime,
            Optional<String> directoryIdentityMarker,
            Optional<String> postMaterializationProblem) {
        public Materialization {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            postMaterializationProblem = Objects.requireNonNull(
                    postMaterializationProblem, "postMaterializationProblem");
            directoryIdentityMarker = Objects.requireNonNull(
                    directoryIdentityMarker, "directoryIdentityMarker");
            if (!preservesDirectoryIdentity) {
                Objects.requireNonNull(creationTime, "creationTime");
            }
        }

        static Materialization preserved(Path path) {
            return new Materialization(
                    path,
                    true,
                    null,
                    null,
                    Optional.empty(),
                    Optional.empty());
        }

        static Materialization replaced(
                Path path,
                Object fileKey,
                FileTime creationTime,
                Optional<String> directoryIdentityMarker,
                Optional<String> postMaterializationProblem) {
            return new Materialization(
                    path,
                    false,
                    fileKey,
                    creationTime,
                    directoryIdentityMarker,
                    postMaterializationProblem);
        }
    }
}
