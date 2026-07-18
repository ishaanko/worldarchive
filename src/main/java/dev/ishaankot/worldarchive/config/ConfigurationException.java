package dev.ishaankot.worldarchive.config;

import java.io.IOException;

/** Checked failure for configuration that cannot be interpreted safely. */
public class ConfigurationException extends IOException {
    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
