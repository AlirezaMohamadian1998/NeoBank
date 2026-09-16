package com.neobank.neobank.transaction.history.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionHistoryFilterTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @MethodSource("invalidHistoryFilters")
    void rejectsInvalidFilters(TransactionHistoryFilter filter, String invalidProperty) {
        assertThat(validator.validate(filter))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly(invalidProperty);
    }

    static Stream<Arguments> invalidHistoryFilters() {
        Instant futureDate = Instant.now().plusSeconds(86_400);

        return Stream.of(
                Arguments.of(new TransactionHistoryFilter(
                        "123", null, null, null, null, null, null
                ), "accountNumber"),

                Arguments.of(new TransactionHistoryFilter(
                        null, null, null, null, null, BigDecimal.ZERO, null
                ), "appliedMin"),

                Arguments.of(new TransactionHistoryFilter(
                        null, null, null, null, null, null, new BigDecimal("-100")
                ), "appliedMax"),

                Arguments.of(new TransactionHistoryFilter(
                        null, null, null, null, null, new BigDecimal("500"), new BigDecimal("100")
                ), "amountRangeValid"),

                Arguments.of(new TransactionHistoryFilter(
                        null, null, null,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-08-01T00:00:00Z"),
                        null, null
                ), "dateRangeValid"),

                Arguments.of(new TransactionHistoryFilter(
                        null, null, null, futureDate, null, null, null
                ), "dateFrom"),

                Arguments.of(new TransactionHistoryFilter(
                        null, null, null, null, futureDate, null, null
                ), "dateTo")
        );
    }
}
