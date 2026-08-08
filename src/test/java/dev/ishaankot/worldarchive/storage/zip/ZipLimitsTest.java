package dev.ishaankot.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ZipLimitsTest {
    @Test
    void boundsUncompressedDataByArchiveSizeAndAbsoluteLimit() {
        assertEquals(
                256L * 1_024 * 1_024,
                ZipLimits.maximumUncompressedBytes(1));
        assertEquals(
                2L * 1_024 * 1_024 * 1_024 * 1_000,
                ZipLimits.maximumUncompressedBytes(
                        2L * 1_024 * 1_024 * 1_024));
        assertEquals(
                ZipLimits.MAXIMUM_UNCOMPRESSED_BYTES,
                ZipLimits.maximumUncompressedBytes(Long.MAX_VALUE));
    }
}
