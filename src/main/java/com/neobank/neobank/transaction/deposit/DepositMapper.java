package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DepositMapper {

    public static DepositResponse toResponse(BankTransaction transaction, LedgerEntry entry, String accountNumber) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (entry == null) {
            throw new IllegalArgumentException("Entry cannot be null");
        }

        return new DepositResponse(
                transaction.getReference(),
                entry.getReference(),
                transaction.getTransactionType(),
                accountNumber,
                entry.getAmount(),
                entry.getCurrency(),
                entry.getBalanceAfter(),
                transaction.getNote(),
                transaction.getCreatedAt()
        );
    }
}
