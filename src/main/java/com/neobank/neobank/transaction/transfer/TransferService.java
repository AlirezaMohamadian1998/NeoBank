package com.neobank.neobank.transaction.transfer;

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
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final BankTransactionRepository bankTransactionRepository;

    private final ReferenceGenerator referenceGenerator;

    private final AccountRepository accountRepository;

    private final LedgerPostingService ledgerPostingService;

    private final IdempotencyService idempotencyService;

    private final RequestHasher requestHasher;

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
        if (!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Invalid idempotency key");
        }

        Account sourceAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, senderEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        if (sourceAccountNumber.equals(request.destinationAccountNumber())) {
            throw new InvalidTransferException("Source and destination accounts cannot be the same");
        }

        Account destinationAccount = accountRepository.findByAccountNumber(request.destinationAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found"));

        if ((sourceAccount.getCurrency() != destinationAccount.getCurrency()) || (sourceAccount.getCurrency() != request.currency())) {
            throw new InvalidTransferException("Source and destination accounts must be in the same currency as request");
        }

        String normalizedNote = "";
        if (request.note() != null) {
            normalizedNote = request.note().trim();
            if (normalizedNote.length() > 255) {
                throw new IllegalArgumentException("Note length must not exceed 255 characters");
            }
        }

        String canonicalRequest = String.join(
                "|",
                TransactionType.TRANSFER.name(),
                sourceAccountNumber,
                destinationAccount.getAccountNumber(),
                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                normalizedNote
        );

        String requestHash = requestHasher.hashRequest(canonicalRequest);

        var existingIdempotencyRecord = idempotencyService.findAndValidateRecord(idempotencyKey, senderEmail, requestHash);

        if (existingIdempotencyRecord.isPresent()) {
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

        var sourceLedgerEntry = ledgerPostingService.post(
                bankTransaction,
                sourceAccount.getLedgerAccount(),
                EntryDirection.DEBIT,
                request.amount()
        );

        ledgerPostingService.post(
                bankTransaction,
                destinationAccount.getLedgerAccount(),
                EntryDirection.CREDIT,
                request.amount()
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
