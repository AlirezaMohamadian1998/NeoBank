package com.neobank.neobank.fx;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "fx.rate-lock")
@Validated
public record FxRateLockProperties(
        @NotNull
        @DurationMin(minutes = 1)
        Duration duration
) {}
