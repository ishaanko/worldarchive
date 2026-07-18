package dev.ishaankot.worldarchive.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RemoteUrlPolicyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyWhitelistedCredentialFreeForms() {
        assertEquals(
                "https://example.invalid/team/backups.git",
                RemoteUrlPolicy.validate("https://example.invalid/team/backups.git"));
        assertEquals(
                "git@example.invalid:team/backups.git",
                RemoteUrlPolicy.validate("git@example.invalid:team/backups.git"));
        String local = temporaryDirectory.resolve("remote.git").toString();
        assertEquals(local, RemoteUrlPolicy.validate(local));
    }

    @Test
    void rejectsCredentialsTokensAndUnapprovedForms() {
        assertThrows(IllegalArgumentException.class, () -> RemoteUrlPolicy.validate("../relative.git"));
        assertThrows(IllegalArgumentException.class, () -> RemoteUrlPolicy.validate(
                "https://user:password@example.invalid/team/repository.git"));
        assertThrows(IllegalArgumentException.class, () -> RemoteUrlPolicy.validate(
                "https://example.invalid/team/repository.git?token=value"));
        assertThrows(IllegalArgumentException.class, () -> RemoteUrlPolicy.validate(
                "https://example.invalid/ghp_abcdefghijklmnopqrstuvwxyz/repository.git"));
        assertThrows(IllegalArgumentException.class, () -> RemoteUrlPolicy.validate(
                "https://example.invalid/ghp%5Fabcdefghijklmnopqrstuvwxyz/repository.git"));
        assertThrows(IllegalArgumentException.class, () -> RemoteUrlPolicy.validate(
                "https://example.invalid/x_ghp_abcdefghijklmnopqrstuvwxyz/repository.git"));
        assertThrows(IllegalArgumentException.class, () -> RemoteUrlPolicy.validate(
                "https://example.invalid/ghp%255Fabcdefghijklmnopqrstuvwxyz/repository.git"));
    }
}
