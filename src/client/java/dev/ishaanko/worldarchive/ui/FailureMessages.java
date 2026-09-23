package dev.ishaanko.worldarchive.ui;

import dev.ishaanko.worldarchive.model.SafeText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Turns a failure into the short, safe text that every backup, storage and import screen shows. */
final class FailureMessages {
    private static final int MAXIMUM_LENGTH = 240;

    private FailureMessages() {
    }

    /** The safe message of the failure's real cause, or the cause's type name when it has none. */
    static String text(Throwable failure) {
        return SafeText.from(failure, SafeText.unwrap(failure).getClass().getSimpleName(), MAXIMUM_LENGTH);
    }

    /** The failure as a red status line. */
    static Component status(Throwable failure) {
        return Component.literal(text(failure)).withStyle(ChatFormatting.RED);
    }
}
