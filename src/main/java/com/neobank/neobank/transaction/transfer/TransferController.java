package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
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
public class TransferController {

    private final TransferService transferService;

    @PostMapping("/{sourceAccountNumber}/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @PathVariable String sourceAccountNumber,
            @RequestBody @Valid TransferRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transferService.transfer(request, sourceAccountNumber, jwt.getSubject()));
    }
}
