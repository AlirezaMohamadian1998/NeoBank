package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;

    private final BankTransactionRepository bankTransactionRepository;

    private final ReferenceGenerator referenceGenerator;

    private final LedgerPostingService ledgerPostingService;

    private final RequestHasher requestHasher;

    private final IdempotencyService idempotencyService;

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
        if (!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Invalid idempotency key");
        }

        Account account = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customerEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        String normalizedNote = "";
        if (request.note() != null) {
            normalizedNote = request.note().trim();
        }

        String canonicalRequest = String.join(
                "|",
                TransactionType.DEPOSIT.name(),
                accountNumber,
                request.amount()
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .toPlainString(),
                normalizedNote
        );

        String requestHash = requestHasher.hashRequest(canonicalRequest);

        var existingIdempotencyRecord = idempotencyService.findAndValidateRecord(
                idempotencyKey,
                customerEmail,
                requestHash
        );

        if (existingIdempotencyRecord.isPresent()) {
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

        BankTransaction bankTransaction = BankTransaction.createNew(
                request.amount(),
                account.getCurrency(),
                TransactionType.DEPOSIT,
                referenceGenerator.generate(),
                request.note()
        );

        var entry = ledgerPostingService.post(
                bankTransaction,
                account.getLedgerAccount(),
                EntryDirection.CREDIT,
                request.amount()
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
