package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final BankTransactionRepository bankTransactionRepository;

    private final AccountRepository accountRepository;

    private final ReferenceGenerator referenceGenerator;

    private final LedgerPostingService ledgerPostingService;

    @Transactional
    public WithdrawalResponse withdraw(WithdrawalRequest request, String accountNumber, String customerEmail) {
        var account = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customerEmail)
                .orElseThrow(() -> new AccountNotFoundException());

        var ledgerAccount = account.getLedgerAccount();

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

        bankTransaction.complete();
        var savedTransaction = bankTransactionRepository.save(bankTransaction);

        return WithdrawalMapper.toResponse(savedTransaction, ledgerEntry, accountNumber);
    }
}
