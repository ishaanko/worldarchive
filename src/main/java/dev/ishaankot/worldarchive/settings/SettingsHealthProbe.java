package dev.ishaankot.worldarchive.settings;

/** Injectable blocking probe that callers must dispatch away from Minecraft threads. */
@FunctionalInterface
public interface SettingsHealthProbe {
    SettingsHealthSnapshot probe(SettingsProbeRequest request) throws InterruptedException;
}
