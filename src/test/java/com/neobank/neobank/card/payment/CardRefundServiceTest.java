package com.neobank.neobank.card.payment;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.card.payment.dto.CardRefundResponse;
import com.neobank.neobank.creditaccount.CreditAccountRepository;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyResultNotFoundException;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.neobank.neobank.support.TestFixtures.currentAccount;
import static com.neobank.neobank.support.TestFixtures.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardRefundServiceTest {

    private static final String IDEMPOTENCY_KEY = "1".repeat(32);
    private static final String PURCHASE_REFERENCE = "6".repeat(32);
    private static final String REFUND_REFERENCE = "a".repeat(32);
    private static final String MERCHANT_ACCOUNT_NUMBER = "98".repeat(7);
    private static final String MERCHANT_EMAIL = "merchant@example.com";

    @Mock
    private FxRateService fxRateService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private CardPurchaseDetailsRepository cardPurchaseDetailsRepository;

    @Mock
    private CardRefundDetailsRepository cardRefundDetailsRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CreditAccountRepository creditAccountRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Captor
    private ArgumentCaptor<CardRefundDetails> cardRefundDetailsCaptor;

    private final RequestHasher requestHasher = new RequestHasher();

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private CardRefundService cardRefundService;

    private Customer merchantCustomer;

    @BeforeEach
    void setUp() {
        cardRefundService = new CardRefundService(
                new LedgerPostingService(referenceGenerator),
                fxRateService,
                idempotencyService,
                bankTransactionRepository,
                cardPurchaseDetailsRepository,
                cardRefundDetailsRepository,
                accountRepository,
                creditAccountRepository,
                requestHasher,
                objectMapper,
                referenceGenerator
        );

        merchantCustomer = customer(MERCHANT_EMAIL);
    }

    @Test
    void crossCurrencyRefundUsesFreshRatesNotThePurchaseSnapshot() {
        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer("customer@example.com"), 1L);
        Account merchantAccount = createAccount(MERCHANT_ACCOUNT_NUMBER, "4".repeat(32), CurrencyCode.USD, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        // Purchase: 10 USD paid from a TRY account at 40 TRY per USD.
        CardPurchaseDetails purchase = createCompletedPurchase(
                fundingAccount, merchantAccount, new BigDecimal("10.00"), CurrencyCode.USD, new BigDecimal("40.00"), BigDecimal.ONE
        );

        stubUpToPurchaseLookup(merchantAccount, purchase);
        stubRefundCreation();

        // The rate has moved since the purchase.
        given(fxRateService.getRates(CurrencyCode.USD))
                .willReturn(Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("42.50"),
                        CurrencyCode.EUR, new BigDecimal("0.85"),
                        CurrencyCode.GBP, new BigDecimal("0.75")
                ));

        CardRefundResponse response = cardRefundService.refundPurchase(
                MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, MERCHANT_EMAIL
        );

        verify(cardRefundDetailsRepository).save(cardRefundDetailsCaptor.capture());

        BankTransaction refundTransaction = cardRefundDetailsCaptor.getValue().getBankTransaction();
        FxInfo refundFxInfo = refundTransaction.getFxInfo();

        assertThat(refundFxInfo)
                .isNotNull()
                .isNotSameAs(purchase.getBankTransaction().getFxInfo());

        assertThat(refundFxInfo.getRate(CurrencyContext.REQUEST))
                .isEqualByComparingTo("1");

        assertThat(refundFxInfo.getRate(CurrencyContext.SOURCE))
                .isEqualByComparingTo("1");

        assertThat(refundFxInfo.getRate(CurrencyContext.DESTINATION))
                .isEqualByComparingTo("42.50");

        assertThat(entry(refundTransaction, EntryDirection.DEBIT).getAmount())
                .isEqualByComparingTo("10.00");

        assertThat(entry(refundTransaction, EntryDirection.CREDIT).getAmount())
                .as("credited at the refund-time rate, not the 40.00 of the purchase")
                .isEqualByComparingTo("425.00");

        assertThat(purchase.getBankTransaction().getFxInfo().getRate(CurrencyContext.SOURCE))
                .isEqualByComparingTo("40.00");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("1025.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(response.debitedAmount())
                .isEqualByComparingTo("10.00");

        assertThat(response.debitedCurrency())
                .isSameAs(CurrencyCode.USD);
    }

    @Test
    void refundPurchaseRejectsInvalidIdempotencyKey() {
        assertThatThrownBy(() -> cardRefundService.refundPurchase(
                MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, "not-a-valid-key", MERCHANT_EMAIL
        ))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(accountRepository, idempotencyService, bankTransactionRepository, cardRefundDetailsRepository);
    }

    @Test
    void refundPurchaseTakesThePurchaseLockBeforeAnyOtherRead() {
        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer("customer@example.com"), 1L);
        Account merchantAccount = createAccount(MERCHANT_ACCOUNT_NUMBER, "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        CardPurchaseDetails purchase = createCompletedPurchase(
                fundingAccount, merchantAccount, new BigDecimal("250.00"), CurrencyCode.TRY, BigDecimal.ONE, BigDecimal.ONE
        );

        stubUpToPurchaseLookup(merchantAccount, purchase);
        stubRefundCreation();

        cardRefundService.refundPurchase(MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, MERCHANT_EMAIL);

        var inOrder = inOrder(bankTransactionRepository, accountRepository, idempotencyService, cardPurchaseDetailsRepository);

        inOrder.verify(bankTransactionRepository)
                .findForUpdateByReferenceAndTransactionType(PURCHASE_REFERENCE, TransactionType.CARD_PURCHASE);
        inOrder.verify(accountRepository)
                .findByAccountNumberAndCustomer_EmailIgnoreCase(MERCHANT_ACCOUNT_NUMBER, MERCHANT_EMAIL);
        inOrder.verify(idempotencyService)
                .findAndValidateRecord(IDEMPOTENCY_KEY, MERCHANT_EMAIL, hash());
        inOrder.verify(cardPurchaseDetailsRepository)
                .findByBankTransaction_Reference(PURCHASE_REFERENCE);
    }

    @Test
    void refundPurchaseFailsWithoutMarkingPurchaseWhenMerchantCannotCoverRefund() {
        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer("customer@example.com"), 1L);
        Account merchantAccount = createAccount(MERCHANT_ACCOUNT_NUMBER, "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        CardPurchaseDetails purchase = createCompletedPurchase(
                fundingAccount, merchantAccount, new BigDecimal("250.00"), CurrencyCode.TRY, BigDecimal.ONE, BigDecimal.ONE
        );

        // The merchant has spent most of the money since the purchase.
        merchantAccount.getLedgerAccount().debit(new BigDecimal("200.00"));

        stubUpToPurchaseLookup(merchantAccount, purchase);

        given(referenceGenerator.generate())
                .willReturn(REFUND_REFERENCE, "b".repeat(32));

        assertThatThrownBy(() -> cardRefundService.refundPurchase(
                MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, MERCHANT_EMAIL
        ))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(purchase.getBankTransaction().getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("50.00");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("750.00");

        verify(cardRefundDetailsRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
    }

    @Test
    void refundPurchaseRejectsWhenTheAccountToRefundToIsClosed() {
        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer("customer@example.com"), 1L);
        Account merchantAccount = createAccount(MERCHANT_ACCOUNT_NUMBER, "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        CardPurchaseDetails purchase = createCompletedPurchase(
                fundingAccount, merchantAccount, new BigDecimal("250.00"), CurrencyCode.TRY, BigDecimal.ONE, BigDecimal.ONE
        );

        fundingAccount.getLedgerAccount().debit(new BigDecimal("750.00"));
        fundingAccount.getLedgerAccount().close();

        stubUpToPurchaseLookup(merchantAccount, purchase);

        assertThatThrownBy(() -> cardRefundService.refundPurchase(
                MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, MERCHANT_EMAIL
        ))
                .isInstanceOf(CardPurchaseNotRefundableException.class)
                .hasMessage("The account to refund to is closed");

        assertThat(purchase.getBankTransaction().getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("250.00");

        verify(cardRefundDetailsRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
    }

    @Test
    void refundPurchaseFailsWhenStoredIdempotencyResultIsMissing() {
        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer("customer@example.com"), 1L);
        Account merchantAccount = createAccount(MERCHANT_ACCOUNT_NUMBER, "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        CardPurchaseDetails purchase = createCompletedPurchase(
                fundingAccount, merchantAccount, new BigDecimal("250.00"), CurrencyCode.TRY, BigDecimal.ONE, BigDecimal.ONE
        );

        given(bankTransactionRepository.findForUpdateByReferenceAndTransactionType(PURCHASE_REFERENCE, TransactionType.CARD_PURCHASE))
                .willReturn(Optional.of(purchase.getBankTransaction()));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(MERCHANT_ACCOUNT_NUMBER, MERCHANT_EMAIL))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord(IDEMPOTENCY_KEY, MERCHANT_EMAIL, hash()))
                .willReturn(Optional.of(IdempotencyRecord.createNew(IDEMPOTENCY_KEY, hash(), merchantCustomer, REFUND_REFERENCE)));

        given(cardRefundDetailsRepository.findByBankTransaction_Reference(REFUND_REFERENCE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> cardRefundService.refundPurchase(
                MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, MERCHANT_EMAIL
        ))
                .isInstanceOf(IdempotencyResultNotFoundException.class)
                .hasMessage("Idempotency result not found");

        verifyNoInteractions(cardPurchaseDetailsRepository, referenceGenerator);
    }

    private void stubUpToPurchaseLookup(Account merchantAccount, CardPurchaseDetails purchase) {
        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(MERCHANT_ACCOUNT_NUMBER, MERCHANT_EMAIL))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord(IDEMPOTENCY_KEY, MERCHANT_EMAIL, hash()))
                .willReturn(Optional.empty());

        given(bankTransactionRepository.findForUpdateByReferenceAndTransactionType(PURCHASE_REFERENCE, TransactionType.CARD_PURCHASE))
                .willReturn(Optional.of(purchase.getBankTransaction()));

        given(cardPurchaseDetailsRepository.findByBankTransaction_Reference(PURCHASE_REFERENCE))
                .willReturn(Optional.of(purchase));
    }

    private void stubRefundCreation() {
        given(referenceGenerator.generate())
                .willReturn(REFUND_REFERENCE, "b".repeat(32), "c".repeat(32));

        given(cardRefundDetailsRepository.save(any(CardRefundDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private String hash() {
        return requestHasher.hashRequest(objectMapper.writeValueAsString(
                new CardRefundCanonicalRequest(
                        TransactionType.CARD_REFUND,
                        MERCHANT_ACCOUNT_NUMBER,
                        PURCHASE_REFERENCE
                )
        ));
    }

    private Account createAccount(
            String accountNumber,
            String ledgerReference,
            CurrencyCode currency,
            Customer customer,
            Long ledgerAccountId
    ) {

        Account account = currentAccount(accountNumber, ledgerReference, customer, currency);
        ReflectionTestUtils.setField(account.getLedgerAccount(), "id", ledgerAccountId);

        return account;
    }

    private CardPurchaseDetails createCompletedPurchase(
            Account fundingAccount,
            Account merchantAccount,
            BigDecimal amount,
            CurrencyCode currency,
            BigDecimal sourceRate,
            BigDecimal destinationRate
    ) {
        LedgerAccount fundingLedgerAccount = fundingAccount.getLedgerAccount();
        LedgerAccount merchantLedgerAccount = merchantAccount.getLedgerAccount();

        BankTransaction transaction = BankTransaction.createNew(
                amount,
                currency,
                TransactionType.CARD_PURCHASE,
                PURCHASE_REFERENCE,
                null
        );

        if(currency != fundingLedgerAccount.getCurrency() || currency != merchantLedgerAccount.getCurrency()) {
            transaction.addFxInfo(FxInfo.createNew(
                    null,
                    Set.of(
                            FxRate.createNew(CurrencyContext.REQUEST, currency, BigDecimal.ONE),
                            FxRate.createNew(CurrencyContext.SOURCE, fundingLedgerAccount.getCurrency(), sourceRate),
                            FxRate.createNew(CurrencyContext.DESTINATION, merchantLedgerAccount.getCurrency(), destinationRate)
                    )
            ));
        }

        BigDecimal debitedAmount = amount.multiply(sourceRate).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal creditedAmount = amount.multiply(destinationRate).setScale(2, RoundingMode.HALF_EVEN);

        transaction.addEntry(
                "7".repeat(32),
                debitedAmount,
                fundingLedgerAccount.debit(debitedAmount),
                EntryDirection.DEBIT,
                fundingLedgerAccount
        );

        transaction.addEntry(
                "8".repeat(32),
                creditedAmount,
                merchantLedgerAccount.credit(creditedAmount),
                EntryDirection.CREDIT,
                merchantLedgerAccount
        );

        transaction.complete();

        return CardPurchaseDetails.createNew(
                transaction,
                "Test merchant",
                "2".repeat(32),
                "1234"
        );
    }

    private LedgerEntry entry(BankTransaction transaction, EntryDirection direction) {
        return transaction.getEntries().stream()
                .filter(entry -> entry.getDirection() == direction)
                .findFirst()
                .orElseThrow();
    }
}
