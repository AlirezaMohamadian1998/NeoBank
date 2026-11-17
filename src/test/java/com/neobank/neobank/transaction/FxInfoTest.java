package com.neobank.neobank.transaction;

import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxInfoTest {

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void blankLockIdIsRejected(String lockId) {
        Set<FxRate> rates = Set.of(
                FxRate.createNew(
                        CurrencyContext.REQUEST,
                        CurrencyCode.USD,
                        BigDecimal.ONE
                ),
                FxRate.createNew(
                        CurrencyContext.SOURCE,
                        CurrencyCode.USD,
                        BigDecimal.ONE
                ),
                FxRate.createNew(
                        CurrencyContext.DESTINATION,
                        CurrencyCode.TRY,
                        new BigDecimal("40.00")
                )
        );

        assertThatThrownBy(() -> FxInfo.createNew(lockId, rates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Lock ID cannot be blank.");
    }
}
