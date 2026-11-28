package com.neobank.neobank.transaction.history;

import com.neobank.neobank.card.payment.CardPurchaseDetails;
import com.neobank.neobank.card.payment.CardPurchaseDetailsRepository;
import com.neobank.neobank.card.payment.CardRefundDetails;
import com.neobank.neobank.card.payment.CardRefundDetailsRepository;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TransactionHistoryItemAssembler {

    private final CardPurchaseDetailsRepository cardPurchaseDetailsRepository;

    private final CardRefundDetailsRepository cardRefundDetailsRepository;

    public Page<TransactionHistoryItem> toItems(
            Page<LedgerEntry> entries,
            Function<LedgerEntry, String> accountNumberOf
    ) {
        List<Long> cardPurchaseTransactionIds = entries.stream()
                .filter(TransactionHistoryMapper::isCardPurchasePayerEntry)
                .map(entry -> entry.getBankTransaction().getId())
                .toList();

        Map<Long, CardPurchaseDetails> cardPurchaseDetailsByTransactionId =
                cardPurchaseTransactionIds.isEmpty()
                        ? Map.of()
                        : cardPurchaseDetailsRepository.findAllByBankTransaction_IdIn(cardPurchaseTransactionIds)
                        .stream()
                        .collect(Collectors.toMap(
                                details -> details.getBankTransaction().getId(),
                                details -> details
                        ));

        List<Long> cardRefundTransactionIds = entries.stream()
                .filter(TransactionHistoryMapper::isCardRefundReceiverEntry)
                .map(entry -> entry.getBankTransaction().getId())
                .toList();

        Map<Long, CardRefundDetails> cardRefundDetailsByTransactionId =
                cardRefundTransactionIds.isEmpty()
                        ? Map.of()
                        : cardRefundDetailsRepository.findAllByBankTransaction_IdIn(cardRefundTransactionIds)
                        .stream()
                        .collect(Collectors.toMap(
                                details -> details.getBankTransaction().getId(),
                                details -> details
                        ));

        return entries.map(entry ->
                TransactionHistoryMapper.toResponse(
                        entry,
                        accountNumberOf.apply(entry),
                        cardPurchaseDetailsByTransactionId,
                        cardRefundDetailsByTransactionId
                )
        );
    }
}
