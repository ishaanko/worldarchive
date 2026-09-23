package dev.ishaanko.worldarchive.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RemoteUrlPolicyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsTheRemotesPlayersUse() {
        List<String> remotes = List.of(
                "https://example.invalid/team/backups.git",
                "git@github.com:bob/Desk-and-chair-collection-2024.git",
                "ssh://git@gitea.example.com:2222/bob/world.git",
                "ssh://git@github.com/bob/world.git",
                "gitea:bob/world.git",
                "file:///srv/git/world.git",
                temporaryDirectory.resolve("100% Backups/world.git").toString(),
                temporaryDirectory.resolve("Basic Training Backups/world.git").toString(),
                temporaryDirectory.resolve("Bob's Laptop & Co (2)/RUNNER~1/remote.git").toString());
        for (String remote : remotes) {
            assertEquals(remote, RemoteUrlPolicy.validateConfiguredPlain(remote));
        }
    }

    @Test
    void rejectsCredentialsTokensAndUnapprovedForms() {
        List<String> rejected = List.of(
                "../relative.git",
                "https://user:password@example.invalid/team/repository.git",
                "https://token@example.invalid/team/repository.git",
                "ssh://git:password@example.invalid/team/repository.git",
                "https://example.invalid/team/repository.git?token=value",
                "https://example.invalid/team/repository.git#main",
                "https://example.invalid/ghp_abcdefghijklmnopqrstuvwxyz/repository.git",
                "https://example.invalid/x_ghp_abcdefghijklmnopqrstuvwxyz/repository.git",
                "https://example.invalid/ghp%5Fabcdefghijklmnopqrstuvwxyz/repository.git",
                "https://example.invalid/ghp%255Fabcdefghijklmnopqrstuvwxyz/repository.git",
                "git@example.invalid:glpat-abcdefghijklmnopqrstuvwxyz.git",
                "git://example.invalid/team/archive.git",
                temporaryDirectory.resolve("bad<name>.git").toString());
        for (String remote : rejected) {
            assertThrows(IllegalArgumentException.class, () -> RemoteUrlPolicy.validateConfiguredPlain(remote), remote);
        }
    }

    @Test
    void permitsTheReadOnlyGitProtocolOnlyForImports() {
        String importUrl = "git://example.invalid/team/archive.git";

        assertEquals(importUrl, RemoteUrlPolicy.validatePlain(importUrl));
    }
}
