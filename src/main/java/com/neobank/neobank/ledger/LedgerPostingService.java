package com.neobank.neobank.ledger;

import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class LedgerPostingService {

    private final ReferenceGenerator referenceGenerator;

    public LedgerEntry post(
            BankTransaction transaction,
            LedgerAccount ledgerAccount,
            EntryDirection direction,
            BigDecimal amount
    ) {
        if(transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }

        if(ledgerAccount == null) {
            throw new IllegalArgumentException("ledgerAccount must not be null");
        }

        if(direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }

        if(!transaction.getStatus().equals(TransactionStatus.PENDING)) {
            throw new IllegalStateException("transaction must be pending");
        }

        String entryReference = referenceGenerator.generate();

        BigDecimal balanceAfter = switch (direction) {
            case CREDIT -> ledgerAccount.credit(amount);
            case DEBIT -> ledgerAccount.debit(amount);
        };

        return transaction.addEntry(entryReference, amount, balanceAfter, direction, ledgerAccount);
    }

}
