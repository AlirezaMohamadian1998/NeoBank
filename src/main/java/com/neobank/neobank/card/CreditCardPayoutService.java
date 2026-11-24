package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.CreditCardPayoutRequest;
import com.neobank.neobank.card.dto.CreditCardPayoutResponse;
import com.neobank.neobank.card.exception.CreditCardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCreditCardPayoutException;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.NothingToPayOutException;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CreditCardPayoutService {

    private final CreditCardRepository creditCardRepository;

    private final AccountRepository accountRepository;

    private final BankTransactionRepository bankTransactionRepository;

    private final LedgerPostingService ledgerPostingService;

    private final IdempotencyService idempotencyService;

    private final RequestHasher requestHasher;

    private final ReferenceGenerator referenceGenerator;

    private final FxRateService fxRateService;

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
    public CreditCardPayoutResponse payOut(
            String cardReference,
            CreditCardPayoutRequest request,
            String idempotencyKey,
            String customerEmail
    ) {
        if(!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Idempotency key is invalid");
        }

        CreditCard creditCard =
                creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(cardReference, customerEmail)
                        .orElseThrow(() -> new CreditCardNotFoundException("Credit card not found"));

        Account destinationAccount =
                accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(request.destinationAccountNumber(), customerEmail)
                        .orElseThrow(AccountNotFoundException::new);

        CreditAccount creditAccount = creditCard.getCreditAccount();

        boolean isCrossCurrency = destinationAccount.getCurrency() != creditAccount.getCurrency();

        if(!isCrossCurrency && request.lockId() != null) {
            throw new InvalidCreditCardPayoutException("Lock ID is not required for same currency payout");
        }

        if(isCrossCurrency && request.lockId() == null) {
            throw new FxRateLockUnavailableException("Lock ID is required for cross-currency payout");
        }

        String canonicalRequest = String.join(
                "|",
                TransactionType.CREDIT_CARD_PAYOUT.name(),
                cardReference,
                request.destinationAccountNumber(),
                request.lockId() != null ? request.lockId() : ""
        );

        String requestHash = requestHasher.hashRequest(canonicalRequest);

        Optional<IdempotencyRecord> idempotencyRecord =
                idempotencyService.findAndValidateRecord(idempotencyKey, customerEmail, requestHash);

        if(idempotencyRecord.isPresent()) {
            BankTransaction existingTransaction =
                    bankTransactionRepository.findByReference(idempotencyRecord.get().getResultReference())
                            .orElseThrow(() -> new IdempotencyResultNotFoundException("Idempotency result not found"));

            return CreditCardMapper.toPayoutResponse(
                    creditCard,
                    destinationAccount,
                    existingTransaction,
                    getEntry(existingTransaction, EntryDirection.DEBIT),
                    getEntry(existingTransaction, EntryDirection.CREDIT)
            );
        }

        if(destinationAccount.getAccountType() != AccountType.CURRENT) {
            throw new InvalidCreditCardPayoutException("Credit balances can only be paid out to a current account");
        }

        creditAccount.ensureOpen();

        BigDecimal amount = creditAccount.getLedgerAccount().getBalance().negate();

        if(amount.signum() <= 0) {
            throw new NothingToPayOutException("There is no credit balance to pay out");
        }

        BankTransaction bankTransaction = BankTransaction.createNew(
                amount,
                creditAccount.getCurrency(),
                TransactionType.CREDIT_CARD_PAYOUT,
                referenceGenerator.generate(),
                null
        );

        FxInfo fxInfo = null;

        if(isCrossCurrency) {
            FxRateLockResponse fxRateLock = fxRateService.getCachedRate(customerEmail, request.lockId());

            if(fxRateLock.baseCurrency() != creditAccount.getCurrency()) {
                throw new FxRateLockUnavailableException("The base currency in the cached fx rate must be the same as the credit card currency");
            }

            fxInfo = FxInfo.createNew(
                    request.lockId(),
                    Set.of(
                            FxRate.createNew(
                                    CurrencyContext.REQUEST,
                                    creditAccount.getCurrency(),
                                    BigDecimal.ONE
                            ),
                            FxRate.createNew(
                                    CurrencyContext.SOURCE,
                                    creditAccount.getCurrency(),
                                    BigDecimal.ONE
                            ),
                            FxRate.createNew(
                                    CurrencyContext.DESTINATION,
                                    destinationAccount.getCurrency(),
                                    fxRateLock.rates().get(destinationAccount.getCurrency())
                            )
                    )
            );

            bankTransaction.addFxInfo(fxInfo);
        }

        LedgerEntry cardEntry = ledgerPostingService.post(
                bankTransaction,
                creditAccount.getLedgerAccount(),
                EntryDirection.DEBIT,
                amount
        );

        LedgerEntry destinationEntry = ledgerPostingService.post(
                bankTransaction,
                destinationAccount.getLedgerAccount(),
                EntryDirection.CREDIT,
                amount.multiply(
                        fxInfo == null
                                ? BigDecimal.ONE
                                : fxInfo.getRate(CurrencyContext.DESTINATION)
                ).setScale(2, RoundingMode.HALF_EVEN)
        );

        bankTransaction.complete();
        BankTransaction savedTransaction = bankTransactionRepository.save(bankTransaction);

        idempotencyService.save(
                IdempotencyRecord.createNew(
                        idempotencyKey,
                        requestHash,
                        destinationAccount.getCustomer(),
                        savedTransaction.getReference()
                )
        );

        return CreditCardMapper.toPayoutResponse(creditCard, destinationAccount, savedTransaction, cardEntry, destinationEntry);
    }

    private static LedgerEntry getEntry(BankTransaction transaction, EntryDirection direction) {
        return transaction.getEntries().stream()
                .filter(entry -> entry.getDirection() == direction)
                .findFirst()
                .orElseThrow();
    }
}
