package com.neobank.neobank.transaction.history;

import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;

public class TransactionHistoryMapper {
    public static TransactionHistoryResponse toResponse(LedgerEntry entry, String accountNumber) {
        boolean isTransferOperationReceiver =
                (entry.getBankTransaction().getTransactionType() == TransactionType.TRANSFER)
                        &&
                        (entry.getDirection() == EntryDirection.CREDIT);

        return new TransactionHistoryResponse(
                accountNumber,
                entry.getReference(),
                entry.getBankTransaction().getTransactionType(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getCurrency(),
                isTransferOperationReceiver ? null : entry.getBankTransaction().getRequestedAmount(),
                isTransferOperationReceiver ? null : entry.getBankTransaction().getRequestedCurrency(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }
}
