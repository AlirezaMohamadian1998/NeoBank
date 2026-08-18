package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final BankTransactionRepository bankTransactionRepository;

    private final ReferenceGenerator referenceGenerator;

    private final AccountRepository accountRepository;

    private final LedgerPostingService ledgerPostingService;

    @Transactional
    public TransferResponse transfer(TransferRequest request, String sourceAccountNumber,String senderEmail) {
        Account sourceAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, senderEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        if(sourceAccountNumber.equals(request.destinationAccountNumber())) {
            throw new InvalidTransferException("Source and destination accounts cannot be the same");
        }

        Account destinationAccount = accountRepository.findByAccountNumber(request.destinationAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found"));

        if((sourceAccount.getCurrency() != destinationAccount.getCurrency()) || (sourceAccount.getCurrency() != request.currency())) {
            throw new InvalidTransferException("Source and destination accounts must be in the same currency as request");
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

        return TransferMapper.toResponse(
                savedTransaction,
                sourceLedgerEntry,
                sourceAccount.getAccountNumber(),
                destinationAccount.getAccountNumber()
        );
    }
}
