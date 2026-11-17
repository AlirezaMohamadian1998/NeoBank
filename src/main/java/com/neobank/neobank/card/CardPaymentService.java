package com.neobank.neobank.card;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CardPurchaseResponse;
import com.neobank.neobank.card.exception.CardExpiredException;
import com.neobank.neobank.card.exception.CardNotActiveException;
import com.neobank.neobank.card.exception.DebitCardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCardPaymentException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
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
import java.time.Clock;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CardPaymentService {

    private final LedgerPostingService ledgerPostingService;

    private final FxRateService fxRateService;

    private final IdempotencyService idempotencyService;

    private final CardPurchaseDetailsRepository cardPurchaseDetailsRepository;

    private final DebitCardRepository debitCardRepository;

    private final AccountRepository accountRepository;

    private final InternalAccountRepository internalAccountRepository;

    private final RequestHasher requestHasher;

    private final ObjectMapper objectMapper;

    private final ReferenceGenerator referenceGenerator;

    private final Clock clock;

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
    public CardPurchaseResponse makePurchase(
            String cardReference,
            CardPurchaseRequest request,
            String idempotencyKey,
            String customerEmail
    ) {
        if(!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new InvalidIdempotencyKeyException("Idempotency key is invalid");
        }

        DebitCard debitCard =
                debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(cardReference, customerEmail)
                        .orElseThrow(() -> new DebitCardNotFoundException("Debit card not found"));

        boolean isExternalMerchant = request.merchantAccountNumber() == null;

        LedgerAccount destinationLedgerAccount =
                isExternalMerchant
                        ? internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, request.currency())
                        .orElseThrow(() -> new IllegalStateException("Settlement account is not configured for " + request.currency()))
                        .getLedgerAccount()
                        : accountRepository.findByAccountNumber(request.merchantAccountNumber())
                        .orElseThrow(() -> new AccountNotFoundException("Merchant's account not found"))
                        .getLedgerAccount();

        if(destinationLedgerAccount.getId().equals(debitCard.getFundingAccount().getLedgerAccount().getId())) {
            throw new InvalidCardPaymentException("Merchant's account and the card's funding account cannot be same");
        }

        boolean isCrossCurrency =
                (request.currency() != debitCard.getFundingAccount().getCurrency())
                        || (!isExternalMerchant && request.currency() != destinationLedgerAccount.getCurrency());

        CardPurchaseCanonicalRequest canonicalRequest = new CardPurchaseCanonicalRequest(
                TransactionType.DEBIT_CARD_PURCHASE,
                cardReference,
                request.merchantAccountNumber(),
                request.merchantName(),
                request.amount(),
                request.currency(),
                request.note()
        );

        String normalizedNote = canonicalRequest.note();
        String requestHash = requestHasher.hashRequest(objectMapper.writeValueAsString(canonicalRequest));

        Optional<IdempotencyRecord> idempotencyRecord =
                idempotencyService.findAndValidateRecord(idempotencyKey, customerEmail, requestHash);

        if(idempotencyRecord.isPresent()) {
            CardPurchaseDetails existingCardPurchaseDetails =
                    cardPurchaseDetailsRepository.findByBankTransaction_Reference(idempotencyRecord.get().getResultReference())
                            .orElseThrow(() -> new IdempotencyResultNotFoundException("Idempotency result not found"));

            BankTransaction existingTransaction = existingCardPurchaseDetails.getBankTransaction();

            LedgerEntry existingEntry = existingTransaction.getEntries().stream()
                    .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                    .findFirst()
                    .orElseThrow();

            return DebitCardMapper
                    .toCardPurchaseResponse(debitCard, existingCardPurchaseDetails, existingEntry);
        }

        if(debitCard.isExpired(YearMonth.now(clock))) {
            throw new CardExpiredException("Card is Expired");
        }

        if(debitCard.getStatus() != CardStatus.ACTIVE) {
            throw new CardNotActiveException("Card must be active for purchase");
        }

        BankTransaction bankTransaction = BankTransaction.createNew(
                request.amount(),
                request.currency(),
                TransactionType.DEBIT_CARD_PURCHASE,
                referenceGenerator.generate(),
                normalizedNote.isEmpty() ? null : normalizedNote
        );

        FxInfo fxInfo = null;

        if(isCrossCurrency) {
            Map<CurrencyCode, BigDecimal> rates = fxRateService.getRates(request.currency());

            fxInfo = FxInfo.createNew(
                    null,
                    Set.of(
                            FxRate.createNew(
                                    CurrencyContext.REQUEST,
                                    request.currency(),
                                    rates.get(request.currency())
                            ),
                            FxRate.createNew(
                                    CurrencyContext.DESTINATION,
                                    destinationLedgerAccount.getCurrency(),
                                    rates.get(destinationLedgerAccount.getCurrency())
                            ),
                            FxRate.createNew(
                                    CurrencyContext.SOURCE,
                                    debitCard.getFundingAccount().getCurrency(),
                                    rates.get(debitCard.getFundingAccount().getCurrency())
                            )
                    )
            );

            bankTransaction.addFxInfo(fxInfo);
        }

        LedgerEntry sourceEntry = ledgerPostingService.post(
                bankTransaction,
                debitCard.getFundingAccount().getLedgerAccount(),
                EntryDirection.DEBIT,
                request.amount().multiply(
                        fxInfo == null
                                ? BigDecimal.ONE
                                : fxInfo.getRate(CurrencyContext.SOURCE)
                ).setScale(2, RoundingMode.HALF_EVEN)
        );

        ledgerPostingService.post(
                bankTransaction,
                destinationLedgerAccount,
                EntryDirection.CREDIT,
                request.amount().multiply(
                        fxInfo == null
                                ? BigDecimal.ONE
                                : fxInfo.getRate(CurrencyContext.DESTINATION)
                ).setScale(2, RoundingMode.HALF_EVEN)
        );

        bankTransaction.complete();

        CardPurchaseDetails cardPurchaseDetails =
                cardPurchaseDetailsRepository.save(
                        CardPurchaseDetails.createNew(
                                bankTransaction,
                                request.merchantName(),
                                cardReference
                        )
                );

        idempotencyService.save(
                IdempotencyRecord.createNew(
                        idempotencyKey,
                        requestHash,
                        debitCard.getFundingAccount().getCustomer(),
                        bankTransaction.getReference()
                )
        );

        return DebitCardMapper.toCardPurchaseResponse(
                debitCard,
                cardPurchaseDetails,
                sourceEntry
        );
    }
}
