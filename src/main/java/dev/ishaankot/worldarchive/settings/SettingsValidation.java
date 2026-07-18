package dev.ishaankot.worldarchive.settings;

import dev.ishaankot.worldarchive.config.WorldArchiveConfig;
import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A validated configuration candidate plus field-addressable errors. */
public record SettingsValidation(
        Optional<WorldArchiveConfig> config,
        Map<SettingsField, String> issues) {
    public SettingsValidation {
        Objects.requireNonNull(config, "config");
        Map<SettingsField, String> safeIssues = new LinkedHashMap<>();
        Objects.requireNonNull(issues, "issues").forEach((field, message) -> {
            Objects.requireNonNull(field, "issue field");
            String safeMessage = SensitiveDataRedactor.redact(
                            Objects.requireNonNull(message, "issue message"))
                    .strip();
            if (safeMessage.isEmpty()
                    || safeMessage.length() > 512
                    || safeMessage.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Settings issue message is invalid");
            }
            safeIssues.put(field, safeMessage);
        });
        issues = Collections.unmodifiableMap(safeIssues);
        if (issues.isEmpty() != config.isPresent()) {
            throw new IllegalArgumentException("A settings result must contain either a config or issues");
        }
    }

    public boolean isValid() {
        return config.isPresent();
    }

    public Optional<String> issue(SettingsField field) {
        return Optional.ofNullable(issues.get(Objects.requireNonNull(field, "field")));
    }

    public Optional<String> firstIssue() {
        return issues.values().stream().findFirst();
    }
}
