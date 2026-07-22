package dev.ishaankot.worldarchive.importing;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable preview token; execution revalidates every pinned artifact. */
public record ImportPreview(
        UUID token,
        ImportKind kind,
        String source,
        List<ImportPreviewItem> items,
        List<String> issues) {
    public ImportPreview {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(kind, "kind");
        source = Objects.requireNonNull(source, "source");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public long actionableCount() {
        return items.stream()
                .filter(item -> item.disposition() == ImportDisposition.ADD
                        || item.disposition() == ImportDisposition.MERGE)
                .count();
    }
}
