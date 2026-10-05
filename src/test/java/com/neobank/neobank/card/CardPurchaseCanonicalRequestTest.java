package com.neobank.neobank.card;

import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CardPurchaseCanonicalRequestTest {
    @Test
    void canonicalRequestNormalizesAmount() {
        CardPurchaseCanonicalRequest canonicalRequest = new CardPurchaseCanonicalRequest(
                TransactionType.DEBIT_CARD_PURCHASE,
                "1".repeat(32),
                "12".repeat(7),
                "Test merchant",
                new BigDecimal("100.1000"),
                CurrencyCode.EUR,
                null
        );

        assertThat(canonicalRequest.amount())
                .isEqualTo(new BigDecimal("100.10"));
    }

    @ParameterizedTest
    @MethodSource("notesToNormalize")
    void canonicalRequestNormalizesNote(String note, String expectedNote) {
        CardPurchaseCanonicalRequest canonicalRequest = new CardPurchaseCanonicalRequest(
                TransactionType.DEBIT_CARD_PURCHASE,
                "1".repeat(32),
                "12".repeat(7),
                "Test merchant",
                new BigDecimal("100.1000"),
                CurrencyCode.EUR,
                note
        );

        assertThat(canonicalRequest.note())
                .isEqualTo(expectedNote);
    }

    @Test
    void serializationDistinguishesDelimiterContainingRequests() {
        JsonMapper objectMapper = JsonMapper.builder().build();
        RequestHasher requestHasher = new RequestHasher();

        CardPurchaseCanonicalRequest firstRequest = new CardPurchaseCanonicalRequest(
                TransactionType.DEBIT_CARD_PURCHASE,
                "1".repeat(32),
                "12".repeat(7),
                "Shop",
                new BigDecimal("10.00"),
                CurrencyCode.USD,
                "memo|20.00|USD|end"
        );

        CardPurchaseCanonicalRequest secondRequest = new CardPurchaseCanonicalRequest(
                TransactionType.DEBIT_CARD_PURCHASE,
                "1".repeat(32),
                "12".repeat(7),
                "Shop|10.00|USD|memo",
                new BigDecimal("20.00"),
                CurrencyCode.USD,
                "end"
        );

        String firstJson = objectMapper.writeValueAsString(firstRequest);
        String secondJson = objectMapper.writeValueAsString(secondRequest);

        assertThat(firstJson)
                .isNotEqualTo(secondJson);

        assertThat(requestHasher.hashRequest(firstJson))
                .isNotEqualTo(requestHasher.hashRequest(secondJson));
    }

    static Stream<Arguments> notesToNormalize() {
        return Stream.of(
                Arguments.of(null, ""),
                Arguments.of("", ""),
                Arguments.of(" ", ""),
                Arguments.of("\t", ""),
                Arguments.of("  purchase note  ", "purchase note"),
                Arguments.of("\t purchase note \n", "purchase note"),
                Arguments.of("  purchase  note  ", "purchase  note")
        );
    }
}
