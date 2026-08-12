package com.neobank.neobank.account;

import com.neobank.neobank.account.dto.AccountResponse;
import com.neobank.neobank.account.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @RequestBody @Valid CreateAccountRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request, jwt.getSubject()));
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getCurrentCustomerAccounts(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(accountService.getCurrentCustomerAccounts(jwt.getSubject()));
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getCurrentCustomerAccount(
            @PathVariable String accountNumber,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(accountService.getCurrentCustomerAccount(accountNumber, jwt.getSubject()));
    }
}
