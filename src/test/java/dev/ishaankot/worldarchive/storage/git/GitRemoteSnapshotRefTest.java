package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GitRemoteSnapshotRefTest {
    @Test
    void leadsWithReadableUtcDateAndOmitsWorldUuid() {
        WorldId worldId = WorldId.parse("179a1a79-98c6-445b-ae2e-bcd82db28764");
        BackupId backupId = BackupId.parse("859d850e-c1ad-4e42-b649-16c64aeaa846");
        GitSnapshot snapshot = new GitSnapshot(
                worldId,
                backupId,
                GitSnapshot.refName(worldId, backupId),
                "d42b70280cd9ae1c4e10b9296bbbb587d0e6fad2",
                Instant.parse("2026-07-21T22:33:23Z"));

        assertEquals(
                "refs/heads/backups/2026-07-21/22-33-23Z-"
                        + "859d850e-c1ad-4e42-b649-16c64aeaa846",
                GitRemoteSnapshotRef.current(snapshot));
    }
}
