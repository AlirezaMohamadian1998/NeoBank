package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardRetrieveResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/credit-cards")
@RequiredArgsConstructor
public class CreditCardController {

    private final CreditCardService creditCardService;

    @PostMapping
    public ResponseEntity<CreditCardIssueResponse> issueCreditCard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid CreditCardIssueRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(creditCardService.issueCreditCard(jwt.getSubject(), request, idempotencyKey));
    }

    @GetMapping("/{cardReference}")
    public ResponseEntity<CreditCardRetrieveResponse> getCreditCard(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(creditCardService.getCreditCard(cardReference, jwt.getSubject()));
    }

    @GetMapping
    public ResponseEntity<PagedModel<CreditCardRetrieveResponse>> getCreditCards(
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable
    ) {
        return ResponseEntity.ok(new PagedModel<>(creditCardService.getCreditCards(jwt.getSubject(), pageable)));
    }

    @PatchMapping("/{cardReference}/status/{status}")
    public ResponseEntity<CreditCardRetrieveResponse> changeCardStatus(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @PathVariable CardStatus status,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(creditCardService.changeCardStatus(cardReference, jwt.getSubject(), status));
    }

    @PutMapping("/{cardReference}/spending-limits")
    public ResponseEntity<CreditCardRetrieveResponse> updateSpendingLimits(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @RequestBody @Valid CardSpendingLimitsUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(creditCardService.updateSpendingLimits(cardReference, jwt.getSubject(), request));
    }
}
