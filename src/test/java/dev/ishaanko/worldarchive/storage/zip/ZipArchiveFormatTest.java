package dev.ishaanko.worldarchive.storage.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupManifestJson;
import dev.ishaanko.worldarchive.model.GoldenFixtures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZipArchiveFormatTest {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @Test
    void encodesManifestsExactlyAsStoredArchivesHoldThem() throws Exception {
        for (BackupManifest manifest : List.of(GoldenFixtures.labeledManifest(), GoldenFixtures.plainManifest())) {
            String expected = manifest.label().isPresent()
                    ? GoldenFixtures.LABELED_MANIFEST_JSON
                    : GoldenFixtures.PLAIN_MANIFEST_JSON;
            JsonObject shared = new JsonObject();
            BackupManifestJson.write(manifest, shared);

            assertEquals(expected, new String(ZipArchiveFormat.encodeManifest(manifest), StandardCharsets.UTF_8));
            assertEquals(expected, PRETTY.toJson(shared) + "\n");
            assertEquals(manifest, ZipArchiveFormat.decodeManifest(ZipArchiveFormat.encodeManifest(manifest)));
        }
    }

    @Test
    void aManifestFromANewerWorldArchiveAsksForAnUpdate() {
        String newer = GoldenFixtures.PLAIN_MANIFEST_JSON.replace(
                "\"formatVersion\": 1", "\"formatVersion\": " + (BackupManifest.CURRENT_FORMAT_VERSION + 1));

        ZipArchiveDamagedException failure = assertThrows(ZipArchiveDamagedException.class,
                () -> ZipArchiveFormat.decodeManifest(newer.getBytes(StandardCharsets.UTF_8)));

        assertTrue(failure.getMessage().contains("Update WorldArchive"), failure.getMessage());
    }
}
