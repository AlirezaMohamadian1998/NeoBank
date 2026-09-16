package com.neobank.neobank.transaction.history;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionHistoryMapperTest {

    @ParameterizedTest
    @MethodSource("typeAndDirectionVaried")
    void hidesRequestedFieldsOnlyForIncomingTransfers(
            TransactionType type,
            EntryDirection direction,
            boolean shouldHideRequestedFields
    ) {

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                type,
                "22222222222222222222222222222222",
                null
        );

        LedgerEntry entry = transaction.addEntry(
                "33333333333333333333333333333333",
                new BigDecimal("4000.00"),
                new BigDecimal("5000.00"),
                direction,
                ledgerAccount
        );

        ReflectionTestUtils.setField(entry, "createdAt", Instant.parse("2026-09-16T12:00:00Z"));

        TransactionHistoryResponse result =
                TransactionHistoryMapper.toResponse(entry, "12345678901234");

        if(shouldHideRequestedFields) {
            assertThat(result.requestedAmount())
                    .isNull();

            assertThat(result.requestedCurrency())
                    .isNull();
        } else {
            assertThat(result.requestedAmount())
                    .isEqualByComparingTo(transaction.getRequestedAmount());

            assertThat(result.requestedCurrency())
                    .isEqualTo(transaction.getRequestedCurrency());
        }

        assertThat(result.accountNumber())
                .isEqualTo("12345678901234");

        assertThat(result.entryReference())
                .isEqualTo(entry.getReference());

        assertThat(result.transactionType())
                .isSameAs(type);

        assertThat(result.direction())
                .isSameAs(direction);

        assertThat(result.appliedAmount())
                .isEqualByComparingTo(entry.getAmount());

        assertThat(result.accountCurrency())
                .isSameAs(ledgerAccount.getCurrency());

        assertThat(result.balanceAfter())
                .isEqualByComparingTo(entry.getBalanceAfter());

        assertThat(result.createdAt())
                .isEqualTo(entry.getCreatedAt());
    }

    static Stream<Arguments> typeAndDirectionVaried() {
        return Stream.of(
                Arguments.of(TransactionType.DEPOSIT, EntryDirection.CREDIT, false),

                Arguments.of(TransactionType.TRANSFER, EntryDirection.CREDIT, true),

                Arguments.of(TransactionType.TRANSFER, EntryDirection.DEBIT, false),

                Arguments.of(TransactionType.WITHDRAWAL, EntryDirection.DEBIT, false)
        );
    }
}
