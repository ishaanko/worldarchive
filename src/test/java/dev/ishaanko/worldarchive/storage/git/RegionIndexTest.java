package dev.ishaanko.worldarchive.storage.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The region index is exactly five lines, and anything else is a damaged snapshot. */
class RegionIndexTest {
    private static final String SOURCE = "ab".repeat(32);

    private static final String STORED = "cd".repeat(32);

    private static final String TEXT = "worldarchive-region-v1\n"
            + "source-sha256 " + SOURCE + "\n"
            + "source-size 1048576\n"
            + "stored-sha256 " + STORED + "\n"
            + "stored-size 8192\n";

    @Test
    void roundTripsTheExactText() throws GitStorageException {
        RegionIndex index = new RegionIndex(SOURCE, 1_048_576, STORED, 8_192);

        assertEquals(TEXT, new String(index.encode(), StandardCharsets.UTF_8));
        assertEquals(index, RegionIndex.decode(TEXT.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMalformedText() {
        List<String> malformed = List.of(
                "",
                TEXT.replace("-v1", "-v2"),
                TEXT.replace("\n", "\r\n"),
                TEXT.substring(0, TEXT.length() - 1),
                TEXT.replace(SOURCE, SOURCE.toUpperCase()),
                TEXT.replace(STORED, STORED.substring(1)),
                TEXT.replace("size 8192", "size 08192"),
                TEXT.replace("size 8192", "size -8192"),
                TEXT.replace("size 8192", "size 9999999999999999999"),
                TEXT.replace("source-size 1048576\n", ""),
                TEXT.replace("source-size", "stored-size"),
                TEXT + "\n",
                TEXT + "extra 1\n",
                TEXT + " ".repeat(RegionIndex.MAXIMUM_BYTES));

        for (String text : malformed) {
            assertThrows(GitStorageException.class, () -> RegionIndex.decode(text.getBytes(StandardCharsets.UTF_8)),
                    text);
        }
    }
}
