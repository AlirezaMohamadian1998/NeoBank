package com.neobank.neobank.card.debit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.CardExpiredException;
import com.neobank.neobank.card.CardStatus;
import com.neobank.neobank.card.InvalidCardStatusTransitionException;
import com.neobank.neobank.card.UnsupportedCardStatusException;
import com.neobank.neobank.card.debit.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.debit.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.card.issuing.CardIssuer;
import com.neobank.neobank.card.issuing.IssuedCard;
import com.neobank.neobank.card.limit.CardDailySpendService;
import com.neobank.neobank.card.limit.CardLimitProperties;
import com.neobank.neobank.card.limit.CardSpendingLimits;
import com.neobank.neobank.card.limit.CardSpendingLimitsNotChangeableException;
import com.neobank.neobank.card.limit.InvalidCardLimitException;
import com.neobank.neobank.card.limit.TestCardLimitProperties;
import com.neobank.neobank.card.limit.dto.CardSpendingLimitsResponse;
import com.neobank.neobank.card.limit.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.idempotency.*;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DebitCardServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DebitCardRepository debitCardRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private RequestHasher requestHasher;

    @Mock
    private CardIssuer cardIssuer;

    @Mock
    private CardDailySpendService cardDailySpendService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), Clock.systemUTC().getZone());

    private final CardLimitProperties cardLimitProperties = TestCardLimitProperties.standard();

    private DebitCardService debitCardService;

    @BeforeEach
    void setUp() {
        debitCardService = new DebitCardService(
                debitCardRepository,
                accountRepository,
                idempotencyService,
                referenceGenerator,
                requestHasher,
                clock,
                cardIssuer,
                cardLimitProperties,
                cardDailySpendService
        );
    }

    @Test
    void issueDebitCardIssuesAndPersistsNewCardWithIdempotencyRecord() {
        ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        ArgumentCaptor<DebitCard> debitCardCaptor = ArgumentCaptor.forClass(DebitCard.class);

        YearMonth now = YearMonth.now(clock);

        String idempotencyKey = "11111111111111111111111111111111";
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String cardReference = "2".repeat(32);

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        IssuedCard issuedCard = new IssuedCard(
                "1234",
                now.plusYears(5)
        );

        String requestHash = "a".repeat(64);

        given(requestHasher.hashRequest("ISSUE_DEBIT_CARD|%s|%s".formatted(email, accountNumber)))
                .willReturn(requestHash);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(cardIssuer.issue(now))
                .willReturn(issuedCard);

        given(referenceGenerator.generate())
                .willReturn(cardReference);

        DebitCardIssueResponse response = debitCardService.issueDebitCard(email, accountNumber, idempotencyKey);

        verify(debitCardRepository).save(debitCardCaptor.capture());
        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        DebitCard debitCard = debitCardCaptor.getValue();
        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(response.cardReference())
                .isEqualTo(debitCard.getCardReference());

        assertThat(response.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(response.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

        assertThat(response.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(debitCard.getFundingAccount())
                .isSameAs(account);

        assertThat(debitCard.getCardReference())
                .isEqualTo(cardReference);

        assertThat(debitCard.getLastFourDigits())
                .isEqualTo(issuedCard.lastFourDigits());

        assertThat(debitCard.getExpirationYearMonth())
                .isEqualTo(issuedCard.expirationYearMonth());

        assertThat(debitCard.getStatus())
                .isSameAs(CardStatus.INACTIVE);

        assertThat(debitCard.getSpendingLimits())
                .isEqualTo(CardSpendingLimits.createNew(new BigDecimal("25000.00"), new BigDecimal("50000.00")));

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(requestHash);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);

        assertThat(idempotencyRecord.getResultReference())
                .isEqualTo(cardReference);

        verify(debitCardRepository, times(1)).save(any());
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(referenceGenerator, times(1)).generate();
        verify(requestHasher).hashRequest("ISSUE_DEBIT_CARD|%s|%s".formatted(email, accountNumber));
        verify(cardIssuer).issue(now);
    }

    @Test
    void issueDebitCardRejectsInvalidIdempotencyKeyBeforeCallingDependencies() {
        String invalidIdempotencyKey = "11111111111111zzz111111111111111";
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        assertThatThrownBy(() -> debitCardService.issueDebitCard(email, accountNumber, invalidIdempotencyKey))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(accountRepository, debitCardRepository, idempotencyService, referenceGenerator, requestHasher, cardIssuer);
    }

    @Test
    void issueDebitCardThrowsAccountNotFoundExceptionWhenFundingAccountDoesNotExist() {
        String idempotencyKey = "11111111111111111111111111111111";
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> debitCardService.issueDebitCard(email, accountNumber, idempotencyKey))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verifyNoInteractions(debitCardRepository, idempotencyService, referenceGenerator, requestHasher, cardIssuer);

    }

    @Test
    void issueDebitCardReturnsExistingCardForIdempotentReplay() {
        String idempotencyKey = "11111111111111111111111111111111";
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        String requestHash = "a".repeat(64);

        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = DebitCard.createNew(
                "2".repeat(32),
                "1234",
                now.plusYears(5),
                now,
                account,
                TestCardLimits.generous()
        );

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                debitCard.getCardReference()
        );

        given(requestHasher.hashRequest("ISSUE_DEBIT_CARD|%s|%s".formatted(email, accountNumber)))
                .willReturn(requestHash);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(debitCardRepository.findByCardReference(debitCard.getCardReference()))
                .willReturn(Optional.of(debitCard));

        DebitCardIssueResponse response = debitCardService.issueDebitCard(email, accountNumber, idempotencyKey);

        assertThat(response.cardReference())
                .isEqualTo(debitCard.getCardReference());

        assertThat(response.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(response.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

        assertThat(response.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        verifyNoInteractions(cardIssuer, referenceGenerator);

        verify(debitCardRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
    }

    @Test
    void issueDebitCardThrowsIdempotencyResultNotFoundExceptionWhenReplayResultDoesNotExist() {
        String idempotencyKey = "11111111111111111111111111111111";
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        String requestHash = "a".repeat(64);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                "2".repeat(32)
        );

        given(requestHasher.hashRequest("ISSUE_DEBIT_CARD|%s|%s".formatted(email, accountNumber)))
                .willReturn(requestHash);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        given(debitCardRepository.findByCardReference(anyString()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> debitCardService.issueDebitCard(email, accountNumber, idempotencyKey))
                .isInstanceOf(IdempotencyResultNotFoundException.class)
                .hasMessage("Couldn't find debit card with the given reference.");

        verifyNoInteractions(cardIssuer, referenceGenerator);

        verify(debitCardRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
    }

    @Test
    void getDebitCardReturnsMappedOwnedDebitCard() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        Customer customer = debitCard.getFundingAccount().getCustomer();

        debitCard.activateCard(now);

        given(debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), customer.getEmail()))
                .willReturn(Optional.of(debitCard));

        given(cardDailySpendService.getAmountSpent(debitCard, LocalDate.of(2026, 9, 23)))
                .willReturn(new BigDecimal("40.00"));

        DebitCardRetrieveResponse response = debitCardService.getDebitCard(debitCard.getCardReference(), customer.getEmail());

        assertThat(response.spendingLimits())
                .isEqualTo(new CardSpendingLimitsResponse(
                        new BigDecimal("1000000.00"),
                        new BigDecimal("5000000.00"),
                        new BigDecimal("40.00"),
                        CurrencyCode.TRY
                ));

        assertThat(response.cardReference())
                .isEqualTo(debitCard.getCardReference());

        assertThat(response.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(response.status())
                .isSameAs(CardStatus.ACTIVE);

        assertThat(response.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(response.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

        verify(debitCardRepository, never()).save(any());
        verify(debitCardRepository, times(1)).findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), customer.getEmail());
    }

    @Test
    void getDebitCardThrowsDebitCardNotFoundExceptionWhenOwnedLookupReturnsEmpty() {
        String cardReference = "1".repeat(32);

        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        given(debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(cardReference, customer.getEmail()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> debitCardService.getDebitCard(cardReference, customer.getEmail()))
                .isInstanceOf(DebitCardNotFoundException.class)
                .hasMessage("Debit card not found");

        verify(debitCardRepository, never()).save(any());
        verify(debitCardRepository, times(1)).findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(cardReference, customer.getEmail());
    }

    @Test
    void getDebitCardsMapsContentAndPreservesPageMetadata() {
        YearMonth now = YearMonth.now(clock);

        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount firstLedgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account firstAccount = Account.createNew(
                "12345678900321",
                "Private Account",
                AccountType.CURRENT,
                customer,
                firstLedgerAccount
        );

        LedgerAccount secondLedgerAccount = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a33",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account secondAccount = Account.createNew(
                "12300123456789",
                "Private Account NO 2",
                AccountType.CURRENT,
                customer,
                secondLedgerAccount
        );

        DebitCard firstDebitCard = DebitCard.createNew(
                "1".repeat(32),
                "1234",
                now.plusYears(5),
                now,
                firstAccount,
                TestCardLimits.generous()
        );

        firstDebitCard.activateCard(now);

        DebitCard secondDebitCard = DebitCard.createNew(
                "2".repeat(32),
                "4321",
                now.plusYears(5),
                now,
                secondAccount,
                TestCardLimits.generous()
        );

        secondDebitCard.activateCard(now);
        secondDebitCard.freezeCard(now);

        ReflectionTestUtils.setField(firstDebitCard, "id", 1L);
        ReflectionTestUtils.setField(secondDebitCard, "id", 2L);

        Page<DebitCard> debitCardPage = new PageImpl<>(
                List.of(firstDebitCard, secondDebitCard),
                PageRequest.of(0, 10),
                2
        );

        given(debitCardRepository.findAllByFundingAccount_Customer_EmailIgnoreCaseOrderByCreatedAtDescIdDesc(customer.getEmail(), debitCardPage.getPageable()))
                .willReturn(debitCardPage);

        given(cardDailySpendService.getAmountSpentPerCard(debitCardPage.getContent(), LocalDate.of(2026, 9, 23)))
                .willReturn(Map.of(1L, new BigDecimal("40.00"), 2L, new BigDecimal("0.00")));

        Page<DebitCardRetrieveResponse> response = debitCardService.getDebitCards(customer.getEmail(), debitCardPage.getPageable());

        assertThat(response.getPageable())
                .isEqualTo(debitCardPage.getPageable());

        assertThat(response.getTotalElements())
                .isEqualTo(2);

        assertThat(response.getTotalPages())
                .isEqualTo(1);

        assertThat(response.getNumber())
                .isZero();

        assertThat(response.getSize())
                .isEqualTo(10);

        assertThat(response.getContent())
                .containsExactly(
                        new DebitCardRetrieveResponse(
                                firstDebitCard.getCardReference(),
                                firstDebitCard.getLastFourDigits(),
                                CardStatus.ACTIVE,
                                firstDebitCard.getExpirationYearMonth(),
                                firstAccount.getAccountNumber(),
                                new CardSpendingLimitsResponse(
                                        new BigDecimal("1000000.00"),
                                        new BigDecimal("5000000.00"),
                                        new BigDecimal("40.00"),
                                        CurrencyCode.TRY
                                )
                        ),
                        new DebitCardRetrieveResponse(
                                secondDebitCard.getCardReference(),
                                secondDebitCard.getLastFourDigits(),
                                CardStatus.FROZEN,
                                secondDebitCard.getExpirationYearMonth(),
                                secondAccount.getAccountNumber(),
                                new CardSpendingLimitsResponse(
                                        new BigDecimal("1000000.00"),
                                        new BigDecimal("5000000.00"),
                                        new BigDecimal("0.00"),
                                        CurrencyCode.TRY
                                )
                        )
                );

        verify(debitCardRepository, times(1))
                .findAllByFundingAccount_Customer_EmailIgnoreCaseOrderByCreatedAtDescIdDesc(customer.getEmail(), debitCardPage.getPageable());
    }

    @Test
    void changeCardStatusActivatesInactiveCardAndReturnsUpdatedResponse() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        given(debitCardRepository
                .findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        given(debitCardRepository.save(debitCard))
                .willAnswer(invocation -> invocation.getArgument(0));

        DebitCardRetrieveResponse response =
                debitCardService.changeCardStatus(debitCard.getCardReference(), email, CardStatus.ACTIVE);

        assertThat(response.cardReference())
                .isEqualTo(debitCard.getCardReference());

        assertThat(response.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(response.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

        assertThat(response.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(response.status())
                .isSameAs(CardStatus.ACTIVE);

        assertThat(debitCard.getStatus())
                .isSameAs(CardStatus.ACTIVE);

        verify(debitCardRepository).save(same(debitCard));
    }

    @Test
    void changeCardStatusUnfreezesFrozenCardAndReturnsUpdatedResponse() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        debitCard.activateCard(now);
        debitCard.freezeCard(now);

        given(debitCardRepository
                .findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        given(debitCardRepository.save(debitCard))
                .willAnswer(invocation -> invocation.getArgument(0));

        DebitCardRetrieveResponse response =
                debitCardService.changeCardStatus(debitCard.getCardReference(), email, CardStatus.ACTIVE);

        assertThat(response.cardReference())
                .isEqualTo(debitCard.getCardReference());

        assertThat(response.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(response.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

        assertThat(response.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(response.status())
                .isSameAs(CardStatus.ACTIVE);

        assertThat(debitCard.getStatus())
                .isSameAs(CardStatus.ACTIVE);

        verify(debitCardRepository).save(same(debitCard));
    }

    @ParameterizedTest
    @MethodSource("validTransitionSource")
    void changeCardStatusAppliesOtherValidTransitions(CardStatus status) {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        debitCard.activateCard(now);

        given(debitCardRepository
                .findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        given(debitCardRepository.save(debitCard))
                .willAnswer(invocation -> invocation.getArgument(0));

        DebitCardRetrieveResponse response =
                debitCardService.changeCardStatus(debitCard.getCardReference(), email, status);

        assertThat(response.cardReference())
                .isEqualTo(debitCard.getCardReference());

        assertThat(response.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(response.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

        assertThat(response.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(response.status())
                .isSameAs(status);

        assertThat(debitCard.getStatus())
                .isSameAs(status);

        verify(debitCardRepository).save(same(debitCard));
    }

    @Test
    void changeCardStatusThrowsNotFoundWhenOwnedLookupReturnsEmpty() {
        String cardReference = "1".repeat(32);

        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        given(debitCardRepository
                .findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(cardReference, customer.getEmail()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() ->
                debitCardService.changeCardStatus(cardReference, customer.getEmail(), CardStatus.ACTIVE))
                .isInstanceOf(DebitCardNotFoundException.class)
                .hasMessage("Debit card not found");

        verify(debitCardRepository)
                .findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(cardReference, customer.getEmail());

        verify(debitCardRepository, never()).save(any());
    }

    @Test
    void changeCardStatusRejectsInactiveTargetWithoutSaving() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        given(debitCardRepository
                .findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        assertThatThrownBy(() ->
                debitCardService.changeCardStatus(debitCard.getCardReference(), email, CardStatus.INACTIVE))
                .isInstanceOf(UnsupportedCardStatusException.class)
                .hasMessage("Cards cannot be changed back to inactive");

        assertThat(debitCard.getStatus())
                .isSameAs(CardStatus.INACTIVE);

        verify(debitCardRepository, never()).save(any());
    }

    @Test
    void changeCardStatusRejectsInvalidTransitionWithoutSaving() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        given(debitCardRepository
                .findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        assertThatThrownBy(() ->
                debitCardService.changeCardStatus(debitCard.getCardReference(), email, CardStatus.FROZEN))
                .isInstanceOf(InvalidCardStatusTransitionException.class)
                .hasMessage("Only active cards can be frozen");

        assertThat(debitCard.getStatus())
                .isSameAs(CardStatus.INACTIVE);

        verify(debitCardRepository, never()).save(any());
    }

    @Test
    void changeCardStatusRejectsExpiredCardsWithoutSaving() {
        YearMonth expirationYearMonth = YearMonth.now(clock).minusMonths(1);
        DebitCard debitCard = createDebitCard(expirationYearMonth, expirationYearMonth);
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        given(debitCardRepository
                .findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        assertThatThrownBy(() ->
                debitCardService.changeCardStatus(debitCard.getCardReference(), email, CardStatus.ACTIVE))
                .isInstanceOf(CardExpiredException.class)
                .hasMessage("Expired cards cannot be activated");

        assertThat(debitCard.getStatus())
                .isSameAs(CardStatus.INACTIVE);

        verify(debitCardRepository, never()).save(any());
    }

    static Stream<Arguments> validTransitionSource() {
        return Stream.of(
                Arguments.of(CardStatus.FROZEN),
                Arguments.of(CardStatus.BLOCKED),
                Arguments.of(CardStatus.CLOSED)
        );
    }

    @Test
    void updateSpendingLimitsChangesLimitsWithinMaximumAndReturnsUpdatedCard() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        given(debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));
        given(debitCardRepository.save(debitCard))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(cardDailySpendService.getAmountSpent(debitCard, LocalDate.of(2026, 9, 23)))
                .willReturn(new BigDecimal("0.00"));

        DebitCardRetrieveResponse response = debitCardService.updateSpendingLimits(
                debitCard.getCardReference(),
                email,
                new CardSpendingLimitsUpdateRequest(new BigDecimal("250000.00"), new BigDecimal("500000.00"))
        );

        assertThat(debitCard.getSpendingLimits())
                .isEqualTo(CardSpendingLimits.createNew(new BigDecimal("250000.00"), new BigDecimal("500000.00")));

        assertThat(response.spendingLimits())
                .isEqualTo(new CardSpendingLimitsResponse(
                        new BigDecimal("250000.00"),
                        new BigDecimal("500000.00"),
                        new BigDecimal("0.00"),
                        CurrencyCode.TRY
                ));

        verify(debitCardRepository).save(debitCard);
    }

    @Test
    void updateSpendingLimitsRejectsLimitsAboveCurrencyMaximumWithoutSaving() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        String email = debitCard.getFundingAccount().getCustomer().getEmail();
        CardSpendingLimits original = debitCard.getSpendingLimits();

        given(debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        assertThatThrownBy(() -> debitCardService.updateSpendingLimits(
                debitCard.getCardReference(),
                email,
                new CardSpendingLimitsUpdateRequest(new BigDecimal("250000.01"), new BigDecimal("500000.00"))
        ))
                .isInstanceOf(InvalidCardLimitException.class)
                .hasMessage("Per-transaction limit must not exceed 250000.00");

        assertThat(debitCard.getSpendingLimits()).isEqualTo(original);
        verify(debitCardRepository, never()).save(any());
    }

    @Test
    void updateSpendingLimitsRejectsPerTransactionAboveDailyWithoutSaving() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        given(debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        assertThatThrownBy(() -> debitCardService.updateSpendingLimits(
                debitCard.getCardReference(),
                email,
                new CardSpendingLimitsUpdateRequest(new BigDecimal("300.00"), new BigDecimal("200.00"))
        ))
                .isInstanceOf(InvalidCardLimitException.class)
                .hasMessage("Per-transaction limit must not exceed the daily limit");

        verify(debitCardRepository, never()).save(any());
    }

    @Test
    void updateSpendingLimitsRejectsClosedCardWithoutSaving() {
        YearMonth now = YearMonth.now(clock);
        DebitCard debitCard = createDebitCard(now, now.plusYears(5));
        debitCard.closeCard();
        String email = debitCard.getFundingAccount().getCustomer().getEmail();

        given(debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase(debitCard.getCardReference(), email))
                .willReturn(Optional.of(debitCard));

        assertThatThrownBy(() -> debitCardService.updateSpendingLimits(
                debitCard.getCardReference(),
                email,
                new CardSpendingLimitsUpdateRequest(new BigDecimal("100.00"), new BigDecimal("200.00"))
        ))
                .isInstanceOf(CardSpendingLimitsNotChangeableException.class);

        verify(debitCardRepository, never()).save(any());
    }

    @Test
    void updateSpendingLimitsThrowsNotFoundWhenOwnedLookupReturnsEmpty() {
        given(debitCardRepository.findByCardReferenceAndFundingAccount_Customer_EmailIgnoreCase("2".repeat(32), "customer@example.com"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> debitCardService.updateSpendingLimits(
                "2".repeat(32),
                "customer@example.com",
                new CardSpendingLimitsUpdateRequest(new BigDecimal("100.00"), new BigDecimal("200.00"))
        ))
                .isInstanceOf(DebitCardNotFoundException.class);

        verify(debitCardRepository, never()).save(any());
    }

    private DebitCard createDebitCard(YearMonth creationYearMonth, YearMonth expirationYearMonth) {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                "12345678900321",
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        return DebitCard.createNew(
                "1".repeat(32),
                "1234",
                expirationYearMonth,
                creationYearMonth,
                account,
                TestCardLimits.generous()
        );
    }

}
