package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final BankTransactionRepository bankTransactionRepository;

    private final AccountEntryRepository accountEntryRepository;

    private final AccountRepository accountRepository;

    private final TransactionReferenceGenerator transactionReferenceGenerator;

    @Transactional
    public WithdrawalResponse withdraw(WithdrawalRequest request, String accountNumber, String customerEmail) {
        var account = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customerEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        BigDecimal balanceAfter = account.debit(request.amount());
        String reference = transactionReferenceGenerator.generate();

        var bankTransaction = BankTransaction.createNew(
                TransactionType.WITHDRAWAL,
                reference,
                request.note()
        );

        var savedBankTransaction = bankTransactionRepository.save(bankTransaction);

        var accountEntry = AccountEntry.createNew(
                request.amount(),
                balanceAfter,
                EntryDirection.DEBIT,
                account,
                savedBankTransaction
        );

        var savedAccountEntry = accountEntryRepository.save(accountEntry);

        return WithdrawalMapper.toResponse(savedBankTransaction, savedAccountEntry);
    }
}
