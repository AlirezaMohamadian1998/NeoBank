package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.transaction.AccountEntry;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import lombok.experimental.UtilityClass;

@UtilityClass
public class WithdrawalMapper {
    public static WithdrawalResponse toResponse(BankTransaction transaction, AccountEntry entry) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (entry == null) {
            throw new IllegalArgumentException("Entry cannot be null");
        }
        return new WithdrawalResponse(
                transaction.getReference(),
                transaction.getTransactionType(),
                entry.getAccount().getAccountNumber(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getBalanceAfter(),
                transaction.getNote(),
                transaction.getCreatedAt()
        );
    }
}
