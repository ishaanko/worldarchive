package dev.ishaanko.worldarchive.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Something the Worlds tab tells the player about a world folder after the saves folder is scanned. */
public sealed interface WorldNotice {
    /** The folder carried the identity of {@code original}, so it is a copy; it now has its own backup history. */
    record CopyGotOwnIdentity(Path copy, Path original) implements WorldNotice {
        public CopyGotOwnIdentity {
            Objects.requireNonNull(copy, "copy");
            Objects.requireNonNull(original, "original");
        }
    }

    /**
     * The folders carry one identity and none is the folder the settings know, so none of them
     * is added. The one the player opens first keeps the backup history; the next scan gives
     * the others their own.
     */
    record SharedIdentity(List<Path> folders) implements WorldNotice {
        public SharedIdentity {
            folders = List.copyOf(folders);
        }
    }

    /** A folder that had settings now carries another identity, so its old settings were not carried over. */
    record IdentityReplaced(Path folder) implements WorldNotice {
        public IdentityReplaced {
            Objects.requireNonNull(folder, "folder");
        }
    }

    /** The folder's identity file could not be read or written, so the folder is left out. */
    record IdentityUnreadable(Path folder, String reason) implements WorldNotice {
        public IdentityUnreadable {
            Objects.requireNonNull(folder, "folder");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
