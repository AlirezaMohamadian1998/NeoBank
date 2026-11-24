package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardPayoutRequest;
import com.neobank.neobank.card.dto.CreditCardPayoutResponse;
import com.neobank.neobank.card.dto.CreditCardRepaymentRequest;
import com.neobank.neobank.card.dto.CreditCardRepaymentResponse;
import com.neobank.neobank.card.dto.CreditCardRetrieveResponse;
import com.neobank.neobank.card.dto.CreditCardTransactionFilter;
import com.neobank.neobank.card.dto.CreditStatementResponse;
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
@RequestMapping("/api/credit-cards")
@RequiredArgsConstructor
public class CreditCardController {

    private final CreditCardService creditCardService;

    private final CreditCardRepaymentService creditCardRepaymentService;

    private final CreditCardPayoutService creditCardPayoutService;

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

    @PostMapping("/{cardReference}/repayments")
    public ResponseEntity<CreditCardRepaymentResponse> repay(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid CreditCardRepaymentRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(creditCardRepaymentService.repay(cardReference, request, idempotencyKey, jwt.getSubject()));
    }

    @PostMapping("/{cardReference}/payouts")
    public ResponseEntity<CreditCardPayoutResponse> payOut(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid CreditCardPayoutRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(creditCardPayoutService.payOut(cardReference, request, idempotencyKey, jwt.getSubject()));
    }

    @GetMapping("/{cardReference}/transactions")
    public ResponseEntity<PagedModel<TransactionHistoryItem>> getTransactions(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @Valid @ModelAttribute CreditCardTransactionFilter filter,
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(new PagedModel<>(
                creditCardService.getTransactions(cardReference, filter, jwt.getSubject(), pageable)));
    }

    @GetMapping("/{cardReference}/statements")
    public ResponseEntity<PagedModel<CreditStatementResponse>> getStatements(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable
    ) {
        return ResponseEntity.ok(new PagedModel<>(creditCardService.getStatements(cardReference, jwt.getSubject(), pageable)));
    }

    @GetMapping("/{cardReference}/statements/{statementReference}")
    public ResponseEntity<CreditStatementResponse> getStatement(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Statement reference must be 32 hexadecimal characters")
            String statementReference,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(creditCardService.getStatement(cardReference, statementReference, jwt.getSubject()));
    }

    @GetMapping("/{cardReference}/statements/{statementReference}/lines")
    public ResponseEntity<PagedModel<TransactionHistoryItem>> getStatementLines(
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Card reference must be 32 hexadecimal characters")
            String cardReference,
            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Statement reference must be 32 hexadecimal characters")
            String statementReference,
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return ResponseEntity.ok(new PagedModel<>(
                creditCardService.getStatementLines(cardReference, statementReference, jwt.getSubject(), pageable)));
    }
}
