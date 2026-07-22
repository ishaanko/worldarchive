package dev.ishaankot.worldarchive.storage.zip;

import java.util.List;

/** Deterministic result from recursively inspecting an import folder. */
public record ZipImportScan(
        List<ZipImportCandidate> candidates,
        List<ZipImportIssue> issues) {
    public ZipImportScan {
        candidates = List.copyOf(candidates);
        issues = List.copyOf(issues);
    }
}
