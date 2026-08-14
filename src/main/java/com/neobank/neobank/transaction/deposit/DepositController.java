package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class DepositController {

    private final DepositService depositService;

    @PostMapping("/{accountNumber}/deposits")
    public ResponseEntity<DepositResponse> deposit(
            @PathVariable String accountNumber,
            @RequestBody @Valid DepositRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(depositService.deposit(request, accountNumber, jwt.getSubject()));
    }
}
