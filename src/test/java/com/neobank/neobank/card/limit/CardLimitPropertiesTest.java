package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.CardSpendingLimits;
import com.neobank.neobank.card.limit.CardLimitProperties.CurrencyLimits;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardLimitPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsLimitsForEveryCurrency() {
        contextRunner
                .withPropertyValues(validLimitsForAllCurrencies())
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    CardLimitProperties properties = context.getBean(CardLimitProperties.class);
                    CardSpendingLimits tryDefaults = properties.getDefaultLimits(CurrencyCode.TRY);
                    CardSpendingLimits tryMaximums = properties.getMaximumLimits(CurrencyCode.TRY);

                    assertThat(tryDefaults.getPerTransactionLimit()).isEqualByComparingTo("100.00");
                    assertThat(tryDefaults.getDailyLimit()).isEqualByComparingTo("250.00");
                    assertThat(tryMaximums.getPerTransactionLimit()).isEqualByComparingTo("1000.00");
                    assertThat(tryMaximums.getDailyLimit()).isEqualByComparingTo("2500.00");
                });
    }

    @Test
    void failsWhenACurrencyIsMissing() {
        List<String> properties = new ArrayList<>(List.of(validLimitsForAllCurrencies()));
        properties.removeIf(property -> property.startsWith("card.limits.currencies.GBP."));

        contextRunner
                .withPropertyValues(properties.toArray(String[]::new))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("every currency"));
    }

    @Test
    void failsWhenTheSectionIsMissing() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsWhenDefaultDailyExceedsMaxDaily() {
        assertFailsWith("card.limits.currencies.USD.default-daily=3000.00", "default daily limit");
    }

    @Test
    void failsWhenDefaultPerTransactionExceedsMaxPerTransaction() {
        assertFailsWith("card.limits.currencies.USD.default-per-transaction=1500.00", "default per-transaction limit");
    }

    @Test
    void failsWhenDefaultPerTransactionExceedsDefaultDaily() {
        assertFailsWith("card.limits.currencies.USD.default-per-transaction=300.00", "default daily limit");
    }

    @Test
    void failsWhenMaxPerTransactionExceedsMaxDaily() {
        assertFailsWith("card.limits.currencies.USD.max-per-transaction=3000.00", "maximum daily limit");
    }

    @Test
    void failsWhenALimitIsNotPositive() {
        assertFailsWith("card.limits.currencies.USD.default-per-transaction=-1.00", "defaultPerTransaction");
    }

    @Test
    void failsWhenALimitHasMoreThanTwoDecimals() {
        assertFailsWith("card.limits.currencies.USD.default-per-transaction=10.001", "defaultPerTransaction");
    }

    @Test
    void getDefaultLimitsForAnUnconfiguredCurrencyThrows() {
        CardLimitProperties properties = new CardLimitProperties(java.util.Map.of(
                CurrencyCode.USD,
                new CurrencyLimits(
                        new BigDecimal("100.00"),
                        new BigDecimal("250.00"),
                        new BigDecimal("1000.00"),
                        new BigDecimal("2500.00")
                )
        ));

        assertThatThrownBy(() -> properties.getDefaultLimits(CurrencyCode.EUR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EUR");
    }

    private void assertFailsWith(String override, String expectedMessagePart) {
        contextRunner
                .withPropertyValues(validLimitsForAllCurrencies())
                .withPropertyValues(override)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining(expectedMessagePart));
    }

    private static String[] validLimitsForAllCurrencies() {
        List<String> properties = new ArrayList<>();

        for (CurrencyCode currency : CurrencyCode.values()) {
            String prefix = "card.limits.currencies." + currency + ".";
            properties.add(prefix + "default-per-transaction=100.00");
            properties.add(prefix + "default-daily=250.00");
            properties.add(prefix + "max-per-transaction=1000.00");
            properties.add(prefix + "max-daily=2500.00");
        }

        return properties.toArray(String[]::new);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CardLimitProperties.class)
    static class PropertiesConfiguration {
    }
}
