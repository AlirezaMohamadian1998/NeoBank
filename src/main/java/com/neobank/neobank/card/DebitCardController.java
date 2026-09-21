package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/debit-cards")
@RequiredArgsConstructor
public class DebitCardController {

    private final DebitCardService debitCardService;

    @PostMapping("/{accountNumber}")
    public ResponseEntity<DebitCardIssueResponse> issueDebitCard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable
            @Pattern(regexp = "\\d{14}", message = "Account number must be exactly 14 digits")
            String accountNumber
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(debitCardService.issueDebitCard(jwt.getSubject(), accountNumber, idempotencyKey));
    }
}
