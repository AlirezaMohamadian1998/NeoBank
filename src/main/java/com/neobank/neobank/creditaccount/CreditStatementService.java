package com.neobank.neobank.creditaccount;

import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.LedgerEntryRepository;
import com.neobank.neobank.transaction.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CreditStatementService {

    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private final CreditAccountRepository creditAccountRepository;

    private final CreditStatementRepository creditStatementRepository;

    private final LedgerEntryRepository ledgerEntryRepository;

    private final BankTransactionRepository bankTransactionRepository;

    private final InternalAccountRepository internalAccountRepository;

    private final LedgerPostingService ledgerPostingService;

    private final ReferenceGenerator referenceGenerator;

    private final CreditAccountProperties creditAccountProperties;

    @Transactional
    @Retryable(
            delay = 200,
            maxDelay = 4000,
            maxRetries = 4,
            multiplier = 2,
            jitter = 50,
            includes = ConcurrencyFailureException.class
    )
    public void generateStatementsUpTo(Long creditAccountId, LocalDate today) {
        CreditAccount creditAccount = creditAccountRepository.findAndLockById(creditAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Credit account not found"));

        LedgerAccount cardLedger = creditAccount.getLedgerAccount();

        while(!creditAccount.getNextStatementDate().isAfter(today)) {
            List<CreditStatement> lastTwoStatements =
                    creditStatementRepository.findTop2ByCreditAccountOrderByStatementDateDesc(creditAccount);

            CreditStatement previousStatement = lastTwoStatements.isEmpty() ? null : lastTwoStatements.get(0);
            CreditStatement statementBeforePrevious = lastTwoStatements.size() < 2 ? null : lastTwoStatements.get(1);

            LocalDate statementDate = creditAccount.getNextStatementDate();

            LocalDate periodStart = previousStatement == null
                    ? LocalDate.ofInstant(creditAccount.getCreatedAt(), ZoneOffset.UTC)
                    : previousStatement.getStatementDate();

            BigDecimal openingBalance = previousStatement == null ? BigDecimal.ZERO : previousStatement.getClosingBalance();

            List<LedgerEntry> entries = ledgerEntryRepository.findNonInterestEntries(
                    cardLedger,
                    periodStart.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    statementDate.atStartOfDay(ZoneOffset.UTC).toInstant()
            );

            BigDecimal balance = openingBalance;
            BigDecimal dailyBalanceSum = BigDecimal.ZERO;
            LocalDate balanceSince = periodStart;

            for(LedgerEntry entry : entries) {
                LocalDate entryDay = LocalDate.ofInstant(entry.getCreatedAt(), ZoneOffset.UTC);

                dailyBalanceSum = dailyBalanceSum.add(
                        balance.max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(balanceSince, entryDay)))
                );

                balance = entry.getDirection() == EntryDirection.DEBIT
                        ? balance.add(entry.getAmount())
                        : balance.subtract(entry.getAmount());

                balanceSince = entryDay;
            }

            dailyBalanceSum = dailyBalanceSum.add(
                    balance.max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(balanceSince, statementDate)))
            );

            boolean gracePeriodAtCycleStart = isPaidInFull(statementBeforePrevious, cardLedger);
            boolean previousStatementPaidInFull = isPaidInFull(previousStatement, cardLedger);

            BigDecimal interest = gracePeriodAtCycleStart && previousStatementPaidInFull
                    ? BigDecimal.ZERO
                    : dailyBalanceSum.multiply(creditAccount.getAnnualInterestRate()).divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_UP);

            BankTransaction interestTransaction = null;

            if(interest.signum() > 0) {
                LedgerAccount interestIncomeLedger = internalAccountRepository
                        .findByPurposeAndCurrency(InternalAccountPurpose.INTEREST_INCOME, creditAccount.getCurrency())
                        .orElseThrow(() -> new IllegalStateException("Interest income account not found for " + creditAccount.getCurrency()))
                        .getLedgerAccount();

                interestTransaction = BankTransaction.createNew(
                        interest,
                        creditAccount.getCurrency(),
                        TransactionType.CREDIT_CARD_INTEREST,
                        referenceGenerator.generate(),
                        null
                );

                ledgerPostingService.post(interestTransaction, cardLedger, EntryDirection.DEBIT, interest);
                ledgerPostingService.post(interestTransaction, interestIncomeLedger, EntryDirection.CREDIT, interest);

                interestTransaction.complete();
                bankTransactionRepository.save(interestTransaction);
            }

            BigDecimal closingBalance = balance.add(interest);

            BigDecimal minimumPayment = closingBalance.signum() <= 0
                    ? BigDecimal.ZERO
                    : closingBalance
                            .multiply(creditAccountProperties.minimumPaymentPercent())
                            .setScale(2, RoundingMode.HALF_UP)
                            .max(creditAccountProperties.getMinimumPaymentFloor(creditAccount.getCurrency()))
                            .min(closingBalance);

            long days = ChronoUnit.DAYS.between(periodStart, statementDate);

            creditStatementRepository.save(CreditStatement.createNew(
                    referenceGenerator.generate(),
                    creditAccount,
                    periodStart,
                    statementDate,
                    statementDate.plusDays(creditAccountProperties.paymentDueDays()),
                    openingBalance,
                    closingBalance,
                    dailyBalanceSum.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP),
                    interest,
                    minimumPayment,
                    interestTransaction
            ));

            creditAccount.advanceStatementDate();
        }
    }

    private boolean isPaidInFull(CreditStatement statement, LedgerAccount cardLedger) {
        if(statement == null || statement.getClosingBalance().signum() <= 0) {
            return true;
        }

        BigDecimal repaidUntilDueDate = ledgerEntryRepository.sumRepaymentsAndRefunds(
                cardLedger,
                statement.getStatementDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                statement.getDueDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        );

        return repaidUntilDueDate.compareTo(statement.getClosingBalance()) >= 0;
    }
}
