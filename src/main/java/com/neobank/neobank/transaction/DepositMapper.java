package com.neobank.neobank.transaction;

import com.neobank.neobank.transaction.dto.DepositResponse;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DepositMapper {

    public static DepositResponse toResponse(BankTransaction transaction, AccountEntry entry) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (entry == null) {
            throw new IllegalArgumentException("Entry cannot be null");
        }

        return new DepositResponse(
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
