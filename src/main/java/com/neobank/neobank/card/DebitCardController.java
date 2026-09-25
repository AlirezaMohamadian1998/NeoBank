package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.dto.DebitCardRetrieveResponse;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
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

    @GetMapping("/{cardReference}")
    public ResponseEntity<DebitCardRetrieveResponse> getDebitCard(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(
                debitCardService.getDebitCard(cardReference, jwt.getSubject())
        );
    }

    @GetMapping
    public ResponseEntity<PagedModel<DebitCardRetrieveResponse>> getDebitCards(
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable
    ) {

        Page<DebitCardRetrieveResponse> page = debitCardService.getDebitCards(jwt.getSubject(), pageable);

        return ResponseEntity.ok(new PagedModel<>(page));
    }
}
