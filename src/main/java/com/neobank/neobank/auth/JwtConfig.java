package com.neobank.neobank.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
@RequiredArgsConstructor
public class JwtConfig {

    private final JwtProperties jwtProperties;

    @Bean
    public SecretKey jwtSecretKey() {

        byte[] decodedKeyBytes;
        try {
            decodedKeyBytes = Base64.getDecoder().decode(jwtProperties.secret());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Application failed to start: The configured JWT secret is not a valid Base64 string.", e);
        }

        if (decodedKeyBytes.length < 32) {
            throw new IllegalStateException("Application failed to start: The configured JWT secret must decode to at least 32 bytes for HMAC-SHA256.");
        }

        return new SecretKeySpec(decodedKeyBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder
                .withSecretKey(jwtSecretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {

        NimbusJwtDecoder decoder =  NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));
        return decoder;

    }
}
