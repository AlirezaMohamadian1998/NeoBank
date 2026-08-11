package com.neobank.neobank.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.*;


class TokenServiceTest {

    private static final String ISSUER = "neo-bank";
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    TokenService tokenService;
    JwtDecoder jwtDecoder;
    Instant fixedInstant;

    @BeforeEach
    void setUp() {
        String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

        JwtProperties jwtProperties = new JwtProperties(ISSUER, ACCESS_TOKEN_TTL, SECRET);
        JwtConfig jwtConfig = new JwtConfig(jwtProperties);

        SecretKey secretKey = jwtConfig.jwtSecretKey();
        JwtEncoder jwtEncoder = jwtConfig.jwtEncoder(secretKey);
        jwtDecoder = jwtConfig.jwtDecoder(secretKey);

        fixedInstant = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Clock fixedClock = Clock.fixed(fixedInstant, Clock.systemUTC().getZone());

        tokenService = new TokenService(jwtProperties, jwtEncoder, fixedClock);
    }

    @Test
    void generateAccessTokenCreatesSignedJwtWithExpectedClaims() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("customer@example.com", null, new ArrayList<>());

        String token = tokenService.generateAccessToken(authentication);
        Jwt jwt = jwtDecoder.decode(token);

        assertThat(jwt.getTokenValue()).isNotEmpty();

        assertThat(jwt.getHeaders())
                .containsEntry("alg", "HS256")
                .containsEntry("typ", "JWT");

        assertThat(jwt.getClaims())
                .containsEntry(JwtClaimNames.ISS, ISSUER)
                .containsEntry(JwtClaimNames.SUB, "customer@example.com")
                .containsEntry(JwtClaimNames.IAT, fixedInstant)
                .containsEntry(JwtClaimNames.EXP, (fixedInstant.plus(ACCESS_TOKEN_TTL)))
                .containsKey(JwtClaimNames.JTI); // 1. Asserts the key exists in the claims map

        assertThat(jwt.getClaimAsString(JwtClaimNames.JTI)).isNotBlank();
    }

    @Test
    void generateAccessTokenRejectsNullAuthentication() {
        Authentication authentication = null;

        assertThatThrownBy(() -> tokenService.generateAccessToken(authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authentication is not authenticated.");
    }

    @Test
    void generateAccessTokenRejectsUnauthenticatedCredentials() {
        Authentication authentication = new UsernamePasswordAuthenticationToken("customer@example.com", "raw-password");

        assertThatThrownBy(() -> tokenService.generateAccessToken(authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authentication is not authenticated.");
    }
}
