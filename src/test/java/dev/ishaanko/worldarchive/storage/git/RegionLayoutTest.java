package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A region file is bucketed only when every strict-parse rule holds; any other file is stored whole. */
class RegionLayoutTest {
    @TempDir
    Path directory;

    @Test
    void parsesAValidFile() throws IOException {
        byte[] file = valid().bytes();

        Optional<RegionLayout> layout = parse(file);

        assertTrue(layout.isPresent());
        assertEquals(List.of(new RegionEntry(0, 7, 100), new RegionEntry(33, 0, 5000)), layout.get().entries());
    }

    @Test
    void rejectsAFileShorterThanItsTables() throws IOException {
        assertFalse(parse(new byte[RegionLayout.TABLES_BYTES - RegionEntry.SECTOR_BYTES]).isPresent());
    }

    @Test
    void rejectsASizeThatIsNotWholeSectors() throws IOException {
        byte[] file = valid().bytes();
        byte[] longer = new byte[file.length + 1];
        System.arraycopy(file, 0, longer, 0, file.length);

        assertFalse(parse(longer).isPresent());
    }

    @Test
    void rejectsAChunkInTheTables() throws IOException {
        assertFalse(parse(valid().location(5, 1 << 8 | 1).bytes()).isPresent());
    }

    @Test
    void rejectsANonzeroLocationWithNoSectors() throws IOException {
        assertFalse(parse(valid().location(5, 8 << 8).bytes()).isPresent());
    }

    @Test
    void rejectsAChunkPastTheEnd() throws IOException {
        assertFalse(parse(valid().location(5, 7 << 8 | 2).bytes()).isPresent());
    }

    @Test
    void rejectsOverlappingChunks() throws IOException {
        assertFalse(parse(valid().location(5, 6 << 8 | 1).lengthField(6, 10).bytes()).isPresent());
    }

    @Test
    void rejectsALengthOfZero() throws IOException {
        assertFalse(parse(valid().lengthField(2, 0).bytes()).isPresent());
    }

    @Test
    void rejectsALengthLargerThanTheAllocation() throws IOException {
        assertFalse(parse(valid().lengthField(2, RegionEntry.SECTOR_BYTES - 3).bytes()).isPresent());
    }

    @Test
    void acceptsALengthThatFillsTheAllocation() throws IOException {
        assertTrue(parse(valid().lengthField(2, RegionEntry.SECTOR_BYTES - 4).bytes()).isPresent());
    }

    @Test
    void parsesAnEmptyRegion() throws IOException {
        assertEquals(List.of(), parse(new byte[RegionLayout.TABLES_BYTES]).orElseThrow().entries());

        byte[] timestampsOnly = new RegionFileBuilder(2).timestamp(12, 99).timestamp(1000, -5).bytes();
        RegionLayout layout = parse(timestampsOnly).orElseThrow();

        assertEquals(List.of(new RegionEntry(12, 99, 0), new RegionEntry(1000, -5, 0)), layout.entries());
        assertEquals(List.of(1, 13), layout.bucketNumbers());
    }

    @Test
    void recognizesRegionFileNames() {
        assertTrue(RegionLayout.isRegionFileName("r.0.0.mca"));
        assertTrue(RegionLayout.isRegionFileName("r.-1.31.mca"));
        assertTrue(RegionLayout.isRegionFileName("r.-2147483648.2147483647.mca"));
        assertFalse(RegionLayout.isRegionFileName("r.01.0.mca"));
        assertFalse(RegionLayout.isRegionFileName("r.-0.0.mca"));
        assertFalse(RegionLayout.isRegionFileName("r.2147483648.0.mca"));
        assertFalse(RegionLayout.isRegionFileName("r.0.0.mcc"));
        assertFalse(RegionLayout.isRegionFileName("R.0.0.MCA"));
        assertFalse(RegionLayout.isRegionFileName("c.0.0.mcc"));
    }

    /** Two chunks: chunk 0 at sector 2 (1 sector), chunk 33 at sectors 5 to 6, in a file of 8 sectors. */
    private static RegionFileBuilder valid() {
        return new RegionFileBuilder(8)
                .chunk(0, 2, 1, RegionFileBuilder.payload(100, 1))
                .chunk(33, 5, 2, RegionFileBuilder.payload(5000, 2))
                .timestamp(0, 7);
    }

    private Optional<RegionLayout> parse(byte[] file) throws IOException {
        Path path = Files.write(directory.resolve("r.0.0.mca"), file);
        try (FileChannel channel = FileChannel.open(path)) {
            return RegionLayout.parse(channel);
        }
    }
}
