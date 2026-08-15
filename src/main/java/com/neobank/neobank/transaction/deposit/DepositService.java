package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;

    private final BankTransactionRepository bankTransactionRepository;

    private final AccountEntryRepository accountEntryRepository;

    private final TransactionReferenceGenerator transactionReferenceGenerator;

    @Transactional
    public DepositResponse deposit(
            DepositRequest request,
            String accountNumber,
            String customerEmail
    ) {
        Account account = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customerEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        BigDecimal balanceAfter = account.credit(request.amount());

        BankTransaction bankTransaction = bankTransactionRepository.save(BankTransaction.createNew(
                TransactionType.DEPOSIT,
                transactionReferenceGenerator.generate(),
                request.note()
        ));

        AccountEntry accountEntry = accountEntryRepository.save(AccountEntry.createNew(
                request.amount(),
                balanceAfter,
                EntryDirection.CREDIT,
                account,
                bankTransaction
        ));

        return DepositMapper.toResponse(bankTransaction, accountEntry);
    }
}
