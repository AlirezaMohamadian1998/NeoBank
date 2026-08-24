package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final BankTransactionRepository bankTransactionRepository;

    private final AccountRepository accountRepository;

    private final ReferenceGenerator referenceGenerator;

    private final LedgerPostingService ledgerPostingService;

    private final IdempotencyService idempotencyService;

    private final RequestHasher requestHasher;

    private final InternalAccountRepository internalAccountRepository;

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
    public WithdrawalResponse withdraw(WithdrawalRequest request, String accountNumber, String customerEmail, String idempotencyKey) {
        if (!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Invalid idempotency key");
        }

        var account = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customerEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        String normalizedNote = "";
        if (request.note() != null) {
            normalizedNote = request.note().trim();
        }

        String canonicalRequest = String.join(
                "|",
                TransactionType.WITHDRAWAL.name(),
                accountNumber,
                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                normalizedNote
        );

        String requestHash = requestHasher.hashRequest(canonicalRequest);

        var existingIdempotencyRecord = idempotencyService.findAndValidateRecord(idempotencyKey, customerEmail, requestHash);

        if (existingIdempotencyRecord.isPresent()) {
            var existingTransaction = existingIdempotencyRecord.get().getBankTransaction();

            var existingEntry = existingTransaction
                    .getEntries()
                    .stream()
                    .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                    .filter(entry ->
                            entry.getLedgerAccount().getId()
                                    .equals(account.getLedgerAccount().getId()))
                    .findFirst().
                    orElseThrow();

            return WithdrawalMapper.toResponse(existingTransaction, existingEntry, accountNumber);
        }

        var ledgerAccount = account.getLedgerAccount();

        var settlementAccount = internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, account.getCurrency())
                .orElseThrow(() -> new IllegalStateException("Settlement account is not configured for " + account.getCurrency()));

        var bankTransaction = BankTransaction.createNew(
                request.amount(),
                account.getCurrency(),
                TransactionType.WITHDRAWAL,
                referenceGenerator.generate(),
                request.note()
        );

        var ledgerEntry = ledgerPostingService.post(
                bankTransaction,
                ledgerAccount,
                EntryDirection.DEBIT,
                request.amount()
        );

        ledgerPostingService.post(
                bankTransaction,
                settlementAccount.getLedgerAccount(),
                EntryDirection.CREDIT,
                request.amount()
        );

        bankTransaction.complete();
        var savedTransaction = bankTransactionRepository.save(bankTransaction);

        idempotencyService.save(IdempotencyRecord.createNew(
                        idempotencyKey,
                        requestHash,
                        account.getCustomer(),
                        savedTransaction
                )
        );

        return WithdrawalMapper.toResponse(savedTransaction, ledgerEntry, accountNumber);
    }
}
