package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final BankTransactionRepository bankTransactionRepository;

    private final AccountEntryRepository accountEntryRepository;

    private final TransactionReferenceGenerator transactionReferenceGenerator;

    private final AccountRepository accountRepository;

    @Transactional
    public TransferResponse transfer(TransferRequest request, String sourceAccountNumber,String senderEmail) {
        Account sourceAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, senderEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        if(sourceAccountNumber.equals(request.destinationAccountNumber())) {
            throw new InvalidTransferException("Source and destination accounts cannot be the same");
        }

        Account destinationAccount = accountRepository.findByAccountNumber(request.destinationAccountNumber())
                .orElseThrow(() -> new AccountNotFoundException("Destination account not found"));

        if(sourceAccount.getCurrency() != destinationAccount.getCurrency()) {
            throw new InvalidTransferException("Source and destination accounts must be in the same currency");
        }

        var sourceAccountBalanceAfter = sourceAccount.debit(request.amount());
        var destinationAccountBalanceAfter = destinationAccount.credit(request.amount());

        BankTransaction bankTransaction = bankTransactionRepository.save(BankTransaction.createNew(
                TransactionType.TRANSFER,
                transactionReferenceGenerator.generate(),
                request.note()
        ));

        AccountEntry sourceAccountEntry = accountEntryRepository.save(AccountEntry.createNew(
                request.amount(),
                sourceAccountBalanceAfter,
                EntryDirection.DEBIT,
                sourceAccount,
                bankTransaction
        ));

        AccountEntry destinationAccountEntry = accountEntryRepository.save(AccountEntry.createNew(
                request.amount(),
                destinationAccountBalanceAfter,
                EntryDirection.CREDIT,
                destinationAccount,
                bankTransaction
        ));

        return TransferMapper.toResponse(bankTransaction, sourceAccountEntry, destinationAccountEntry);
    }
}
