package dev.ishaanko.worldarchive.storage.git;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.model.BackupManifest;
import dev.ishaanko.worldarchive.model.BackupManifestJson;
import dev.ishaanko.worldarchive.support.JsonFields;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Strict JSON codec for the manifest file in every snapshot tree. Stored snapshots depend on the
 * exact bytes, so the field order and the pretty printing never change.
 */
final class GitSnapshotManifestCodec {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final JsonFields<IOException> FIELDS = new JsonFields<>(IOException::new);

    private GitSnapshotManifestCodec() {
    }

    static byte[] encode(GitSnapshotManifest snapshotManifest) {
        JsonObject root = new JsonObject();
        root.addProperty("storageFormatVersion", snapshotManifest.storageFormatVersion());
        BackupManifestJson.write(snapshotManifest.manifest(), root);
        root.addProperty("sourceIdentity", snapshotManifest.sourceIdentity());
        JsonArray patterns = new JsonArray();
        snapshotManifest.lfsPatterns().forEach(patterns::add);
        root.add("lfsPatterns", patterns);
        return (GSON.toJson(root) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static GitSnapshotManifest decode(byte[] encoded) throws IOException {
        JsonObject root = FIELDS.parseObject(new String(encoded, StandardCharsets.UTF_8), "Git snapshot manifest");
        try {
            BackupManifest manifest = BackupManifestJson.read(root, FIELDS);
            return new GitSnapshotManifest(
                    FIELDS.requiredInt(root, "storageFormatVersion"),
                    manifest,
                    FIELDS.requiredStrings(root, "lfsPatterns"),
                    FIELDS.requiredString(root, "sourceIdentity"));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Git snapshot manifest contains invalid data", exception);
        }
    }
}
