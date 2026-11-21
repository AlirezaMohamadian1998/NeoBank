package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CardPurchaseResponse;
import com.neobank.neobank.card.exception.CardExpiredException;
import com.neobank.neobank.card.exception.CardLimitExceededException;
import com.neobank.neobank.card.exception.CardNotActiveException;
import com.neobank.neobank.card.exception.CardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCardPaymentException;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.idempotency.IdempotencyConflictException;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyResultNotFoundException;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardPaymentServiceTest {

    @Mock
    private FxRateService fxRateService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private CardPurchaseDetailsRepository cardPurchaseDetailsRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private InternalAccountRepository internalAccountRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private CardDailySpendService cardDailySpendService;

    private final RequestHasher requestHasher = new RequestHasher();

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), Clock.systemUTC().getZone());

    private CardPaymentService cardPaymentService;

    @Captor
    private ArgumentCaptor<CardPurchaseDetails> cardPurchaseDetailsCaptor;

    @Captor
    private ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        cardPaymentService = new CardPaymentService(
                ledgerPostingService,
                fxRateService,
                idempotencyService,
                cardPurchaseDetailsRepository,
                cardRepository,
                accountRepository,
                internalAccountRepository,
                requestHasher,
                objectMapper,
                referenceGenerator,
                clock,
                cardDailySpendService
        );
    }

    @Test
    void makePurchaseDebitsFundingAccountAndCreditsMerchantAccountAndSavesCompletedPurchase() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Coffee"
        );

        String requestHash = hash(request);

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32), "8".repeat(32));

        given(cardPurchaseDetailsRepository.save(any(CardPurchaseDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        CardPurchaseResponse response = cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                "1".repeat(32),
                customer.getEmail()
        );

        verify(cardPurchaseDetailsRepository).save(cardPurchaseDetailsCaptor.capture());
        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        CardPurchaseDetails savedDetails = cardPurchaseDetailsCaptor.getValue();
        BankTransaction savedTransaction = savedDetails.getBankTransaction();
        List<LedgerEntry> entries = savedTransaction.getEntries();

        assertThat(entries)
                .hasSize(2);

        LedgerEntry sourceEntry = entries.get(0);
        LedgerEntry destinationEntry = entries.get(1);

        assertThat(savedDetails.getMerchantName())
                .isEqualTo(request.merchantName());

        assertThat(savedDetails.getCardReference())
                .isEqualTo("2".repeat(32));

        assertThat(savedDetails.getCardLastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(savedTransaction.getReference())
                .isEqualTo("6".repeat(32));

        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.CARD_PURCHASE);

        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(savedTransaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedTransaction.getRequestedCurrency())
                .isSameAs(request.currency());

        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(savedTransaction.getFxInfo())
                .isNull();

        assertThat(sourceEntry.getReference())
                .isEqualTo("7".repeat(32));

        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(sourceEntry.getBalanceAfter())
                .isEqualByComparingTo(fundingAccount.getBalance());

        assertThat(sourceEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(sourceEntry.getCurrency())
                .isSameAs(fundingAccount.getCurrency());

        assertThat(sourceEntry.getLedgerAccount())
                .isSameAs(fundingAccount.getLedgerAccount());

        assertThat(sourceEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(destinationEntry.getReference())
                .isEqualTo("8".repeat(32));

        assertThat(destinationEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(destinationEntry.getBalanceAfter())
                .isEqualByComparingTo(merchantAccount.getBalance());

        assertThat(destinationEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(destinationEntry.getCurrency())
                .isSameAs(merchantAccount.getCurrency());

        assertThat(destinationEntry.getLedgerAccount())
                .isSameAs(merchantAccount.getLedgerAccount());

        assertThat(destinationEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(response.transactionReference())
                .isEqualTo("6".repeat(32));

        assertThat(response.requestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.debitedAmount())
                .isEqualByComparingTo(sourceEntry.getAmount());

        assertThat(response.requestedCurrency())
                .isSameAs(request.currency());

        assertThat(response.debitedCurrency())
                .isSameAs(fundingAccount.getCurrency());

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(sourceEntry.getBalanceAfter());

        assertThat(response.merchantName())
                .isEqualTo(request.merchantName());

        assertThat(response.cardLastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(response.cardReference())
                .isEqualTo("2".repeat(32));

        assertThat(response.linkedAccountNumber())
                .isEqualTo("12".repeat(7));

        assertThat(response.createdAt())
                .isEqualTo(savedTransaction.getCreatedAt());

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("750.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("250.00");

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo("1".repeat(32));

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(requestHash);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);

        assertThat(idempotencyRecord.getResultReference())
                .isEqualTo(savedTransaction.getReference());

        verify(referenceGenerator, times(3)).generate();

        verifyNoInteractions(internalAccountRepository, fxRateService);
    }

    @Test
    void makePurchaseCreditsSettlementAccountWhenMerchantIsExternal() {
        Customer customer = createCustomer("customer@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        InternalAccount settlementAccount = createSettlementAccount("5".repeat(32), CurrencyCode.TRY, 2L);

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                null,
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                null
        );

        String requestHash = hash(request);

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY))
                .willReturn(Optional.of(settlementAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32), "8".repeat(32));

        given(cardPurchaseDetailsRepository.save(any(CardPurchaseDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        CardPurchaseResponse response = cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                "1".repeat(32),
                customer.getEmail()
        );

        verify(cardPurchaseDetailsRepository).save(cardPurchaseDetailsCaptor.capture());

        BankTransaction savedTransaction = cardPurchaseDetailsCaptor.getValue().getBankTransaction();

        LedgerEntry destinationEntry = savedTransaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(destinationEntry.getLedgerAccount())
                .isSameAs(settlementAccount.getLedgerAccount());

        assertThat(destinationEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedTransaction.getNote())
                .isNull();

        assertThat(savedTransaction.getFxInfo())
                .isNull();

        assertThat(response.debitedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.balanceAfter())
                .isEqualByComparingTo("750.00");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("750.00");

        assertThat(settlementAccount.getBalance())
                .isEqualByComparingTo("9750.00");

        verify(idempotencyService).save(any(IdempotencyRecord.class));
        verifyNoInteractions(accountRepository, fxRateService);
    }

    @Test
    void crossCurrencyPurchaseConvertsFundingAmountUsingLiveRatesWithoutLock() {
        Customer customer = createCustomer("customer@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("10000.00"));

        InternalAccount settlementAccount = createSettlementAccount("5".repeat(32), CurrencyCode.USD, 2L);

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                null,
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                "Online order"
        );

        String requestHash = hash(request);

        Map<CurrencyCode, BigDecimal> rates = Map.of(
                CurrencyCode.USD, BigDecimal.ONE,
                CurrencyCode.TRY, new BigDecimal("40.00"),
                CurrencyCode.EUR, new BigDecimal("0.85"),
                CurrencyCode.GBP, new BigDecimal("0.75")
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD))
                .willReturn(Optional.of(settlementAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32), "8".repeat(32));

        given(fxRateService.getRates(CurrencyCode.USD))
                .willReturn(rates);

        given(cardPurchaseDetailsRepository.save(any(CardPurchaseDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        CardPurchaseResponse response = cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                "1".repeat(32),
                customer.getEmail()
        );

        verify(cardPurchaseDetailsRepository).save(cardPurchaseDetailsCaptor.capture());

        BankTransaction savedTransaction = cardPurchaseDetailsCaptor.getValue().getBankTransaction();

        LedgerEntry sourceEntry = savedTransaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry destinationEntry = savedTransaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(savedTransaction.getFxInfo().getLockId())
                .isNull();

        assertThat(savedTransaction.getFxInfo().getRates())
                .extracting(FxRate::getCurrencyContext, FxRate::getCurrency, FxRate::getRate)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyContext.REQUEST, CurrencyCode.USD, BigDecimal.ONE),
                        tuple(CurrencyContext.SOURCE, CurrencyCode.TRY, new BigDecimal("40.00")),
                        tuple(CurrencyContext.DESTINATION, CurrencyCode.USD, BigDecimal.ONE)
                );

        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo("4000.00");

        assertThat(sourceEntry.getCurrency())
                .isSameAs(CurrencyCode.TRY);

        assertThat(destinationEntry.getAmount())
                .isEqualByComparingTo("100.00");

        assertThat(destinationEntry.getCurrency())
                .isSameAs(CurrencyCode.USD);

        assertThat(response.requestedAmount())
                .isEqualByComparingTo("100.00");

        assertThat(response.requestedCurrency())
                .isSameAs(CurrencyCode.USD);

        assertThat(response.debitedAmount())
                .isEqualByComparingTo("4000.00");

        assertThat(response.debitedCurrency())
                .isSameAs(CurrencyCode.TRY);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo("6000.00");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("6000.00");

        assertThat(settlementAccount.getBalance())
                .isEqualByComparingTo("9900.00");

        verify(fxRateService).getRates(CurrencyCode.USD);

        verifyNoMoreInteractions(fxRateService);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void crossCurrencyPurchaseConvertsBothSidesWhenMerchantAccountUsesAnotherCurrency() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.EUR, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("10000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                "Test"
        );

        String requestHash = hash(request);

        Map<CurrencyCode, BigDecimal> rates = Map.of(
                CurrencyCode.USD, BigDecimal.ONE,
                CurrencyCode.TRY, new BigDecimal("40.00"),
                CurrencyCode.EUR, new BigDecimal("0.85"),
                CurrencyCode.GBP, new BigDecimal("0.75")
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32), "8".repeat(32));

        given(fxRateService.getRates(CurrencyCode.USD))
                .willReturn(rates);

        given(cardPurchaseDetailsRepository.save(any(CardPurchaseDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                "1".repeat(32),
                customer.getEmail()
        );

        verify(cardPurchaseDetailsRepository).save(cardPurchaseDetailsCaptor.capture());

        BankTransaction savedTransaction = cardPurchaseDetailsCaptor.getValue().getBankTransaction();

        assertThat(savedTransaction.getFxInfo().getRates())
                .extracting(FxRate::getCurrencyContext, FxRate::getCurrency, FxRate::getRate)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyContext.REQUEST, CurrencyCode.USD, BigDecimal.ONE),
                        tuple(CurrencyContext.SOURCE, CurrencyCode.TRY, new BigDecimal("40.00")),
                        tuple(CurrencyContext.DESTINATION, CurrencyCode.EUR, new BigDecimal("0.85"))
                );

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("6000.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("85.00");

        verifyNoInteractions(internalAccountRepository);
    }

    @Test
    void purchaseUsesFxWhenOnlyMerchantAccountCurrencyDiffers() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.EUR, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("5000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("1000.00"),
                CurrencyCode.TRY,
                "Test"
        );

        String requestHash = hash(request);

        Map<CurrencyCode, BigDecimal> rates = Map.of(
                CurrencyCode.TRY, BigDecimal.ONE,
                CurrencyCode.USD, new BigDecimal("0.025"),
                CurrencyCode.EUR, new BigDecimal("0.02125"),
                CurrencyCode.GBP, new BigDecimal("0.01875")
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32), "8".repeat(32));

        given(fxRateService.getRates(CurrencyCode.TRY))
                .willReturn(rates);

        given(cardPurchaseDetailsRepository.save(any(CardPurchaseDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                "1".repeat(32),
                customer.getEmail()
        );

        verify(cardPurchaseDetailsRepository).save(cardPurchaseDetailsCaptor.capture());

        BankTransaction savedTransaction = cardPurchaseDetailsCaptor.getValue().getBankTransaction();

        assertThat(savedTransaction.getFxInfo())
                .isNotNull();

        assertThat(savedTransaction.getFxInfo().getRate(CurrencyContext.SOURCE))
                .isEqualByComparingTo(BigDecimal.ONE);

        assertThat(savedTransaction.getFxInfo().getRate(CurrencyContext.DESTINATION))
                .isEqualByComparingTo("0.02125");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("4000.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("21.25");
    }

    @Test
    void crossCurrencyPurchaseRoundsConvertedAmountHalfEvenToTwoDecimals() {
        Customer customer = createCustomer("customer@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("100.00"));

        InternalAccount settlementAccount = createSettlementAccount("5".repeat(32), CurrencyCode.USD, 2L);

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                null,
                new BigDecimal("0.10"),
                CurrencyCode.USD,
                "Test"
        );

        String requestHash = hash(request);

        Map<CurrencyCode, BigDecimal> rates = Map.of(
                CurrencyCode.USD, BigDecimal.ONE,
                CurrencyCode.TRY, new BigDecimal("41.25"),
                CurrencyCode.EUR, new BigDecimal("0.85"),
                CurrencyCode.GBP, new BigDecimal("0.75")
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD))
                .willReturn(Optional.of(settlementAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32), "8".repeat(32));

        given(fxRateService.getRates(CurrencyCode.USD))
                .willReturn(rates);

        given(cardPurchaseDetailsRepository.save(any(CardPurchaseDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        CardPurchaseResponse response = cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                "1".repeat(32),
                customer.getEmail()
        );

        assertThat(response.debitedAmount())
                .isEqualByComparingTo("4.12");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("95.88");
    }

    @Test
    void makePurchaseThrowsInvalidIdempotencyKeyWhenKeyInvalid() {
        String idempotencyKey = "1111111111111111!111111111111111";

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, idempotencyKey, "customer@example.com"))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Idempotency key is invalid");

        verifyNoInteractions(
                cardRepository,
                accountRepository,
                internalAccountRepository,
                idempotencyService,
                fxRateService,
                cardPurchaseDetailsRepository,
                referenceGenerator
        );
    }

    @Test
    void makePurchaseThrowsCardNotFoundExceptionWhenCardIsNotOwnedByCustomer() {
        Customer owner = createCustomer("owner@example.com");
        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, owner, 1L);
        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), "customer@example.com"))
                .isInstanceOf(CardNotFoundException.class)
                .hasMessage("Card not found");

        verifyNoInteractions(
                accountRepository,
                internalAccountRepository,
                idempotencyService,
                fxRateService,
                cardPurchaseDetailsRepository,
                referenceGenerator
        );
    }

    @Test
    void makePurchaseThrowsCardNotFoundExceptionWhenCardDoesNotExist() {
        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), "customer@example.com"))
                .isInstanceOf(CardNotFoundException.class)
                .hasMessage("Card not found");

        verifyNoInteractions(
                accountRepository,
                internalAccountRepository,
                idempotencyService,
                fxRateService,
                cardPurchaseDetailsRepository,
                referenceGenerator
        );
    }

    @Test
    void makePurchaseThrowsAccountNotFoundExceptionWhenMerchantAccountDoesNotExist() {
        Customer customer = createCustomer("customer@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Merchant's account not found");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        verifyNoInteractions(
                internalAccountRepository,
                idempotencyService,
                fxRateService,
                cardPurchaseDetailsRepository,
                referenceGenerator
        );
    }

    @Test
    void makePurchaseThrowsIllegalStateExceptionWhenSettlementAccountIsNotConfigured() {
        Customer customer = createCustomer("customer@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                null,
                new BigDecimal("250.00"),
                CurrencyCode.USD,
                "Test"
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Settlement account is not configured for USD");

        verifyNoInteractions(
                accountRepository,
                idempotencyService,
                fxRateService,
                cardPurchaseDetailsRepository,
                referenceGenerator
        );
    }

    @Test
    void makePurchaseThrowsInvalidCardPaymentExceptionWhenMerchantAccountIsFundingAccount() {
        Customer customer = createCustomer("customer@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "12".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("12".repeat(7)))
                .willReturn(Optional.of(fundingAccount));

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(InvalidCardPaymentException.class)
                .hasMessage("Merchant's account and the card's funding account cannot be same");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        verifyNoInteractions(
                internalAccountRepository,
                idempotencyService,
                fxRateService,
                cardPurchaseDetailsRepository,
                referenceGenerator
        );
    }

    @Test
    void makePurchaseThrowsCardExpiredExceptionWhenCardIsExpired() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        YearMonth lastMonth = YearMonth.now(clock).minusMonths(1);
        DebitCard debitCard = DebitCard.createNew("2".repeat(32), "1234", lastMonth, lastMonth, fundingAccount, TestCardLimits.generous());
        debitCard.activateCard(lastMonth);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        String requestHash = hash(request);

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(CardExpiredException.class)
                .hasMessage("Card is Expired");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(fxRateService, cardPurchaseDetailsRepository, referenceGenerator);
    }

    @ParameterizedTest
    @MethodSource("nonActiveCardStatusSource")
    void makePurchaseThrowsCardNotActiveExceptionWhenCardIsNotActive(CardStatus status) {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, status);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        String requestHash = hash(request);

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(CardNotActiveException.class)
                .hasMessage("Card must be active for purchase");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(fxRateService, cardPurchaseDetailsRepository, referenceGenerator);
    }

    @Test
    void makePurchaseThrowsInsufficientFundsWhenFundingBalanceIsLessThanAmount() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("100.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        String requestHash = hash(request);

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32));

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("100.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(referenceGenerator, times(2)).generate();
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(cardPurchaseDetailsRepository);
    }

    @Test
    void makePurchaseReturnsOriginalResponseWhenIdempotencyKeyAlreadyExists() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        String requestHash = hash(request);

        CardPurchaseDetails existingDetails = createCompletedPurchase(fundingAccount, merchantAccount, request);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                "1".repeat(32),
                requestHash,
                customer,
                "6".repeat(32)
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(cardPurchaseDetailsRepository.findByBankTransaction_Reference("6".repeat(32)))
                .willReturn(Optional.of(existingDetails));

        CardPurchaseResponse response = cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                "1".repeat(32),
                customer.getEmail()
        );

        assertThat(response.transactionReference())
                .isEqualTo("6".repeat(32));

        assertThat(response.requestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.debitedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.balanceAfter())
                .isEqualByComparingTo("750.00");

        assertThat(response.merchantName())
                .isEqualTo(request.merchantName());

        assertThat(response.cardReference())
                .isEqualTo("2".repeat(32));

        assertThat(response.linkedAccountNumber())
                .isEqualTo("12".repeat(7));

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("750.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("250.00");

        verify(cardPurchaseDetailsRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(referenceGenerator, fxRateService, cardDailySpendService);
    }

    @Test
    void makePurchaseReplaysOriginalResponseEvenWhenCardIsNoLongerActive() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.FROZEN);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        String requestHash = hash(request);

        CardPurchaseDetails existingDetails = createCompletedPurchase(fundingAccount, merchantAccount, request);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                "1".repeat(32),
                requestHash,
                customer,
                "6".repeat(32)
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(cardPurchaseDetailsRepository.findByBankTransaction_Reference("6".repeat(32)))
                .willReturn(Optional.of(existingDetails));

        CardPurchaseResponse response = cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                "1".repeat(32),
                customer.getEmail()
        );

        assertThat(response.transactionReference())
                .isEqualTo("6".repeat(32));

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("750.00");

        verify(cardPurchaseDetailsRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());

        verifyNoInteractions(referenceGenerator, fxRateService, cardDailySpendService);
    }

    @Test
    void makePurchaseThrowsIdempotencyResultNotFoundExceptionWhenReplayResultDoesNotExist() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        String requestHash = hash(request);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                "1".repeat(32),
                requestHash,
                customer,
                "6".repeat(32)
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(cardPurchaseDetailsRepository.findByBankTransaction_Reference("6".repeat(32)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(IdempotencyResultNotFoundException.class)
                .hasMessage("Idempotency result not found");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        verify(cardPurchaseDetailsRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(referenceGenerator, fxRateService, cardDailySpendService);
    }

    @Test
    void makePurchasePropagatesIdempotencyConflictWithoutMovingMoney() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(
                "98".repeat(7),
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                "Test"
        );

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), hash(request)))
                .willThrow(new IdempotencyConflictException("request hash mismatch"));

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessage("request hash mismatch");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(referenceGenerator, fxRateService, cardPurchaseDetailsRepository);
    }

    static Stream<Arguments> nonActiveCardStatusSource() {
        return Stream.of(
                Arguments.of(CardStatus.INACTIVE),
                Arguments.of(CardStatus.FROZEN),
                Arguments.of(CardStatus.BLOCKED),
                Arguments.of(CardStatus.CLOSED)
        );
    }

    @Test
    void makePurchaseRecordsDailySpendWithDebitedAmountForToday() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest("98".repeat(7), new BigDecimal("250"), CurrencyCode.TRY, "Coffee");

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), hash(request)))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32), "8".repeat(32));

        given(cardPurchaseDetailsRepository.save(any(CardPurchaseDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail());

        verify(cardDailySpendService).recordSpend(debitCard, LocalDate.of(2026, 9, 23), new BigDecimal("250.00"));
    }

    @Test
    void crossCurrencyPurchaseRecordsDailySpendInFundingCurrency() {
        Customer customer = createCustomer("customer@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("10000.00"));

        InternalAccount settlementAccount = createSettlementAccount("5".repeat(32), CurrencyCode.USD, 2L);

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest(null, new BigDecimal("100.00"), CurrencyCode.USD, "Online order");

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD))
                .willReturn(Optional.of(settlementAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), hash(request)))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32), "7".repeat(32), "8".repeat(32));

        given(fxRateService.getRates(CurrencyCode.USD))
                .willReturn(Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("40.00"),
                        CurrencyCode.EUR, new BigDecimal("0.85"),
                        CurrencyCode.GBP, new BigDecimal("0.75")
                ));

        given(cardPurchaseDetailsRepository.save(any(CardPurchaseDetails.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail());

        verify(cardDailySpendService).recordSpend(debitCard, LocalDate.of(2026, 9, 23), new BigDecimal("4000.00"));
    }

    @Test
    void makePurchaseMovesNoMoneyWhenCardLimitIsExceeded() {
        Customer customer = createCustomer("customer@example.com");
        Customer merchantCustomer = createCustomer("merchant@example.com");

        Account fundingAccount = createAccount("12".repeat(7), "3".repeat(32), CurrencyCode.TRY, customer, 1L);
        Account merchantAccount = createAccount("98".repeat(7), "4".repeat(32), CurrencyCode.TRY, merchantCustomer, 2L);
        fundingAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        DebitCard debitCard = createDebitCard(fundingAccount, CardStatus.ACTIVE);

        CardPurchaseRequest request = createPurchaseRequest("98".repeat(7), new BigDecimal("250.00"), CurrencyCode.TRY, "Coffee");

        given(cardRepository.findByCardReference("2".repeat(32)))
                .willReturn(Optional.of(debitCard));

        given(accountRepository.findByAccountNumber("98".repeat(7)))
                .willReturn(Optional.of(merchantAccount));

        given(idempotencyService.findAndValidateRecord("1".repeat(32), customer.getEmail(), hash(request)))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn("6".repeat(32));

        given(cardDailySpendService.recordSpend(debitCard, LocalDate.of(2026, 9, 23), new BigDecimal("250.00")))
                .willThrow(new CardLimitExceededException("Purchase exceeds the card's daily limit"));

        assertThatThrownBy(() -> cardPaymentService.makePurchase("2".repeat(32), request, "1".repeat(32), customer.getEmail()))
                .isInstanceOf(CardLimitExceededException.class)
                .hasMessage("Purchase exceeds the card's daily limit");

        assertThat(fundingAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(merchantAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(referenceGenerator, times(1)).generate();
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(cardPurchaseDetailsRepository);
    }

    private Customer createCustomer(String email) {
        return Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );
    }

    private Account createAccount(
            String accountNumber,
            String ledgerReference,
            CurrencyCode currency,
            Customer customer,
            Long ledgerAccountId
    ) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                ledgerReference,
                LedgerAccountType.LIABILITY,
                currency
        );

        ReflectionTestUtils.setField(ledgerAccount, "id", ledgerAccountId);

        return Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );
    }

    private InternalAccount createSettlementAccount(String ledgerReference, CurrencyCode currency, Long ledgerAccountId) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                ledgerReference,
                LedgerAccountType.ASSET,
                currency
        );

        ReflectionTestUtils.setField(ledgerAccount, "id", ledgerAccountId);

        ledgerAccount.debit(new BigDecimal("10000.00"));

        return InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                ledgerAccount
        );
    }

    private DebitCard createDebitCard(Account fundingAccount, CardStatus status) {
        YearMonth now = YearMonth.now(clock);

        DebitCard debitCard = DebitCard.createNew(
                "2".repeat(32),
                "1234",
                now.plusYears(5),
                now,
                fundingAccount,
                TestCardLimits.generous()
        );

        switch (status) {
            case INACTIVE -> {
            }
            case ACTIVE -> debitCard.activateCard(now);
            case FROZEN -> {
                debitCard.activateCard(now);
                debitCard.freezeCard(now);
            }
            case BLOCKED -> debitCard.blockCard(now);
            case CLOSED -> debitCard.closeCard();
        }

        return debitCard;
    }

    private CardPurchaseRequest createPurchaseRequest(
            String merchantAccountNumber,
            BigDecimal amount,
            CurrencyCode currency,
            String note
    ) {
        return new CardPurchaseRequest(
                merchantAccountNumber,
                currency,
                amount,
                note,
                "Test merchant"
        );
    }

    private CardPurchaseDetails createCompletedPurchase(
            Account fundingAccount,
            Account merchantAccount,
            CardPurchaseRequest request
    ) {
        BankTransaction transaction = BankTransaction.createNew(
                request.amount(),
                request.currency(),
                TransactionType.CARD_PURCHASE,
                "6".repeat(32),
                request.note()
        );

        BigDecimal fundingBalanceAfter = fundingAccount.getLedgerAccount().debit(request.amount());

        transaction.addEntry(
                "7".repeat(32),
                request.amount(),
                fundingBalanceAfter,
                EntryDirection.DEBIT,
                fundingAccount.getLedgerAccount()
        );

        BigDecimal merchantBalanceAfter = merchantAccount.getLedgerAccount().credit(request.amount());

        transaction.addEntry(
                "8".repeat(32),
                request.amount(),
                merchantBalanceAfter,
                EntryDirection.CREDIT,
                merchantAccount.getLedgerAccount()
        );

        transaction.complete();

        return CardPurchaseDetails.createNew(
                transaction,
                request.merchantName(),
                "2".repeat(32),
                "1234"
        );
    }

    private String hash(CardPurchaseRequest request) {
        return requestHasher.hashRequest(objectMapper.writeValueAsString(
                new CardPurchaseCanonicalRequest(
                        TransactionType.CARD_PURCHASE,
                        "2".repeat(32),
                        request.merchantAccountNumber(),
                        request.merchantName(),
                        request.amount(),
                        request.currency(),
                        request.note()
                )
        ));
    }
}
