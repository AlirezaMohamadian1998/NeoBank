package com.neobank.neobank.creditaccount;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreditStatementJob {

    private final CreditAccountRepository creditAccountRepository;

    private final CreditStatementService creditStatementService;

    private final Clock clock;

    @Scheduled(cron = "${credit-statements.cron:0 15 0 * * *}", zone = "UTC")
    public void generateDueStatements() {
        LocalDate today = LocalDate.now(clock);

        for(Long creditAccountId : creditAccountRepository.findIdsWithStatementDueBy(today)) {
            try {
                creditStatementService.generateStatementsUpTo(creditAccountId, today);
            } catch(RuntimeException e) {
                log.error("Generating statements failed for credit account {}", creditAccountId, e);
            }
        }
    }
}
