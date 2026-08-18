package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;

    private final BankTransactionRepository bankTransactionRepository;

    private final ReferenceGenerator referenceGenerator;

    private final LedgerPostingService ledgerPostingService;

    @Transactional
    public DepositResponse deposit(
            DepositRequest request,
            String accountNumber,
            String customerEmail
    ) {
        Account account = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customerEmail)
                .orElseThrow(() -> new AccountNotFoundException());

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

        return DepositMapper.toResponse(savedTransaction, entry, accountNumber);
    }
}
