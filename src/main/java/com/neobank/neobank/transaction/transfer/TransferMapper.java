package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TransferMapper {
    public TransferResponse toResponse(
            BankTransaction transaction,
            LedgerEntry sourceAccountEntry,
            String sourceAccountNumber,
            String destinationAccountNumber
    ) {
        return new TransferResponse(
                transaction.getReference(),
                sourceAccountEntry.getReference(),
                transaction.getTransactionType(),
                sourceAccountNumber,
                destinationAccountNumber,
                transaction.getRequestedAmount(),
                sourceAccountEntry.getBalanceAfter(),
                transaction.getRequestedCurrency(),
                transaction.getNote(),
                transaction.getCreatedAt()
        );
    }
}
