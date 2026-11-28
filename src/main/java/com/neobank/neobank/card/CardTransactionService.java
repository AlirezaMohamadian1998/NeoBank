package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardTransactionFilter;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.LedgerEntryRepository;
import com.neobank.neobank.transaction.history.LedgerEntrySpecifications;
import com.neobank.neobank.transaction.history.TransactionHistoryItemAssembler;
import com.neobank.neobank.transaction.history.TransactionHistorySortHelper;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CardTransactionService {

    private final CardRepository cardRepository;

    private final LedgerEntryRepository ledgerEntryRepository;

    private final TransactionHistoryItemAssembler transactionHistoryItemAssembler;

    @Transactional(readOnly = true)
    public Page<TransactionHistoryItem> getTransactions(
            String cardReference,
            CardTransactionFilter filter,
            String customerEmail,
            Pageable pageable
    ) {
        Card card = cardRepository.findByCardReference(cardReference)
                .filter(found -> found.getOwner().getEmail().equalsIgnoreCase(customerEmail))
                .orElseThrow(() -> new CardNotFoundException("Card not found"));

        Specification<LedgerEntry> spec =
                LedgerEntrySpecifications.belongsToAccount(List.of(card.getLinkedLedgerAccount().getId()))
                        .and(LedgerEntrySpecifications.madeWithCard(card.getCardReference()))
                        .and(LedgerEntrySpecifications.createdAfterOrEqual(filter.dateFrom()))
                        .and(LedgerEntrySpecifications.createdBefore(filter.dateTo()));

        Page<LedgerEntry> entries =
                ledgerEntryRepository.findAll(spec, TransactionHistorySortHelper.normalize(pageable));

        return transactionHistoryItemAssembler.toItems(entries, entry -> card.getLinkedAccountNumber());
    }
}
