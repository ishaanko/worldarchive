package dev.ishaankot.worldarchive.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class NoCatchUpScheduleTest {
    private static final Instant START = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void schedulesFromObservationTimeWithoutCatchUpBursts() {
        NoCatchUpSchedule schedule = new NoCatchUpSchedule(Duration.ofMinutes(30), START);

        assertTrue(schedule.poll(START.plusSeconds(1_799)).isEmpty());
        assertEquals(START.plusSeconds(1_800), schedule.poll(START.plus(Duration.ofDays(2))).orElseThrow());
        assertEquals(
                START.plus(Duration.ofDays(2)).plus(Duration.ofMinutes(30)),
                schedule.nextRunAt());
        assertTrue(schedule.poll(START.plus(Duration.ofDays(2)).plusSeconds(1)).isEmpty());
    }

    @Test
    void resetStartsFreshInterval() {
        NoCatchUpSchedule schedule = NoCatchUpSchedule.minutes(30, START);
        Instant reopened = START.plus(Duration.ofDays(3));

        schedule.reset(reopened);

        assertEquals(reopened.plus(Duration.ofMinutes(30)), schedule.nextRunAt());
        assertTrue(schedule.poll(reopened).isEmpty());
    }
}
