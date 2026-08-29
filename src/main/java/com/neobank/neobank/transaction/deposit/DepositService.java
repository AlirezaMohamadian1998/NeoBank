package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;

    private final BankTransactionRepository bankTransactionRepository;

    private final ReferenceGenerator referenceGenerator;

    private final LedgerPostingService ledgerPostingService;

    private final RequestHasher requestHasher;

    private final IdempotencyService idempotencyService;

    private final InternalAccountRepository internalAccountRepository;

    private final FxRateService fxRateService;

    @Transactional
    @Retryable(maxRetries = 4,
            delay = 200,
            multiplier = 2,
            maxDelay = 4000,
            jitter = 50,
            includes = {
                    ConcurrencyFailureException.class,
                    IdempotencyKeyRaceException.class
            }
    )
    public DepositResponse deposit(
            DepositRequest request,
            String accountNumber,
            String customerEmail,
            String idempotencyKey
    ) {
        if(!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Invalid idempotency key");
        }

        Account account = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customerEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        if(account.getCurrency() != request.requestedCurrency() && request.lockId() == null) {
            throw new FxRateLockUnavailableException("Lock ID is required for cross-currency operation.");
        }

        if(account.getCurrency() == request.requestedCurrency() && request.lockId() != null) {
            throw new IllegalArgumentException("Lock ID is not required for same currency operation.");
        }

        String normalizedNote = "";
        if(request.note() != null) {
            normalizedNote = request.note().trim();
        }

        String canonicalRequest = String.join(
                "|",
                TransactionType.DEPOSIT.name(),
                accountNumber,
                request.amount()
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString(),
                request.requestedCurrency().name(),
                account.getCurrency().name(),
                request.lockId() != null ? request.lockId() : "",
                normalizedNote
        );

        String requestHash = requestHasher.hashRequest(canonicalRequest);

        var existingIdempotencyRecord = idempotencyService.findAndValidateRecord(
                idempotencyKey,
                customerEmail,
                requestHash
        );

        if(existingIdempotencyRecord.isPresent()) {
            var existingTransaction = existingIdempotencyRecord.get().getBankTransaction();

            var existingEntry = existingTransaction
                    .getEntries()
                    .stream()
                    .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                    .filter(entry ->
                            entry.getLedgerAccount().getId()
                                    .equals(account.getLedgerAccount().getId())
                    )
                    .findFirst().
                    orElseThrow();

            return DepositMapper.toResponse(
                    existingTransaction,
                    existingEntry,
                    accountNumber
            );
        }

        InternalAccount settlementAccount = internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, request.requestedCurrency())
                .orElseThrow(() -> new IllegalStateException("Settlement account is not configured for " + request.requestedCurrency()));

        BankTransaction bankTransaction = BankTransaction.createNew(
                request.amount(),
                request.requestedCurrency(),
                TransactionType.DEPOSIT,
                referenceGenerator.generate(),
                request.note()
        );

        FxInfo fxInfo = null;

        if(account.getCurrency() != request.requestedCurrency() && request.lockId() != null) {
            FxRateLockResponse cachedRate = fxRateService.getCachedRate(customerEmail, request.lockId());

            if(cachedRate.baseCurrency() != request.requestedCurrency()) {
                throw new FxRateLockUnavailableException("The base currency in the cached fx rate must be the same as the requested currency");
            }

            FxRate requestFxRate = FxRate.createNew(
                    CurrencyContext.REQUEST,
                    request.requestedCurrency(),
                    cachedRate.rates().get(request.requestedCurrency())
            );

            FxRate sourceFxRate = FxRate.createNew(
                    CurrencyContext.SOURCE,
                    settlementAccount.getCurrency(),
                    cachedRate.rates().get(settlementAccount.getCurrency())
            );

            FxRate destinationFxRate = FxRate.createNew(
                    CurrencyContext.DESTINATION,
                    account.getCurrency(),
                    cachedRate.rates().get(account.getCurrency())
            );

            fxInfo = FxInfo.createNew(
                    request.lockId(),
                    Set.of(
                            requestFxRate,
                            sourceFxRate,
                            destinationFxRate
                    )
            );

            bankTransaction.addFxInfo(fxInfo);
        }

        ledgerPostingService.post(
                bankTransaction,
                settlementAccount.getLedgerAccount(),
                EntryDirection.DEBIT,
                request.amount()
                        .multiply(fxInfo != null ? fxInfo.getRate(CurrencyContext.SOURCE) : BigDecimal.ONE)
                        .setScale(2, RoundingMode.HALF_EVEN)
        );

        var entry = ledgerPostingService.post(
                bankTransaction,
                account.getLedgerAccount(),
                EntryDirection.CREDIT,
                request.amount()
                        .multiply(fxInfo != null ? fxInfo.getRate(CurrencyContext.DESTINATION) : BigDecimal.ONE)
                        .setScale(2, RoundingMode.HALF_EVEN)
        );

        bankTransaction.complete();
        var savedTransaction = bankTransactionRepository.save(bankTransaction);

        idempotencyService.save(
                IdempotencyRecord.createNew(
                        idempotencyKey,
                        requestHash,
                        account.getCustomer(),
                        savedTransaction
                )
        );

        return DepositMapper.toResponse(savedTransaction, entry, accountNumber);
    }
}
