package dev.ishaanko.worldarchive.settings;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.model.WorldId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The result of checking a {@link SettingsDraft}: the configuration to save, or what is wrong.
 * Problems with the Git and ZIP tabs are keyed by field; problems with one world's own fields
 * are keyed by that world and field, so two worlds with problems each keep their own.
 */
public record SettingsValidation(
        Optional<WorldArchiveConfig> config,
        Map<SettingsField, String> issues,
        Map<WorldId, Map<SettingsField, String>> worldIssues) {
    public SettingsValidation {
        Objects.requireNonNull(config, "config");
        issues = Collections.unmodifiableMap(new LinkedHashMap<>(issues));
        Map<WorldId, Map<SettingsField, String>> copied = new LinkedHashMap<>();
        worldIssues.forEach((world, fields) ->
                copied.put(world, Collections.unmodifiableMap(new LinkedHashMap<>(fields))));
        worldIssues = Collections.unmodifiableMap(copied);
        if ((issues.isEmpty() && worldIssues.isEmpty()) != config.isPresent()) {
            throw new IllegalArgumentException("A settings result must contain either a config or issues");
        }
    }

    public static SettingsValidation valid(WorldArchiveConfig config) {
        return new SettingsValidation(Optional.of(config), Map.of(), Map.of());
    }

    public static SettingsValidation invalid(
            Map<SettingsField, String> issues,
            Map<WorldId, Map<SettingsField, String>> worldIssues) {
        return new SettingsValidation(Optional.empty(), issues, worldIssues);
    }

    public boolean isValid() {
        return config.isPresent();
    }

    public Optional<String> issue(SettingsField field) {
        return Optional.ofNullable(issues.get(Objects.requireNonNull(field, "field")));
    }

    public Optional<String> issue(WorldId world, SettingsField field) {
        return Optional.ofNullable(worldIssues.getOrDefault(world, Map.of()).get(field));
    }

    /** The worlds that have a problem, for marking them in the world list. */
    public Set<WorldId> invalidWorlds() {
        return worldIssues.keySet();
    }

    /** The first world problem, in world order. */
    public Optional<String> firstWorldIssue() {
        return worldIssues.values().stream().flatMap(fields -> fields.values().stream()).findFirst();
    }

    /** The first problem of any kind: the Git and ZIP tabs first, then the worlds. */
    public Optional<String> firstIssue() {
        return issues.values().stream().findFirst().or(this::firstWorldIssue);
    }
}
