package dev.ishaankot.worldarchive.command.model;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.BackupRecord;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Pure full-ID and prefix resolver for command adapters. */
public final class BackupIdResolver {
    private BackupIdResolver() {
    }

    public static BackupIdResolution resolve(List<BackupRecord> records, String input) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(input, "input");
        String prefix = input.strip().toLowerCase(Locale.ROOT);
        if (!isCanonicalPrefix(prefix)) {
            return empty(BackupIdResolutionStatus.INVALID);
        }
        List<BackupId> matches = records.stream()
                .map(record -> record.manifest().backupId())
                .distinct()
                .filter(id -> id.toString().startsWith(prefix))
                .sorted()
                .toList();
        if (matches.isEmpty()) {
            return empty(BackupIdResolutionStatus.NOT_FOUND);
        }
        if (matches.size() > 1) {
            return new BackupIdResolution(
                    BackupIdResolutionStatus.AMBIGUOUS,
                    Optional.empty(),
                    matches);
        }
        BackupId resolved = matches.getFirst();
        BackupIdResolutionStatus status = resolved.toString().equals(prefix)
                ? BackupIdResolutionStatus.EXACT
                : BackupIdResolutionStatus.UNIQUE_PREFIX;
        return new BackupIdResolution(status, Optional.of(resolved), matches);
    }

    private static BackupIdResolution empty(BackupIdResolutionStatus status) {
        return new BackupIdResolution(status, Optional.empty(), List.of());
    }

    private static boolean isCanonicalPrefix(String value) {
        if (value.isEmpty() || value.length() > 36) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            boolean hyphenPosition = index == 8 || index == 13 || index == 18 || index == 23;
            char character = value.charAt(index);
            if (hyphenPosition != (character == '-')) {
                return false;
            }
            boolean hexadecimal = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f';
            if (!hyphenPosition && !hexadecimal) {
                return false;
            }
        }
        return true;
    }
}
