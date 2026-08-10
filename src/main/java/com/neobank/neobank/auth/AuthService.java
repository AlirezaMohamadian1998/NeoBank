package com.neobank.neobank.auth;

import com.neobank.neobank.auth.dto.LoginRequest;
import com.neobank.neobank.auth.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;

    private final TokenService tokenService;

    private final JwtProperties jwtProperties;

    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim();
        String password = request.password();

        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(email, password)
                );

        String jwtToken = tokenService.generateAccessToken(authentication);

        return new LoginResponse(jwtToken, "Bearer", jwtProperties.accessTokenTtl().toSeconds());
    }
}
