package com.neobank.neobank.auth;

import com.neobank.neobank.auth.dto.LoginRequest;
import com.neobank.neobank.auth.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    AuthenticationManager authenticationManager;

    @Mock
    TokenService tokenService;

    @Mock
    JwtProperties jwtProperties;

    @InjectMocks
    AuthService authService;

    @Captor
    ArgumentCaptor<Authentication> authenticationCaptor;

    @Test
    void loginAuthenticatesTrimmedEmailAndReturnsBearerToken() {
        LoginRequest request = new LoginRequest(" customer@example.com  ", "  raw-password  ");

        Authentication authentication = new UsernamePasswordAuthenticationToken(request.email(), null, new ArrayList<>());
        given(authenticationManager.authenticate(any(Authentication.class)))
                .willReturn(authentication);

        given(tokenService.generateAccessToken(authentication))
                .willReturn("signed-jwt");

        given(jwtProperties.accessTokenTtl())
                .willReturn(Duration.ofMinutes(15));

        LoginResponse response = authService.login(request);

        assertThat(response.accessToken())
                .isEqualTo("signed-jwt");

        assertThat(response.tokenType())
                .isEqualTo("Bearer");

        assertThat(response.expiresIn())
                .isEqualTo(900);

        verify(authenticationManager).authenticate(authenticationCaptor.capture());

        Authentication capturedAuthentication = authenticationCaptor.getValue();

        assertThat(capturedAuthentication.getPrincipal())
                .isEqualTo("customer@example.com");

        assertThat(capturedAuthentication.getCredentials())
                .isEqualTo("  raw-password  ");

        assertThat(capturedAuthentication.isAuthenticated())
                .isFalse();

        verify(tokenService).generateAccessToken(same(authentication));
    }

    @Test
    void loginDoesNotGenerateTokenWhenCredentialsAreInvalid() {
        LoginRequest request = new LoginRequest(" customer@example.com  ", "  raw-password  ");

        given(authenticationManager.authenticate(any(Authentication.class)))
                .willThrow(BadCredentialsException.class);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(tokenService);
        verifyNoInteractions(jwtProperties);
    }
}
