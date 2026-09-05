package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
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
public class TransferService {

    private final BankTransactionRepository bankTransactionRepository;

    private final ReferenceGenerator referenceGenerator;

    private final AccountRepository accountRepository;

    private final LedgerPostingService ledgerPostingService;

    private final IdempotencyService idempotencyService;

    private final RequestHasher requestHasher;

    private final FxRateService fxRateService;

    @Transactional
    @Retryable(
            maxRetries = 4,
            delay = 200,
            multiplier = 2,
            maxDelay = 4000,
            jitter = 50,
            includes = {
                    ConcurrencyFailureException.class,
                    IdempotencyKeyRaceException.class
            }
    )
    public TransferResponse transfer(TransferRequest request, String sourceAccountNumber, String senderEmail, String idempotencyKey) {
        if(!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Invalid idempotency key");
        }

        Account sourceAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, senderEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        if(sourceAccountNumber.equals(request.destinationAccountNumber())) {
            throw new InvalidTransferException("Source and destination accounts cannot be the same");
        }

        Account destinationAccount = accountRepository.findByAccountNumber(request.destinationAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found"));

        boolean isCrossCurrency = (sourceAccount.getCurrency() != destinationAccount.getCurrency()) || (sourceAccount.getCurrency() != request.currency());

        if(!isCrossCurrency && request.lockId() != null) {
            throw new InvalidTransferException("Lock ID is not required for same currency operation.");
        }

        if(isCrossCurrency && request.lockId() == null) {
            throw new FxRateLockUnavailableException("Lock ID is required for cross-currency operation.");
        }

        String normalizedNote = "";
        if(request.note() != null) {
            normalizedNote = request.note().trim();
        }

        String canonicalRequest = String.join(
                "|",
                TransactionType.TRANSFER.name(),
                sourceAccountNumber,
                destinationAccount.getAccountNumber(),
                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                request.currency().name(),
                sourceAccount.getCurrency().name(),
                destinationAccount.getCurrency().name(),
                request.lockId() != null ? request.lockId() : "",
                normalizedNote
        );

        String requestHash = requestHasher.hashRequest(canonicalRequest);

        var existingIdempotencyRecord = idempotencyService.findAndValidateRecord(idempotencyKey, senderEmail, requestHash);

        if(existingIdempotencyRecord.isPresent()) {
            var existingTransaction = existingIdempotencyRecord.get().getBankTransaction();

            var existingSourceEntry = existingTransaction
                    .getEntries()
                    .stream()
                    .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                    .filter(entry ->
                            entry.getLedgerAccount().getId()
                                    .equals(sourceAccount.getLedgerAccount().getId()))
                    .findFirst()
                    .orElseThrow();

            return TransferMapper.toResponse(existingTransaction, existingSourceEntry, sourceAccountNumber, destinationAccount.getAccountNumber());
        }

        BankTransaction bankTransaction = BankTransaction.createNew(
                request.amount(),
                request.currency(),
                TransactionType.TRANSFER,
                referenceGenerator.generate(),
                request.note()
        );

        FxInfo fxInfo = null;

        if(isCrossCurrency) {
            FxRateLockResponse fxRateLockResponse = fxRateService.getCachedRate(senderEmail, request.lockId());

            if(fxRateLockResponse.baseCurrency() != request.currency()) {
                throw new FxRateLockUnavailableException("The base currency in the cached fx rate must be the same as the requested currency");
            }

            fxInfo = FxInfo.createNew(
                    request.lockId(),
                    Set.of(FxRate.createNew(
                                    CurrencyContext.REQUEST,
                                    request.currency(),
                                    fxRateLockResponse.rates().get(request.currency())
                            ),
                            FxRate.createNew(
                                    CurrencyContext.SOURCE,
                                    sourceAccount.getCurrency(),
                                    fxRateLockResponse.rates().get(sourceAccount.getCurrency())
                            ),
                            FxRate.createNew(
                                    CurrencyContext.DESTINATION,
                                    destinationAccount.getCurrency(),
                                    fxRateLockResponse.rates().get(destinationAccount.getCurrency())
                            )
                    )
            );

            bankTransaction.addFxInfo(fxInfo);
        }

        var sourceLedgerEntry = ledgerPostingService.post(
                bankTransaction,
                sourceAccount.getLedgerAccount(),
                EntryDirection.DEBIT,
                request.amount()
                        .multiply(
                                fxInfo != null
                                        ? fxInfo.getRate(CurrencyContext.SOURCE)
                                        : BigDecimal.ONE)
                        .setScale(2, RoundingMode.HALF_EVEN)
        );

        ledgerPostingService.post(
                bankTransaction,
                destinationAccount.getLedgerAccount(),
                EntryDirection.CREDIT,
                request.amount()
                        .multiply(
                                fxInfo != null
                                        ? fxInfo.getRate(CurrencyContext.DESTINATION)
                                        : BigDecimal.ONE)
                        .setScale(2, RoundingMode.HALF_EVEN)
        );

        bankTransaction.complete();
        var savedTransaction = bankTransactionRepository.save(bankTransaction);

        idempotencyService.save(
                IdempotencyRecord.createNew(
                        idempotencyKey,
                        requestHash,
                        sourceAccount.getCustomer(),
                        savedTransaction
                )
        );

        return TransferMapper.toResponse(
                savedTransaction,
                sourceLedgerEntry,
                sourceAccount.getAccountNumber(),
                destinationAccount.getAccountNumber()
        );
    }
}
