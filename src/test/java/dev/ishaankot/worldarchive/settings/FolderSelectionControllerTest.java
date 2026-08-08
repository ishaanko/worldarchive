package dev.ishaankot.worldarchive.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaankot.worldarchive.model.SensitiveDataRedactor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FolderSelectionControllerTest {
    @Test
    void cancellationLeavesTypedValueUntouched() {
        FolderSelectionController controller = new FolderSelectionController();
        FolderSelectionController.Request request = controller.begin("C:\\Backups");

        FolderSelectionController.Application application = controller.apply(
                request,
                new FolderSelectionResult.Cancelled(),
                "C:\\Backups");

        assertTrue(application.applied());
        assertEquals("C:\\Backups", application.value());
        assertTrue(application.message().isEmpty());
    }

    @Test
    void manualEditMakesPendingSelectionStale() {
        FolderSelectionController controller = new FolderSelectionController();
        FolderSelectionController.Request request = controller.begin("");
        controller.noteManualEdit();

        FolderSelectionController.Application application = controller.apply(
                request,
                new FolderSelectionResult.Selected(Path.of("C:\\OldSelection")),
                "C:\\NewTypedValue");

        assertFalse(application.applied());
        assertEquals("C:\\NewTypedValue", application.value());
    }

    @Test
    void failurePreservesValueAndReturnsFallbackMessage() {
        FolderSelectionController controller = new FolderSelectionController();
        FolderSelectionController.Request request = controller.begin("");

        FolderSelectionController.Application application = controller.apply(
                request,
                new FolderSelectionResult.Unavailable("Type an absolute path instead"),
                "typed");

        assertTrue(application.applied());
        assertEquals("typed", application.value());
        assertEquals("Type an absolute path instead", application.message().orElseThrow());
    }

    @Test
    void pickerFailureRedactsCredentialsBeforeReachingTheController() {
        FolderSelectionController controller = new FolderSelectionController();
        FolderSelectionController.Request request = controller.begin("");

        FolderSelectionController.Application application = controller.apply(
                request,
                new FolderSelectionResult.Failed(
                        "Could not open https://user:password@example.com/folder"),
                "typed");

        assertEquals(
                "Could not open https://" + SensitiveDataRedactor.REDACTED + "@example.com/folder",
                application.message().orElseThrow());
    }
}
