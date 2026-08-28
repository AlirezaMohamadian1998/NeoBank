package com.neobank.neobank.fx;

import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fx/rate-locks")
public class FxRateController {

    private final FxRateService fxRateService;

    @PostMapping
    public ResponseEntity<FxRateLockResponse> createRateLock(
            @RequestParam(name = "base") CurrencyCode baseCurrency,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.OK).body(fxRateService.createRateLock(baseCurrency, jwt.getSubject()));
    }
}
