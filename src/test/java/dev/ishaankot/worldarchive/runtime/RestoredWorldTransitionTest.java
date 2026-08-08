package dev.ishaankot.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RestoredWorldTransitionTest {
    @Test
    void disconnectsActiveWorldBeforeContinuing() {
        List<String> events = new ArrayList<>();

        RestoredWorldTransition.afterLeavingActiveWorld(
                () -> {
                    events.add("active");
                    return true;
                },
                () -> events.add("disconnect"),
                leftActiveWorld -> events.add("continue:" + leftActiveWorld));

        assertEquals(List.of("active", "disconnect", "continue:true"), events);
    }

    @Test
    void continuesWithoutDisconnectWhenNoWorldIsActive() {
        List<String> events = new ArrayList<>();

        RestoredWorldTransition.afterLeavingActiveWorld(
                () -> false,
                () -> events.add("disconnect"),
                leftActiveWorld -> events.add("continue:" + leftActiveWorld));

        assertEquals(List.of("continue:false"), events);
    }

    @Test
    void doesNotContinueWhenDisconnectFails() {
        List<String> events = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("disconnect failed");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> RestoredWorldTransition.afterLeavingActiveWorld(
                        () -> true,
                        () -> {
                            events.add("disconnect");
                            throw failure;
                        },
                        ignored -> events.add("continue")));

        assertEquals(failure, thrown);
        assertEquals(List.of("disconnect"), events);
    }
}
