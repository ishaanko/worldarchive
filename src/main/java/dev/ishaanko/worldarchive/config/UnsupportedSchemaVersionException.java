package dev.ishaanko.worldarchive.config;

/**
 * Refuses settings written with a schema this version cannot read: one from a newer
 * WorldArchive, or one older than WorldArchive 0.1.1.
 */
public final class UnsupportedSchemaVersionException extends ConfigurationException {
    private final int schemaVersion;

    public UnsupportedSchemaVersionException(int schemaVersion) {
        super(message(schemaVersion));
        this.schemaVersion = schemaVersion;
    }

    /** True when a newer WorldArchive wrote the settings. */
    public boolean newer() {
        return schemaVersion > WorldArchiveConfig.CURRENT_SCHEMA_VERSION;
    }

    private static String message(int schemaVersion) {
        return schemaVersion > WorldArchiveConfig.CURRENT_SCHEMA_VERSION
                ? "The settings were saved by a newer WorldArchive (schema " + schemaVersion
                        + "). Update WorldArchive, or reset the settings."
                : "The settings are from WorldArchive 0.1.0 or older (schema " + schemaVersion
                        + ") and can no longer be read. Reset the settings, then use Import with the old"
                        + " Git repository to bring back its backups.";
    }
}
