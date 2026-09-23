package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldSelection;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;

/**
 * Resolves the WorldArchive identity of a world picked in a vanilla world screen, for the
 * WorldArchive buttons on that screen. The newest request wins; the answer to an older selection
 * is dropped.
 */
final class SelectedWorldResolver {
    private final ScreenCalls calls;

    SelectedWorldResolver(Screen screen) {
        calls = new ScreenCalls(screen);
    }

    /**
     * Resolves {@code selection}. {@code resolved} receives the world; {@code unavailable} receives
     * the text the buttons show when WorldArchive cannot back the world up.
     */
    void resolve(
            BackupClientFacade facade,
            BackupWorldSelection selection,
            Consumer<BackupWorldContext> resolved,
            Consumer<String> unavailable) {
        calls.dropPending();
        calls.start(() -> facade.resolveWorld(selection), world -> {
            if (world.matches(selection)) {
                resolved.accept(world);
            } else {
                unavailable.accept("World identity did not match the selection");
            }
        }, failure -> unavailable.accept(FailureMessages.text(failure)));
    }

    /** Drops the answer of a request that is still running. */
    void cancel() {
        calls.dropPending();
    }
}
