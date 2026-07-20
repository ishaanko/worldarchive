package dev.ishaankot.worldarchive.settings;

/** Top-level destinations and world configuration pages. */
enum SettingsPage {
    GIT("screen.worldarchive.settings.tab.git"),
    ZIP("screen.worldarchive.settings.tab.zip"),
    WORLDS("screen.worldarchive.settings.tab.worlds");

    private final String translationKey;

    SettingsPage(String translationKey) {
        this.translationKey = translationKey;
    }

    String translationKey() {
        return translationKey;
    }
}
