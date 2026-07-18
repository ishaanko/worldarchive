package dev.ishaankot.worldarchive.config;

/** Refuses configuration written by a newer, potentially incompatible WorldArchive version. */
public final class UnsupportedSchemaVersionException extends ConfigurationException {
    private final int schemaVersion;

    public UnsupportedSchemaVersionException(int schemaVersion) {
        super("Unsupported future WorldArchive configuration schema: " + schemaVersion);
        this.schemaVersion = schemaVersion;
    }

    public int schemaVersion() {
        return schemaVersion;
    }
}
