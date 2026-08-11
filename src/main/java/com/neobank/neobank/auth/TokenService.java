package com.neobank.neobank.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtProperties jwtProperties;

    private final JwtEncoder jwtEncoder;

    private final Clock clock;

    public String generateAccessToken(Authentication authentication) {
        if (authentication == null || (!authentication.isAuthenticated())) {
            throw new IllegalArgumentException("Authentication is not authenticated.");
        }

        Instant iat = clock.instant();
        Instant exp = iat.plus(jwtProperties.accessTokenTtl());

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();

        JwtClaimsSet claims =
                JwtClaimsSet
                        .builder()
                        .issuer(jwtProperties.issuer())
                        .subject(authentication.getName())
                        .issuedAt(iat)
                        .expiresAt(exp)
                        .id(UUID.randomUUID().toString())
                        .build();

        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));

        return jwt.getTokenValue();
    }
}
