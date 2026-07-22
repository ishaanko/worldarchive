package dev.ishaankot.worldarchive.storage.git;

import java.util.Objects;

/** Remote ref and its observed commit, used for guarded snapshot mutation. */
record RemoteSnapshotRef(String refName, String commitId) {
    RemoteSnapshotRef {
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(commitId, "commitId");
    }
}
