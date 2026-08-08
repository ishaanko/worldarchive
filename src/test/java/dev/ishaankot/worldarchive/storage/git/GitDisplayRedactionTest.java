package dev.ishaankot.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.ishaankot.worldarchive.model.BackupId;
import dev.ishaankot.worldarchive.model.WorldId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitDisplayRedactionTest {
    @Test
    void verificationAndHealthObjectsRedactMissedProcessSecrets() {
        String secret = "Authorization: Bearer ABCDEFGHIJKLMNOP";
        WorldId worldId = WorldId.create();
        BackupId backupId = BackupId.create();
        GitSnapshot snapshot = new GitSnapshot(
                worldId,
                backupId,
                GitSnapshot.refName(worldId, backupId),
                "a".repeat(40),
                Instant.EPOCH);

        GitVerification verification = new GitVerification(snapshot, false, secret);
        GitToolHealth health = new GitToolHealth(
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.of(secret),
                Optional.of("api_key=visible"));

        assertFalse(verification.message().contains("ABCDEFGHIJKLMNOP"));
        assertFalse(health.gitFailure().orElseThrow().contains("ABCDEFGHIJKLMNOP"));
        assertFalse(health.lfsFailure().orElseThrow().contains("visible"));
    }
}
