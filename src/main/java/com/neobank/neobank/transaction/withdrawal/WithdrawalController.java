package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/accounts")
public class WithdrawalController {
    private final WithdrawalService withdrawalService;

    @PostMapping("/{accountNumber}/withdrawals")
    public ResponseEntity<WithdrawalResponse> withdraw(
            @PathVariable String accountNumber,
            @RequestBody @Valid WithdrawalRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(withdrawalService.withdraw(request, accountNumber, jwt.getSubject()));
    }
}
