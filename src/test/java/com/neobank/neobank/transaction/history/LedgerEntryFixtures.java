package com.neobank.neobank.transaction.history;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionType;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;

@UtilityClass
class LedgerEntryFixtures {

    static BankTransaction completedTransaction(
            TransactionType type,
            CurrencyCode requestedCurrency,
            String amount,
            String reference,
            String debitReference,
            LedgerAccount debitedLedgerAccount,
            String creditReference,
            LedgerAccount creditedLedgerAccount
    ) {
        BigDecimal value = new BigDecimal(amount);
        BankTransaction transaction = BankTransaction.createNew(value, requestedCurrency, type, reference, null);

        transaction.addEntry(debitReference, value, value, EntryDirection.DEBIT, debitedLedgerAccount);
        transaction.addEntry(creditReference, value, value, EntryDirection.CREDIT, creditedLedgerAccount);
        transaction.complete();

        return transaction;
    }

    static LedgerEntry entryOn(BankTransaction transaction, LedgerAccount ledgerAccount) {
        return transaction.getEntries().stream()
                .filter(entry -> entry.getLedgerAccount() == ledgerAccount)
                .findFirst()
                .orElseThrow();
    }
}
