package dev.ishaanko.worldarchive.model;

import com.google.gson.JsonObject;
import dev.ishaanko.worldarchive.support.JsonFields;
import java.util.Optional;

/**
 * The JSON fields of a {@link BackupManifest}, in the order that the catalog, the Git snapshot
 * manifest and the ZIP manifest write them. The bytes of every stored manifest depend on the
 * names and the order, so change neither. Callers add their own fields before or after.
 */
public final class BackupManifestJson {
    private BackupManifestJson() {
    }

    /** Appends the manifest fields to {@code target}; an absent label or game version is omitted. */
    public static void write(BackupManifest manifest, JsonObject target) {
        target.addProperty("formatVersion", manifest.formatVersion());
        target.addProperty("backupId", manifest.backupId().toString());
        target.addProperty("worldId", manifest.worldId().toString());
        target.addProperty("worldName", manifest.worldName());
        manifest.label().ifPresent(label -> target.addProperty("label", label));
        target.addProperty("createdAt", manifest.createdAt().toString());
        target.addProperty("trigger", manifest.trigger().name());
        target.addProperty("sourceFileCount", manifest.sourceFileCount());
        target.addProperty("sourceByteCount", manifest.sourceByteCount());
        target.addProperty("changedFileCount", manifest.changedFileCount());
        target.addProperty("contentSha256", manifest.contentSha256());
        target.addProperty("inventorySha256", manifest.inventorySha256());
        manifest.gameVersion().ifPresent(stamp -> {
            JsonObject version = new JsonObject();
            version.addProperty("name", stamp.name());
            version.addProperty("dataVersion", stamp.dataVersion());
            target.add("gameVersion", version);
        });
    }

    /**
     * Reads the fields that {@link #write} adds. A missing or mistyped field throws the
     * caller's exception; a value the model rejects throws {@link IllegalArgumentException}.
     */
    public static <E extends Exception> BackupManifest read(JsonObject source, JsonFields<E> fields)
            throws E {
        Optional<JsonObject> version = fields.optionalObject(source, "gameVersion");
        Optional<GameVersionStamp> gameVersion = Optional.empty();
        if (version.isPresent()) {
            gameVersion = Optional.of(new GameVersionStamp(
                    fields.requiredString(version.get(), "name"),
                    fields.requiredInt(version.get(), "dataVersion")));
        }
        return new BackupManifest(
                fields.requiredInt(source, "formatVersion"),
                BackupId.parse(fields.requiredString(source, "backupId")),
                WorldId.parse(fields.requiredString(source, "worldId")),
                fields.requiredString(source, "worldName"),
                fields.optionalString(source, "label"),
                fields.requiredInstant(source, "createdAt"),
                fields.requiredEnum(source, "trigger", BackupTrigger.class),
                fields.requiredLong(source, "sourceFileCount"),
                fields.requiredLong(source, "sourceByteCount"),
                fields.requiredLong(source, "changedFileCount"),
                fields.requiredString(source, "contentSha256"),
                fields.requiredString(source, "inventorySha256"),
                gameVersion);
    }
}
