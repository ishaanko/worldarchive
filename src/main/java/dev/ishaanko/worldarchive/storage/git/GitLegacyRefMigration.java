package dev.ishaanko.worldarchive.storage.git;

import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Migrates legacy (non-hierarchical) remote snapshot refs onto the current naming scheme. */
final class GitLegacyRefMigration {
    private final GitBackendSettings settings;

    private final GitCommands commands;

    private final GitRefStore refs;

    private final SnapshotLister lister;

    GitLegacyRefMigration(
            GitBackendSettings settings,
            GitCommands commands,
            GitRefStore refs,
            SnapshotLister lister) {
        this.settings = settings;
        this.commands = commands;
        this.refs = refs;
        this.lister = lister;
    }

    void migrateLegacyRemoteRefs(WorldId worldId)
            throws IOException, InterruptedException, GitStorageException {
        Map<String, String> legacy = refs.resolveRemotePattern(
                "refs/heads/worldarchive/" + worldId + "/*");
        if (legacy.isEmpty()) {
            return;
        }
        Map<String, GitSnapshot> local = new LinkedHashMap<>();
        for (GitSnapshot snapshot : lister.list(worldId)) {
            local.put(snapshot.refName(), snapshot);
        }
        List<String> arguments = new ArrayList<>(List.of(
                "push",
                "--atomic",
                "--porcelain",
                settings.remoteName()));
        for (Map.Entry<String, String> entry : legacy.entrySet()) {
            GitSnapshot snapshot = local.get(entry.getKey());
            if (snapshot == null) {
                continue;
            }
            if (!snapshot.commitId().equals(entry.getValue())) {
                throw new GitStorageException(
                        "Legacy GitHub backup branch no longer matches local storage");
            }
            arguments.add(snapshot.refName() + ":" + GitRemoteSnapshotRef.current(snapshot));
            arguments.add(":" + entry.getKey());
        }
        if (arguments.size() > 4) {
            commands.checked(arguments, settings.repository(), Map.of(), new byte[0]);
        }
    }

    /** Lists the current local snapshots for a world, without acquiring any repository lock. */
    @FunctionalInterface
    interface SnapshotLister {
        List<GitSnapshot> list(WorldId worldId)
                throws IOException, InterruptedException, GitStorageException;
    }
}
