package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.PayoffQuoteResponse;
import com.neobank.neobank.card.exception.CreditCardNotFoundException;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditAccountRepository;
import com.neobank.neobank.creditaccount.CreditStatementService;
import com.neobank.neobank.ledger.LedgerAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditCardClosingService {

    private final CreditAccountRepository creditAccountRepository;

    private final CreditStatementService creditStatementService;

    private final PayoffQuoteCache payoffQuoteCache;

    private final Clock clock;

    @Transactional
    @Retryable(
            delay = 200,
            maxDelay = 4000,
            maxRetries = 4,
            multiplier = 2,
            jitter = 50,
            includes = ConcurrencyFailureException.class
    )
    public PayoffQuoteResponse quotePayoff(String cardReference, String customerEmail) {
        CreditAccount creditAccount =
                creditAccountRepository.findAndLockByCardReferenceAndCustomerEmail(cardReference, customerEmail)
                        .orElseThrow(() -> new CreditCardNotFoundException("Credit card not found"));

        creditAccount.ensureOpen();

        LocalDate today = LocalDate.now(clock);

        creditStatementService.generateStatementsUpTo(creditAccount.getId(), today);
        creditAccountRepository.flush();

        LedgerAccount cardLedger = creditAccount.getLedgerAccount();

        BigDecimal accruedInterest = creditStatementService.calculatePayoffInterest(creditAccount, today);
        BigDecimal payoffAmount = cardLedger.getBalance().add(accruedInterest);

        PayoffQuote quote = new PayoffQuote(
                UUID.randomUUID().toString(),
                cardReference,
                payoffAmount,
                cardLedger.getVersion(),
                today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        );

        payoffQuoteCache.put(customerEmail, quote);

        return new PayoffQuoteResponse(
                quote.quoteId(),
                cardReference,
                creditAccount.getCurrency(),
                cardLedger.getBalance(),
                accruedInterest,
                payoffAmount,
                quote.expiresAt()
        );
    }
}
