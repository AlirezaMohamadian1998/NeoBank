package com.neobank.neobank.creditaccount;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CreditStatementJobTest {

    private final CreditAccountRepository creditAccountRepository = mock(CreditAccountRepository.class);

    private final CreditStatementService creditStatementService = mock(CreditStatementService.class);

    private final CreditStatementJob creditStatementJob = new CreditStatementJob(
            creditAccountRepository,
            creditStatementService,
            Clock.fixed(Instant.parse("2026-02-15T00:15:00Z"), ZoneOffset.UTC)
    );

    @Test
    void oneFailingAccountDoesNotStopTheOthers() {
        LocalDate today = LocalDate.of(2026, 2, 15);
        given(creditAccountRepository.findIdsWithStatementDueBy(today)).willReturn(List.of(1L, 2L));
        willThrow(new IllegalStateException("boom")).given(creditStatementService).generateStatementsUpTo(1L, today);

        creditStatementJob.generateDueStatements();

        verify(creditStatementService).generateStatementsUpTo(1L, today);
        verify(creditStatementService).generateStatementsUpTo(2L, today);
    }
}
