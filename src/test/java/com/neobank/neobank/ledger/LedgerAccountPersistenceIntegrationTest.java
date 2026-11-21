package com.neobank.neobank.ledger;

import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.TransactionIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class LedgerAccountPersistenceIntegrationTest extends TransactionIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void negativeBalanceIsPersistedWhenAllowed() {
        LedgerAccount ledgerAccount = LedgerAccount.createNewWithNegativeBalanceAllowed(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );
        ledgerAccount.credit(new BigDecimal("25.00"));

        LedgerAccount saved = ledgerAccountRepository.save(ledgerAccount);

        LedgerAccount reloaded = ledgerAccountRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.isNegativeBalanceAllowed()).isTrue();
        assertThat(reloaded.getBalance()).isEqualByComparingTo(new BigDecimal("-25.00"));
    }

    @Test
    void databaseRejectsNegativeBalanceWhenNotAllowed() {
        LedgerAccount saved = ledgerAccountRepository.save(LedgerAccount.createNew(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        ));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_accounts SET balance = -1.00 WHERE id = ?",
                saved.getId()
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_ledger_accounts_balance_non_negative");

        assertThat(ledgerAccountRepository.findById(saved.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }
}
