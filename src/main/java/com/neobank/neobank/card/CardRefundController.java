package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardRefundResponse;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class CardRefundController {

    private final CardRefundService cardRefundService;

    @PostMapping("/{merchantAccountNumber}/card-refunds/{purchaseReference}")
    public ResponseEntity<CardRefundResponse> refundCardPurchase(
            @PathVariable
            @Pattern(regexp = "\\d{14}", message = "Account number must be exactly 14 digits")
            String merchantAccountNumber,

            @PathVariable
            @Pattern(regexp = "[a-f0-9]{32}", message = "Purchase reference must be 32 hexadecimal characters")
            String purchaseReference,

            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cardRefundService.refundPurchase(
                        merchantAccountNumber,
                        purchaseReference,
                        idempotencyKey,
                        jwt.getSubject()
                ));
    }
}
