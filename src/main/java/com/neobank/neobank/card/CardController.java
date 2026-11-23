package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CardPurchaseResponse;
import com.neobank.neobank.card.dto.CardTransactionFilter;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardPaymentService cardPaymentService;

    private final CardTransactionService cardTransactionService;

    @PostMapping("/{cardReference}/payments")
    public ResponseEntity<CardPurchaseResponse> makePayment(
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            @PathVariable
            String cardReference,

            @RequestBody @Valid CardPurchaseRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cardPaymentService.makePurchase(cardReference, request, idempotencyKey, jwt.getSubject()));
    }

    @GetMapping("/{cardReference}/transactions")
    public ResponseEntity<PagedModel<TransactionHistoryItem>> getTransactions(
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            @PathVariable
            String cardReference,

            @Valid @ModelAttribute CardTransactionFilter filter,
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(new PagedModel<>(
                cardTransactionService.getTransactions(cardReference, filter, jwt.getSubject(), pageable)));
    }
}
