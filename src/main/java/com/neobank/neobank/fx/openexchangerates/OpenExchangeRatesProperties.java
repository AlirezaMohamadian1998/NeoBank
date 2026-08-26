package com.neobank.neobank.fx.openexchangerates;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "fx.provider")
@Validated
public record OpenExchangeRatesProperties(

        @NotNull
        URI baseUrl,

        @NotBlank
        String appId,

        @NotNull
        Duration connectTimeout,

        @NotNull
        Duration readTimeout
) {
    public OpenExchangeRatesProperties {
        if (connectTimeout != null && (connectTimeout.isNegative() || connectTimeout.isZero())) {
            throw new IllegalArgumentException("Connection timeout must be positive");
        }

        if (readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero())) {
            throw new IllegalArgumentException("Read timeout must be positive");
        }
    }
}
