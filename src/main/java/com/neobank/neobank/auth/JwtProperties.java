package com.neobank.neobank.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;


@ConfigurationProperties(prefix = "security.jwt")
@Validated
public record JwtProperties(
        @NotBlank
        String issuer,

        @NotNull
        Duration accessTokenTtl,

        @NotBlank
        String secret
) {
        public JwtProperties {
                if (accessTokenTtl != null && (accessTokenTtl.isZero() || accessTokenTtl.isNegative())) {
                        throw new IllegalArgumentException("Duration must be positive");
                }
        }
}
