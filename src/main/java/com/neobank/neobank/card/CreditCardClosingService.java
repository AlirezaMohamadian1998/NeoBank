package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.CreditCardClosingRequest;
import com.neobank.neobank.card.dto.CreditCardClosingResponse;
import com.neobank.neobank.card.dto.PayoffQuoteResponse;
import com.neobank.neobank.card.exception.CreditCardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCreditCardClosingException;
import com.neobank.neobank.card.exception.PayoffQuoteExpiredException;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditAccountRepository;
import com.neobank.neobank.creditaccount.CreditStatement;
import com.neobank.neobank.creditaccount.CreditStatementRepository;
import com.neobank.neobank.creditaccount.CreditStatementService;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.ledger.LedgerAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditCardClosingService {

    private final CreditAccountRepository creditAccountRepository;

    private final CreditCardRepository creditCardRepository;

    private final CreditStatementRepository creditStatementRepository;

    private final AccountRepository accountRepository;

    private final CreditStatementService creditStatementService;

    private final CreditCardBalanceService creditCardBalanceService;

    private final PayoffQuoteCache payoffQuoteCache;

    private final IdempotencyService idempotencyService;

    private final RequestHasher requestHasher;

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

    @Transactional
    @Retryable(
            delay = 200,
            maxDelay = 4000,
            maxRetries = 4,
            multiplier = 2,
            jitter = 50,
            includes = {
                    IdempotencyKeyRaceException.class,
                    ConcurrencyFailureException.class
            }
    )
    public CreditCardClosingResponse close(
            String cardReference,
            CreditCardClosingRequest request,
            String idempotencyKey,
            String customerEmail
    ) {
        if(!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Idempotency key is invalid");
        }

        CreditAccount creditAccount =
                creditAccountRepository.findAndLockByCardReferenceAndCustomerEmail(cardReference, customerEmail)
                        .orElseThrow(() -> new CreditCardNotFoundException("Credit card not found"));

        CreditCard creditCard =
                creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(cardReference, customerEmail)
                        .orElseThrow(() -> new CreditCardNotFoundException("Credit card not found"));

        Account payoffAccount =
                accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(request.payoffAccountNumber(), customerEmail)
                        .orElseThrow(AccountNotFoundException::new);

        boolean isCrossCurrency = payoffAccount.getCurrency() != creditAccount.getCurrency();

        if(!isCrossCurrency && request.lockId() != null) {
            throw new InvalidCreditCardClosingException("Lock ID is not required for same currency closing");
        }

        if(isCrossCurrency && request.lockId() == null) {
            throw new FxRateLockUnavailableException("Lock ID is required for cross-currency closing");
        }

        String canonicalRequest = String.join(
                "|",
                "CREDIT_CARD_CLOSING",
                cardReference,
                request.quoteId(),
                request.payoffAccountNumber(),
                request.lockId() != null ? request.lockId() : ""
        );

        String requestHash = requestHasher.hashRequest(canonicalRequest);

        Optional<IdempotencyRecord> idempotencyRecord =
                idempotencyService.findAndValidateRecord(idempotencyKey, customerEmail, requestHash);

        if(idempotencyRecord.isPresent()) {
            CreditStatement closingStatement = creditStatementRepository
                    .findByStatementReferenceAndCreditAccount(idempotencyRecord.get().getResultReference(), creditAccount)
                    .orElseThrow(() -> new IdempotencyResultNotFoundException("Idempotency result not found"));

            return CreditCardMapper.toClosingResponse(creditCard, creditAccount.getPayoffAccount(), closingStatement);
        }

        if(payoffAccount.getAccountType() != AccountType.CURRENT) {
            throw new InvalidCreditCardClosingException("Credit cards can only be paid off and closed with a current account");
        }

        creditAccount.ensureOpen();

        LocalDate today = LocalDate.now(clock);

        creditStatementService.generateStatementsUpTo(creditAccount.getId(), today);
        creditAccountRepository.flush();

        LedgerAccount cardLedger = creditAccount.getLedgerAccount();

        PayoffQuote quote = payoffQuoteCache.get(customerEmail, cardReference, request.quoteId())
                .filter(cachedQuote -> Instant.now(clock).isBefore(cachedQuote.expiresAt()))
                .orElseThrow(() -> new PayoffQuoteExpiredException("Payoff quote not found or expired"));

        if(!quote.ledgerVersion().equals(cardLedger.getVersion())) {
            throw new PayoffQuoteExpiredException("The card's balance changed after the quote was made");
        }

        CreditStatement closingStatement = creditStatementService.writeClosingStatement(creditAccount, today);

        BigDecimal payoffAmount = cardLedger.getBalance();

        if(payoffAmount.compareTo(quote.payoffAmount()) != 0) {
            throw new IllegalStateException(
                    "Payoff amount " + payoffAmount.toPlainString() + " differs from quoted " + quote.payoffAmount().toPlainString()
            );
        }

        if(payoffAmount.signum() > 0) {
            creditCardBalanceService.postRepayment(creditAccount, payoffAccount, payoffAmount, request.lockId(), customerEmail);
        } else if(payoffAmount.signum() < 0) {
            creditCardBalanceService.postPayout(creditAccount, payoffAccount, payoffAmount.negate(), request.lockId(), customerEmail);
        }

        creditAccount.close(payoffAccount);

        if(creditCard.getStatus() != CardStatus.CLOSED) {
            creditCard.closeCard();
        }

        idempotencyService.save(
                IdempotencyRecord.createNew(
                        idempotencyKey,
                        requestHash,
                        payoffAccount.getCustomer(),
                        closingStatement.getStatementReference()
                )
        );

        return CreditCardMapper.toClosingResponse(creditCard, payoffAccount, closingStatement);
    }
}
