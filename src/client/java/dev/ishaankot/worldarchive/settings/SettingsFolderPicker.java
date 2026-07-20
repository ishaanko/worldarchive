package dev.ishaankot.worldarchive.settings;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Owns one cancellable native folder-picker request and its stale-result guard. */
final class SettingsFolderPicker {
    private final NativeFolderChooser chooser;

    private final FolderSelectionController selection = new FolderSelectionController();

    private final String titleKey;

    private CancellableRequest<FolderSelectionResult> pending;

    SettingsFolderPicker(NativeFolderChooser chooser, String titleKey) {
        this.chooser = Objects.requireNonNull(chooser, "chooser");
        this.titleKey = Objects.requireNonNull(titleKey, "titleKey");
    }

    boolean choosing() {
        return pending != null;
    }

    void noteManualEdit() {
        selection.noteManualEdit();
    }

    void choose(
            SettingsScreenState screenState,
            Minecraft minecraft,
            Supplier<String> currentValue,
            Consumer<FolderSelectionController.Application> completion) {
        Objects.requireNonNull(screenState, "screenState");
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(currentValue, "currentValue");
        Objects.requireNonNull(completion, "completion");
        cancel();
        FolderSelectionController.Request request = selection.begin(currentValue.get());
        SettingsScreenState.LifecycleToken lifecycleToken = screenState.lifecycleToken();
        CancellableRequest<FolderSelectionResult> picker = chooser.chooseFolder(
                Component.translatable(titleKey).getString(),
                request.initialDirectory());
        pending = picker;
        picker.completion().whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (pending != picker || !screenState.acceptsActive(lifecycleToken)) {
                return;
            }
            pending = null;
            FolderSelectionResult outcome = throwable == null && result != null
                    ? result
                    : new FolderSelectionResult.Failed(Component.translatable(
                            "screen.worldarchive.settings.folder_picker_failed").getString());
            completion.accept(selection.apply(request, outcome, currentValue.get()));
        }));
    }

    void cancel() {
        CancellableRequest<FolderSelectionResult> request = pending;
        pending = null;
        if (request != null) {
            request.cancel();
        }
    }
}
