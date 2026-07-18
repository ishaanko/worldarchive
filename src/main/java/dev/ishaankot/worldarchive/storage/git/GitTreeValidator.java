package dev.ishaankot.worldarchive.storage.git;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rejects tree entries that cannot be restored as ordinary world files. */
final class GitTreeValidator {
    private GitTreeValidator() {
    }

    static void validate(String treeOutput) throws GitStorageException {
        parse(treeOutput);
    }

    static List<GitTreeEntry> parse(String treeOutput) throws GitStorageException {
        List<GitTreeEntry> entries = new ArrayList<>();
        Map<String, PathKind> collisionKinds = new HashMap<>();
        for (String entry : treeOutput.split("\u0000", -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            int firstSpace = entry.indexOf(' ');
            int tab = entry.indexOf('\t');
            int secondSpace = entry.indexOf(' ', firstSpace + 1);
            if (firstSpace < 0 || secondSpace < 0 || tab < 0 || secondSpace > tab) {
                throw new GitStorageException("Git tree contains a malformed entry");
            }
            String mode = entry.substring(0, firstSpace);
            String type = entry.substring(firstSpace + 1, secondSpace);
            String objectId = entry.substring(secondSpace + 1, tab);
            String path = entry.substring(tab + 1);
            if (mode.equals("120000")) {
                throw new GitStorageException("Git snapshot contains a symbolic link");
            }
            if (mode.equals("160000")) {
                throw new GitStorageException("Git snapshot contains a nested Git repository link");
            }
            if (!mode.equals("100644") && !mode.equals("100755")) {
                throw new GitStorageException("Git snapshot contains an unsupported tree mode");
            }
            if (!type.equals("blob")) {
                throw new GitStorageException("Git snapshot contains an unsupported tree object");
            }
            try {
                GitPortablePath.validate(path);
                if (!path.equals(GitBackupBackend.MANIFEST_PATH)) {
                    GitPortablePath.requireNotInternalRoot(path);
                }
                registerPortablePath(path, collisionKinds);
                entries.add(new GitTreeEntry(mode, objectId, path));
            } catch (IllegalArgumentException exception) {
                throw new GitStorageException("Git tree contains an unsafe or colliding path", exception);
            }
        }
        return List.copyOf(entries);
    }

    private static void registerPortablePath(
            String portable,
            Map<String, PathKind> collisionKinds) {
        String[] segments = portable.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < segments.length; index++) {
            if (!prefix.isEmpty()) {
                prefix.append('/');
            }
            prefix.append(segments[index]);
            String key = GitPortablePath.collisionKey(prefix.toString());
            PathKind expected = index == segments.length - 1 ? PathKind.FILE : PathKind.DIRECTORY;
            PathKind previous = collisionKinds.putIfAbsent(key, expected);
            if (previous != null && (previous != expected || expected == PathKind.FILE)) {
                throw new IllegalArgumentException("Git tree paths collide on another platform");
            }
        }
    }

    private enum PathKind {
        DIRECTORY,
        FILE
    }
}
