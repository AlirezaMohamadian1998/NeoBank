package com.neobank.neobank.card.credit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.credit.dto.CreditCardRepaymentRequest;
import com.neobank.neobank.card.credit.dto.CreditCardRepaymentResponse;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CreditCardBalanceServiceRepaymentTest {

    private static final String EMAIL = "customer@example.com";

    private static final String CARD_REFERENCE = "2".repeat(32);

    private static final String SOURCE_ACCOUNT_NUMBER = "12345678900987";

    private static final String IDEMPOTENCY_KEY = "1".repeat(32);

    private static final String LOCK_ID = "3f2b8c1e-7a4d-4e9b-9c2f-1d5e6a7b8c9d";

    @Mock
    private CreditCardRepository creditCardRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private FxRateService fxRateService;

    private final RequestHasher requestHasher = new RequestHasher();

    private final Customer customer = Customer.createNew(EMAIL, "{bcrypt}encoded-password", "Ada Lovelace");

    private CreditCardBalanceService creditCardBalanceService;

    @BeforeEach
    void setUp() {
        creditCardBalanceService = new CreditCardBalanceService(
                creditCardRepository,
                accountRepository,
                bankTransactionRepository,
                new LedgerPostingService(referenceGenerator),
                idempotencyService,
                requestHasher,
                referenceGenerator,
                fxRateService
        );
    }

    @Test
    void repayAllowsRepayingTheFullBalanceOfAFrozenCard() {
        CreditCard creditCard = createCreditCard(CurrencyCode.TRY, "600.00");
        creditCard.freezeCard(YearMonth.of(2026, 9));
        Account sourceAccount = createSourceAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");

        givenCardAndSourceAccount(creditCard, sourceAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());
        given(referenceGenerator.generate()).willReturn("a".repeat(32), "b".repeat(32), "c".repeat(32));
        given(bankTransactionRepository.save(any(BankTransaction.class))).willAnswer(invocation -> invocation.getArgument(0));

        CreditCardRepaymentResponse response =
                creditCardBalanceService.repay(CARD_REFERENCE, request("600.00"), IDEMPOTENCY_KEY, EMAIL);

        assertThat(response.creditCardBalanceAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    void repayRejectsInvalidIdempotencyKey() {
        assertThatThrownBy(() -> creditCardBalanceService.repay(CARD_REFERENCE, request("250.00"), "invalid", EMAIL))
                .isInstanceOf(InvalidIdempotencyKeyException.class);

        verifyNoInteractions(creditCardRepository, accountRepository, idempotencyService);
    }

    @Test
    void repayRejectsCardOfAnotherCustomer() {
        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(CARD_REFERENCE, EMAIL))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.repay(CARD_REFERENCE, request("250.00"), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(CreditCardNotFoundException.class)
                .hasMessage("Credit card not found");
    }

    @Test
    void repayRejectsSourceAccountOfAnotherCustomer() {
        CreditCard creditCard = createCreditCard(CurrencyCode.TRY, "600.00");

        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(CARD_REFERENCE, EMAIL))
                .willReturn(Optional.of(creditCard));
        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(SOURCE_ACCOUNT_NUMBER, EMAIL))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.repay(CARD_REFERENCE, request("250.00"), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void repayRejectsSavingsAccount() {
        CreditCard creditCard = createCreditCard(CurrencyCode.TRY, "600.00");
        Account sourceAccount = createSourceAccount(AccountType.SAVINGS, CurrencyCode.TRY, "1000.00");

        givenCardAndSourceAccount(creditCard, sourceAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> creditCardBalanceService.repay(CARD_REFERENCE, request("250.00"), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(InvalidCreditCardRepaymentException.class)
                .hasMessage("Credit cards can only be repaid from a current account");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void repayFromAnotherCurrencyDebitsConvertedAmountUsingTheLockedRate() {
        CreditCard creditCard = createCreditCard(CurrencyCode.USD, "600.00");
        Account sourceAccount = createSourceAccount(AccountType.CURRENT, CurrencyCode.TRY, "20000.00");

        givenCardAndSourceAccount(creditCard, sourceAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());
        given(fxRateService.getCachedRate(EMAIL, LOCK_ID)).willReturn(rateLock(CurrencyCode.USD));
        given(referenceGenerator.generate()).willReturn("a".repeat(32), "b".repeat(32), "c".repeat(32));
        given(bankTransactionRepository.save(any(BankTransaction.class))).willAnswer(invocation -> invocation.getArgument(0));

        CreditCardRepaymentResponse response =
                creditCardBalanceService.repay(CARD_REFERENCE, request("250.00", LOCK_ID), IDEMPOTENCY_KEY, EMAIL);

        assertThat(response.debitedAmount()).isEqualByComparingTo("8545.75");
        assertThat(response.debitedCurrency()).isEqualTo(CurrencyCode.TRY);
        assertThat(response.creditedAmount()).isEqualByComparingTo("250.00");
        assertThat(response.creditedCurrency()).isEqualTo(CurrencyCode.USD);
        assertThat(response.sourceAccountBalanceAfter()).isEqualByComparingTo("11454.25");
        assertThat(response.creditCardBalanceAfter()).isEqualByComparingTo("350.00");
    }

    @Test
    void repayInSameCurrencyRejectsALock() {
        CreditCard creditCard = createCreditCard(CurrencyCode.TRY, "600.00");
        Account sourceAccount = createSourceAccount(AccountType.CURRENT, CurrencyCode.TRY, "1000.00");

        givenCardAndSourceAccount(creditCard, sourceAccount);

        assertThatThrownBy(() -> creditCardBalanceService.repay(CARD_REFERENCE, request("250.00", LOCK_ID), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(InvalidCreditCardRepaymentException.class)
                .hasMessage("Lock ID is not required for same currency repayment");

        verifyNoInteractions(idempotencyService, fxRateService);
    }

    @Test
    void repayRejectsLockWithAnotherBaseCurrency() {
        CreditCard creditCard = createCreditCard(CurrencyCode.USD, "600.00");
        Account sourceAccount = createSourceAccount(AccountType.CURRENT, CurrencyCode.TRY, "20000.00");

        givenCardAndSourceAccount(creditCard, sourceAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());
        given(fxRateService.getCachedRate(EMAIL, LOCK_ID)).willReturn(rateLock(CurrencyCode.TRY));
        given(referenceGenerator.generate()).willReturn("a".repeat(32));

        assertThatThrownBy(() -> creditCardBalanceService.repay(CARD_REFERENCE, request("250.00", LOCK_ID), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("The base currency in the cached fx rate must be the same as the credit card currency");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("20000.00");
    }

    @Test
    void repayRejectsAmountOverTheSourceAccountBalance() {
        CreditCard creditCard = createCreditCard(CurrencyCode.TRY, "600.00");
        Account sourceAccount = createSourceAccount(AccountType.CURRENT, CurrencyCode.TRY, "100.00");

        givenCardAndSourceAccount(creditCard, sourceAccount);
        given(idempotencyService.findAndValidateRecord(eq(IDEMPOTENCY_KEY), eq(EMAIL), any())).willReturn(Optional.empty());
        given(referenceGenerator.generate()).willReturn("a".repeat(32), "b".repeat(32));

        assertThatThrownBy(() -> creditCardBalanceService.repay(CARD_REFERENCE, request("250.00"), IDEMPOTENCY_KEY, EMAIL))
                .isInstanceOf(InsufficientFundsException.class);

        verify(bankTransactionRepository, never()).save(any());
    }

    private void givenCardAndSourceAccount(CreditCard creditCard, Account sourceAccount) {
        given(creditCardRepository.findByCardReferenceAndCreditAccount_Customer_EmailIgnoreCase(CARD_REFERENCE, EMAIL))
                .willReturn(Optional.of(creditCard));
        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(SOURCE_ACCOUNT_NUMBER, EMAIL))
                .willReturn(Optional.of(sourceAccount));
    }

    private CreditCard createCreditCard(CurrencyCode currency, String owed) {
        CreditAccount creditAccount = CreditAccount.createNew(
                "55".repeat(7),
                "5".repeat(32),
                customer,
                currency,
                new BigDecimal("1000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        );

        creditAccount.getLedgerAccount().debit(new BigDecimal(owed));

        YearMonth now = YearMonth.of(2026, 9);

        CreditCard creditCard = CreditCard.createNew(
                CARD_REFERENCE,
                "5678",
                now.plusYears(5),
                now,
                creditAccount,
                TestCardLimits.generous()
        );

        creditCard.activateCard(now);

        return creditCard;
    }

    private Account createSourceAccount(AccountType type, CurrencyCode currency, String balance) {

        Account account = Account.createNew(SOURCE_ACCOUNT_NUMBER, "7".repeat(32), "Main", type, customer, currency);
        account.getLedgerAccount().credit(new BigDecimal(balance));

        return account;
    }

    private static CreditCardRepaymentRequest request(String amount) {
        return request(amount, null);
    }

    private static CreditCardRepaymentRequest request(String amount, String lockId) {
        return new CreditCardRepaymentRequest(SOURCE_ACCOUNT_NUMBER, new BigDecimal(amount), lockId);
    }

    private static FxRateLockResponse rateLock(CurrencyCode baseCurrency) {
        return new FxRateLockResponse(
                LOCK_ID,
                Instant.parse("2026-09-23T12:05:00Z"),
                baseCurrency,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("34.18300000")
                )
        );
    }
}
