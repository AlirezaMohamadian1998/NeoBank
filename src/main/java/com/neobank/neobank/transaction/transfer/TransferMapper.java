package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.transaction.AccountEntry;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TransferMapper {
    public TransferResponse toResponse(
            BankTransaction transaction,
            AccountEntry sourceAccountEntry,
            AccountEntry destinationAccountEntry
    ) {
        return new TransferResponse(
                transaction.getReference(),
                transaction.getTransactionType(),
                sourceAccountEntry.getAccount().getAccountNumber(),
                destinationAccountEntry.getAccount().getAccountNumber(),
                sourceAccountEntry.getAmount(),
                sourceAccountEntry.getBalanceAfter(),
                sourceAccountEntry.getCurrency(),
                transaction.getNote(),
                transaction.getCreatedAt()
        );
    }
}
