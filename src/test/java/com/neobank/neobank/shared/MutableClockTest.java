package com.neobank.neobank.shared;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MutableClockTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void followsSystemTimeUntilSet() {
        Instant before = Instant.now();
        Instant now = clock.instant();
        Instant after = Instant.now();

        assertThat(now).isBetween(before, after);
    }

    @Test
    void returnsFixedInstantOnceSet() {
        Instant fixed = Instant.parse("2026-01-15T10:00:00Z");

        clock.setInstant(fixed);

        assertThat(clock.instant()).isEqualTo(fixed);
        assertThat(clock.instant()).isEqualTo(fixed);
    }

    @Test
    void advancesFixedInstant() {
        clock.setInstant(Instant.parse("2026-01-15T10:00:00Z"));

        clock.advance(Duration.ofDays(31));

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-02-15T10:00:00Z"));
    }

    @Test
    void resetReturnsToSystemTime() {
        clock.setInstant(Instant.parse("2000-01-01T00:00:00Z"));

        clock.reset();

        assertThat(clock.instant()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void isAlwaysUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void rejectsNullInstant() {
        assertThatThrownBy(() -> clock.setInstant(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
