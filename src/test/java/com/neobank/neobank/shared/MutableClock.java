package com.neobank.neobank.shared;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public class MutableClock extends Clock {

    private final AtomicReference<Instant> fixedInstant = new AtomicReference<>();

    public void setInstant(Instant instant) {
        if(instant == null) {
            throw new IllegalArgumentException("Instant must not be null");
        }

        fixedInstant.set(instant);
    }

    public void advance(Duration duration) {
        if(duration == null) {
            throw new IllegalArgumentException("Duration must not be null");
        }

        fixedInstant.updateAndGet(current -> (current == null ? Instant.now() : current).plus(duration));
    }

    public void reset() {
        fixedInstant.set(null);
    }

    @Override
    public Instant instant() {
        Instant instant = fixedInstant.get();
        return instant == null ? Instant.now() : instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("MutableClock is always UTC");
    }
}
