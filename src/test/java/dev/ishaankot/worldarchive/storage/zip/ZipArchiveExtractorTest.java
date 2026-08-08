package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ZipArchiveExtractorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void insufficientFreeSpaceFailsBeforeArchiveAccess() throws Exception {
        Path staging = Files.createDirectory(temporaryDirectory.resolve("staging"));
        long usableSpace = Files.getFileStore(staging).getUsableSpace();
        Assumptions.assumeTrue(usableSpace < Long.MAX_VALUE);
        ZipInventory inventory = ZipInventory.create(List.of(new ZipInventoryEntry(
                "too-large.dat", Long.MAX_VALUE, "0".repeat(64))));

        ZipBackupException failure = assertThrows(
                ZipBackupException.class,
                () -> ZipArchiveExtractor.extract(
                        temporaryDirectory.resolve("missing.zip"),
                        ZipArchiveExtractor.openEmpty(staging),
                        inventory,
                        new ZipStoreHooks() {
                        }));

        assertTrue(failure.getMessage().contains("insufficient free space"));
    }
}
