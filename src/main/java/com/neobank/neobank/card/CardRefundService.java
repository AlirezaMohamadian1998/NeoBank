package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.card.dto.CardRefundResponse;
import com.neobank.neobank.card.exception.CardPurchaseNotFoundException;
import com.neobank.neobank.card.exception.CardPurchaseNotRefundableException;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditAccountRepository;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CardRefundService {

    private final LedgerPostingService ledgerPostingService;

    private final FxRateService fxRateService;

    private final IdempotencyService idempotencyService;

    private final BankTransactionRepository bankTransactionRepository;

    private final CardPurchaseDetailsRepository cardPurchaseDetailsRepository;

    private final CardRefundDetailsRepository cardRefundDetailsRepository;

    private final AccountRepository accountRepository;

    private final CreditAccountRepository creditAccountRepository;

    private final RequestHasher requestHasher;

    private final ObjectMapper objectMapper;

    private final ReferenceGenerator referenceGenerator;

    /**
     * Fully refunds a card purchase on behalf of the merchant account's owner.
     * Only purchases credited to a NeoBank account can be refunded this way; purchases paid to
     * outside merchants (credited to the settlement account) are refunded through the card network,
     * which is not simulated.
     */
    @Transactional
    @Retryable(
            delay = 200,
            maxDelay = 4000,
            maxRetries = 4,
            multiplier = 2,
            jitter = 50,
            includes = {
                    IdempotencyKeyRaceException.class,
                    ConcurrencyFailureException.class
            }
    )
    public CardRefundResponse refundPurchase(
            String merchantAccountNumber,
            String purchaseReference,
            String idempotencyKey,
            String customerEmail
    ) {
        if(!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Idempotency key is invalid");
        }

        // The lock must be the first query of the transaction. Concurrent refunds of the same purchase
        // queue here until the holder commits. Under REPEATABLE READ, the snapshot for plain reads is
        // taken at the first non-locking read, so a waiter that reads nothing before this point sees
        // the holder's committed work afterwards: its idempotency record (same key -> replay) or the
        // REFUNDED status (different key -> 409). Reading anything first would freeze an older snapshot.
        BankTransaction purchaseTransaction =
                bankTransactionRepository.findForUpdateByReferenceAndTransactionType(purchaseReference, TransactionType.CARD_PURCHASE)
                        .orElseThrow(() -> new CardPurchaseNotFoundException("Card purchase not found"));

        Account merchantAccount =
                accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(merchantAccountNumber, customerEmail)
                        .orElseThrow(() -> new AccountNotFoundException("Merchant's account not found"));

        CardRefundCanonicalRequest canonicalRequest = new CardRefundCanonicalRequest(
                TransactionType.CARD_REFUND,
                merchantAccountNumber,
                purchaseReference
        );

        String requestHash = requestHasher.hashRequest(objectMapper.writeValueAsString(canonicalRequest));

        Optional<IdempotencyRecord> idempotencyRecord =
                idempotencyService.findAndValidateRecord(idempotencyKey, customerEmail, requestHash);

        if(idempotencyRecord.isPresent()) {
            CardRefundDetails existingRefund =
                    cardRefundDetailsRepository.findByBankTransaction_Reference(idempotencyRecord.get().getResultReference())
                            .orElseThrow(() -> new IdempotencyResultNotFoundException("Idempotency result not found"));

            return CardMapper.toCardRefundResponse(
                    existingRefund,
                    getEntry(existingRefund.getBankTransaction(), EntryDirection.DEBIT),
                    merchantAccount.getAccountNumber()
            );
        }

        CardPurchaseDetails cardPurchaseDetails =
                cardPurchaseDetailsRepository.findByBankTransaction_Reference(purchaseReference)
                        .orElseThrow(() -> new IllegalStateException(
                                "Card purchase details not found for transaction " + purchaseReference
                        ));

        LedgerAccount creditedLedgerAccount = getEntry(purchaseTransaction, EntryDirection.CREDIT).getLedgerAccount();

        // Same response as an unknown reference, so merchants cannot probe other merchants' purchases.
        if(!creditedLedgerAccount.getId().equals(merchantAccount.getLedgerAccount().getId())) {
            throw new CardPurchaseNotFoundException("Card purchase not found");
        }

        if(purchaseTransaction.getStatus() == TransactionStatus.REFUNDED) {
            throw new CardPurchaseNotRefundableException("Card purchase is already refunded");
        }

        if(purchaseTransaction.getStatus() != TransactionStatus.COMPLETED) {
            throw new CardPurchaseNotRefundableException("Only completed card purchases can be refunded");
        }

        CardRefundDetails cardRefundDetails = reversePurchase(cardPurchaseDetails);

        idempotencyService.save(
                IdempotencyRecord.createNew(
                        idempotencyKey,
                        requestHash,
                        merchantAccount.getCustomer(),
                        cardRefundDetails.getBankTransaction().getReference()
                )
        );

        return CardMapper.toCardRefundResponse(
                cardRefundDetails,
                getEntry(cardRefundDetails.getBankTransaction(), EntryDirection.DEBIT),
                merchantAccount.getAccountNumber()
        );
    }

    /**
     * Moves the purchase's requested amount back from whichever ledger account the purchase credited
     * to the card's funding account, as a new transaction, converting with current FX rates.
     * If the funding account is a closed credit account, the refund goes to the account that paid it off.
     * It does not check who is asking; callers authorize before calling it.
     */
    private CardRefundDetails reversePurchase(CardPurchaseDetails cardPurchaseDetails) {
        BankTransaction purchaseTransaction = cardPurchaseDetails.getBankTransaction();

        LedgerAccount sourceLedgerAccount = getEntry(purchaseTransaction, EntryDirection.CREDIT).getLedgerAccount();
        LedgerAccount fundingLedgerAccount = getEntry(purchaseTransaction, EntryDirection.DEBIT).getLedgerAccount();

        LedgerAccount destinationLedgerAccount = creditAccountRepository.findByLedgerAccount(fundingLedgerAccount)
                .filter(CreditAccount::isClosed)
                .map(creditAccount -> creditAccount.getPayoffAccount().getLedgerAccount())
                .orElse(fundingLedgerAccount);

        if(destinationLedgerAccount.getStatus() != LedgerAccountStatus.ACTIVE) {
            throw new CardPurchaseNotRefundableException("The account to refund to is closed");
        }

        BigDecimal requestedAmount = purchaseTransaction.getRequestedAmount();
        CurrencyCode requestedCurrency = purchaseTransaction.getRequestedCurrency();

        BankTransaction refundTransaction = BankTransaction.createNew(
                requestedAmount,
                requestedCurrency,
                TransactionType.CARD_REFUND,
                referenceGenerator.generate(),
                null
        );

        boolean isCrossCurrency =
                requestedCurrency != sourceLedgerAccount.getCurrency()
                        || requestedCurrency != destinationLedgerAccount.getCurrency();

        FxInfo fxInfo = null;

        if(isCrossCurrency) {
            Map<CurrencyCode, BigDecimal> rates = fxRateService.getRates(requestedCurrency);

            fxInfo = FxInfo.createNew(
                    null,
                    Set.of(
                            FxRate.createNew(
                                    CurrencyContext.REQUEST,
                                    requestedCurrency,
                                    rates.get(requestedCurrency)
                            ),
                            FxRate.createNew(
                                    CurrencyContext.SOURCE,
                                    sourceLedgerAccount.getCurrency(),
                                    rates.get(sourceLedgerAccount.getCurrency())
                            ),
                            FxRate.createNew(
                                    CurrencyContext.DESTINATION,
                                    destinationLedgerAccount.getCurrency(),
                                    rates.get(destinationLedgerAccount.getCurrency())
                            )
                    )
            );

            refundTransaction.addFxInfo(fxInfo);
        }

        ledgerPostingService.post(
                refundTransaction,
                sourceLedgerAccount,
                EntryDirection.DEBIT,
                requestedAmount.multiply(
                        fxInfo == null
                                ? BigDecimal.ONE
                                : fxInfo.getRate(CurrencyContext.SOURCE)
                ).setScale(2, RoundingMode.HALF_EVEN)
        );

        ledgerPostingService.post(
                refundTransaction,
                destinationLedgerAccount,
                EntryDirection.CREDIT,
                requestedAmount.multiply(
                        fxInfo == null
                                ? BigDecimal.ONE
                                : fxInfo.getRate(CurrencyContext.DESTINATION)
                ).setScale(2, RoundingMode.HALF_EVEN)
        );

        refundTransaction.complete();
        purchaseTransaction.markRefunded();

        return cardRefundDetailsRepository.save(
                CardRefundDetails.createNew(refundTransaction, cardPurchaseDetails)
        );
    }

    private static LedgerEntry getEntry(BankTransaction transaction, EntryDirection direction) {
        return transaction.getEntries().stream()
                .filter(entry -> entry.getDirection() == direction)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Transaction " + transaction.getReference() + " has no " + direction + " entry"
                ));
    }
}
