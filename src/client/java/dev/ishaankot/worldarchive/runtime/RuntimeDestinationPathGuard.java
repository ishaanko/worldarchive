package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.model.DestinationResult;
import dev.ishaankot.worldarchive.model.DestinationStatus;
import dev.ishaankot.worldarchive.model.DestinationType;
import java.util.List;
import java.util.Objects;

/** Prevents a settings save from disconnecting catalog records from managed storage. */
final class RuntimeDestinationPathGuard {
    private RuntimeDestinationPathGuard() {
    }

    static void requireAllowed(
            RuntimeStoragePaths current,
            RuntimeStoragePaths replacement,
            List<DestinationResult> catalogDestinations) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(replacement, "replacement");
        List<DestinationResult> destinations = List.copyOf(
                Objects.requireNonNull(catalogDestinations, "catalogDestinations"));
        if (!current.gitRepository().equals(replacement.gitRepository())
                && dependsOn(destinations, DestinationType.GIT)) {
            throw new IllegalArgumentException(
                    "The Git repository cannot change while the catalog contains Git backups");
        }
        if (!current.zipDirectory().equals(replacement.zipDirectory())
                && dependsOn(destinations, DestinationType.ZIP)) {
            throw new IllegalArgumentException(
                    "The ZIP destination cannot change while the catalog contains ZIP backups");
        }
    }

    private static boolean dependsOn(
            List<DestinationResult> destinations,
            DestinationType type) {
        return destinations.stream()
                .anyMatch(result -> result.destination() == type
                        && (result.status() == DestinationStatus.SUCCESS
                                || result.status() == DestinationStatus.PENDING_SYNC));
    }
}
