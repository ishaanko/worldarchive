package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettingsScreenStateTest {
    @Test
    void rejectsStaleCallbacksAcrossOperationsAndScreenLifecycles() {
        SettingsScreenState state = new SettingsScreenState();
        SettingsScreenState.LifecycleToken beforeInitialization = state.lifecycleToken();

        assertTrue(state.acceptsBeforeInitialization(beforeInitialization));
        assertFalse(state.acceptsActive(beforeInitialization));

        state.activate();
        SettingsScreenState.RevisionToken firstValidation = state.nextValidation();
        SettingsScreenState.RevisionToken currentValidation = state.nextValidation();
        SettingsScreenState.RevisionToken health = state.nextHealthProbe();

        assertFalse(state.acceptsValidation(firstValidation));
        assertTrue(state.acceptsValidation(currentValidation));
        assertTrue(state.acceptsHealthProbe(health));
        assertTrue(state.beginSave().isPresent());
        assertTrue(state.beginSave().isEmpty());

        state.deactivate();

        assertFalse(state.acceptsActive(beforeInitialization));
        assertFalse(state.acceptsValidation(currentValidation));
        assertFalse(state.acceptsHealthProbe(health));
        assertFalse(state.saving());

        state.activate();
        SettingsScreenState.LifecycleToken reopened = state.lifecycleToken();
        assertTrue(state.acceptsActive(reopened));

        state.close();
        assertFalse(state.acceptsBeforeInitialization(reopened));
        assertFalse(state.acceptsActive(reopened));
    }
}
