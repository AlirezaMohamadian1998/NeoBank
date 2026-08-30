package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxRateLockUnavailableException;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private InternalAccountRepository internalAccountRepository;

    @Mock
    private FxRateService fxRateService;

    private final RequestHasher requestHasher = new RequestHasher();

    private DepositService depositService;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @Captor
    private ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        depositService = new DepositService(
                accountRepository,
                bankTransactionRepository,
                referenceGenerator,
                ledgerPostingService,
                requestHasher,
                idempotencyService,
                internalAccountRepository,
                fxRateService
        );
    }

    @Test
    void depositCreditsOwnedAccountAndSavesCompletedTransactionWithEntry() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String customerEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String internalEntryReference = "8f3c8a21d9e64b5fa2c17e9084bd6a32";
        String idempotencyKey = "11111111111111111111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                null
        );

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

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        String requestHash = requestHasher.hashRequest(String.join(
                        "|",
                        TransactionType.DEPOSIT.name(),
                        accountNumber,
                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        request.requestedCurrency().name(),
                        account.getCurrency().name(),
                        "",
                        request.note().trim()
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(referenceGenerator.generate())
                .willReturn(transactionReference, internalEntryReference, customerEntryReference);

        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, account.getCurrency()))
                .willReturn(Optional.of(internalAccount));

        DepositResponse response = depositService.deposit(request, accountNumber, email, idempotencyKey);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();

        assertThat(savedTransaction.getEntries())
                .hasSize(2);

        assertThat(savedTransaction.getEntries())
                .extracting(LedgerEntry::getReference)
                .doesNotHaveDuplicates();

        LedgerEntry savedCustomerEntry = savedTransaction
                .getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry savedInternalEntry = savedTransaction
                .getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(savedTransaction.getReference())
                .isEqualTo(transactionReference);

        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.DEPOSIT);

        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(savedCustomerEntry.getReference())
                .isEqualTo(customerEntryReference);

        assertThat(savedCustomerEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedCustomerEntry.getCurrency())
                .isSameAs(account.getCurrency());

        assertThat(savedCustomerEntry.getBalanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(savedCustomerEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(savedCustomerEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(savedCustomerEntry.getLedgerAccount())
                .isSameAs(ledgerAccount);

        assertThat(savedInternalEntry.getReference())
                .isEqualTo(internalEntryReference);

        assertThat(savedInternalEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedInternalEntry.getCurrency())
                .isSameAs(account.getCurrency());

        assertThat(savedInternalEntry.getBalanceAfter())
                .isEqualByComparingTo(internalAccount.getBalance());

        assertThat(savedInternalEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(savedInternalEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(savedInternalEntry.getLedgerAccount())
                .isSameAs(internalLedgerAccount);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedCustomerEntry.getBalanceAfter());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());

        assertThat(response.entryReference())
                .isEqualTo(savedCustomerEntry.getReference());

        assertThat(response.transactionType())
                .isSameAs(savedTransaction.getTransactionType());

        assertThat(response.amount())
                .isEqualByComparingTo(savedCustomerEntry.getAmount());

        assertThat(response.note())
                .isEqualTo(savedTransaction.getNote());

        assertThat(response.currency())
                .isSameAs(savedCustomerEntry.getCurrency());

        assertThat(account.getBalance())
                .isEqualByComparingTo(savedCustomerEntry.getBalanceAfter());

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);

        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(
                        requestHasher.hashRequest(String.join(
                                        "|",
                                        savedTransaction.getTransactionType().name(),
                                        accountNumber,
                                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                        request.requestedCurrency().name(),
                                        account.getCurrency().name(),
                                        "",
                                        request.note().trim()
                                )
                        )
                );

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);

        verify(referenceGenerator, times(3)).generate();

        verify(accountRepository, never()).save(any(Account.class));

        verify(internalAccountRepository, times(1)).findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY);
    }

    @Test
    void depositThrowsAccountNotFoundExceptionWhenOwnedAccountDoesNotExist() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                null
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verifyNoInteractions(referenceGenerator, bankTransactionRepository, idempotencyService, internalAccountRepository);
    }

    @Test
    void depositThrowsInvalidIdempotencyKeyExceptionForInvalidIdempotencyKey() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "1111111111111111!111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                null
        );

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(referenceGenerator, bankTransactionRepository, idempotencyService, accountRepository, internalAccountRepository);
    }

    @Test
    void depositWithExistingIdempotencyKeyReturnsTheOldResponse() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String internalEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String customerEntryReference = "8f3c8a21d9e64b5fa2c17e9084bd6a32";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                null
        );

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

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        String requestHash = requestHasher
                .hashRequest(String
                        .join(
                                "|",
                                TransactionType.DEPOSIT.name(),
                                accountNumber,
                                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                request.requestedCurrency().name(),
                                account.getCurrency().name(),
                                "",
                                request.note().trim()
                        )
                );

        BankTransaction bankTransaction = BankTransaction.createNew(
                request.amount(),
                account.getCurrency(),
                TransactionType.DEPOSIT,
                transactionReference,
                request.note()
        );

        BigDecimal balanceAfter = ledgerAccount.credit(request.amount());
        BigDecimal internalAccountBalanceAfter = internalLedgerAccount.debit(request.amount());

        bankTransaction.addEntry(
                internalEntryReference,
                request.amount(),
                internalAccountBalanceAfter,
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        bankTransaction.addEntry(
                customerEntryReference,
                request.amount(),
                balanceAfter,
                EntryDirection.CREDIT,
                ledgerAccount
        );

        bankTransaction.complete();

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                bankTransaction
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        ReflectionTestUtils.setField(ledgerAccount, "id", 1L);

        DepositResponse response = depositService.deposit(request, accountNumber, email, idempotencyKey);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(transactionReference);

        assertThat(response.entryReference())
                .isEqualTo(customerEntryReference);

        assertThat(response.transactionType())
                .isSameAs(TransactionType.DEPOSIT);

        verify(bankTransactionRepository, never()).save(any());

        verify(idempotencyService, never()).save(any());

        verifyNoInteractions(referenceGenerator, internalAccountRepository);
    }

    @Test
    void crossCurrencyDepositUsesRateLockAndPostsConvertedAmounts() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String customerEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String internalEntryReference = "8f3c8a21d9e64b5fa2c17e9084bd6a32";
        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

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

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.USD,
                lockId
        );

        FxRateLockResponse fxResponse = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-08-30T12:00:00Z"),
                request.requestedCurrency(),
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.14"),
                        CurrencyCode.GBP, new BigDecimal("0.74"),
                        CurrencyCode.EUR, new BigDecimal("0.86")
                )
        );

        String requestHash = requestHasher.hashRequest(String.join(
                        "|",
                        TransactionType.DEPOSIT.name(),
                        accountNumber,
                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        request.requestedCurrency().name(),
                        account.getCurrency().name(),
                        lockId,
                        request.note().trim()
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, request.requestedCurrency()))
                .willReturn(Optional.of(internalAccount));

        given(referenceGenerator.generate())
                .willReturn(transactionReference, internalEntryReference, customerEntryReference);

        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        given(fxRateService.getCachedRate(email, lockId))
                .willReturn(fxResponse);

        DepositResponse response = depositService.deposit(request, accountNumber, email, idempotencyKey);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();

        assertThat(savedTransaction.getEntries())
                .hasSize(2);

        assertThat(savedTransaction.getEntries())
                .extracting(LedgerEntry::getReference)
                .doesNotHaveDuplicates();

        LedgerEntry savedCustomerEntry = savedTransaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry savedInternalEntry = savedTransaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(savedTransaction.getReference())
                .isEqualTo(transactionReference);

        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.DEPOSIT);

        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(savedTransaction.getFxInfo().getLockId())
                .isEqualTo(lockId);

        assertThat(savedTransaction.getFxInfo().getRates())
                .extracting(FxRate::getCurrency, FxRate::getRate, FxRate::getCurrencyContext)
                .containsExactlyInAnyOrder(
                        tuple(request.requestedCurrency(), BigDecimal.ONE, CurrencyContext.REQUEST),
                        tuple(request.requestedCurrency(), BigDecimal.ONE, CurrencyContext.SOURCE),
                        tuple(account.getCurrency(), new BigDecimal("48.14"), CurrencyContext.DESTINATION)
                );

        assertThat(savedCustomerEntry.getReference())
                .isEqualTo(customerEntryReference);

        assertThat(savedCustomerEntry.getAmount())
                .isEqualByComparingTo(
                        request.amount()
                                .multiply(fxResponse.rates().get(account.getCurrency()))
                                .setScale(2, RoundingMode.HALF_EVEN)
                );

        assertThat(savedCustomerEntry.getCurrency())
                .isSameAs(account.getCurrency());

        assertThat(savedCustomerEntry.getBalanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(savedCustomerEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(savedCustomerEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(savedCustomerEntry.getLedgerAccount())
                .isSameAs(ledgerAccount);

        assertThat(savedInternalEntry.getReference())
                .isEqualTo(internalEntryReference);

        assertThat(savedInternalEntry.getAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedInternalEntry.getCurrency())
                .isSameAs(request.requestedCurrency());

        assertThat(savedInternalEntry.getBalanceAfter())
                .isEqualByComparingTo(internalAccount.getBalance());

        assertThat(savedInternalEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(savedInternalEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(savedInternalEntry.getLedgerAccount())
                .isSameAs(internalLedgerAccount);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedCustomerEntry.getBalanceAfter());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());

        assertThat(response.entryReference())
                .isEqualTo(savedCustomerEntry.getReference());

        assertThat(response.transactionType())
                .isSameAs(savedTransaction.getTransactionType());

        assertThat(response.amount())
                .isEqualByComparingTo(savedCustomerEntry.getAmount());

        assertThat(response.note())
                .isEqualTo(savedTransaction.getNote());

        assertThat(response.currency())
                .isSameAs(savedCustomerEntry.getCurrency());

        assertThat(account.getBalance())
                .isEqualByComparingTo(savedCustomerEntry.getBalanceAfter());

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);

        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(
                        requestHasher.hashRequest(String.join(
                                        "|",
                                        savedTransaction.getTransactionType().name(),
                                        accountNumber,
                                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                        request.requestedCurrency().name(),
                                        account.getCurrency().name(),
                                        lockId,
                                        request.note().trim()
                                )
                        )
                );

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);

        verify(fxRateService).getCachedRate(email, lockId);

        verify(referenceGenerator, times(3)).generate();

        verify(accountRepository, never()).save(any(Account.class));

        verify(internalAccountRepository, times(1)).findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD);
    }

    @Test
    void crossCurrencyIdempotentReplayDoesNotReloadRateLock() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String internalEntryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String customerEntryReference = "8f3c8a21d9e64b5fa2c17e9084bd6a32";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.USD,
                lockId
        );

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

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        String requestHash = requestHasher
                .hashRequest(String
                        .join(
                                "|",
                                TransactionType.DEPOSIT.name(),
                                accountNumber,
                                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                request.requestedCurrency().name(),
                                account.getCurrency().name(),
                                lockId,
                                request.note().trim()
                        )
                );

        FxInfo fxInfo = FxInfo.createNew(
                lockId,
                Set.of(
                        FxRate.createNew(
                                CurrencyContext.REQUEST,
                                request.requestedCurrency(),
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.SOURCE,
                                request.requestedCurrency(),
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.DESTINATION,
                                account.getCurrency(),
                                new BigDecimal("48.14")
                        )
                )
        );

        BankTransaction bankTransaction = BankTransaction.createNew(
                request.amount(),
                request.requestedCurrency(),
                TransactionType.DEPOSIT,
                transactionReference,
                request.note()
        );

        bankTransaction.addFxInfo(fxInfo);

        BigDecimal balanceAfter = ledgerAccount.credit(request.amount().multiply(fxInfo.getRate(CurrencyContext.DESTINATION)).setScale(2, RoundingMode.HALF_EVEN));
        BigDecimal internalAccountBalanceAfter = internalLedgerAccount.debit(request.amount());

        bankTransaction.addEntry(
                internalEntryReference,
                request.amount(),
                internalAccountBalanceAfter,
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        bankTransaction.addEntry(
                customerEntryReference,
                request.amount().multiply(fxInfo.getRate(CurrencyContext.DESTINATION)).setScale(2, RoundingMode.HALF_EVEN),
                balanceAfter,
                EntryDirection.CREDIT,
                ledgerAccount
        );

        bankTransaction.complete();

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                bankTransaction
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        ReflectionTestUtils.setField(ledgerAccount, "id", 1L);

        DepositResponse response = depositService.deposit(request, accountNumber, email, idempotencyKey);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(account.getBalance());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(transactionReference);

        assertThat(response.entryReference())
                .isEqualTo(customerEntryReference);

        assertThat(response.transactionType())
                .isSameAs(TransactionType.DEPOSIT);

        verify(bankTransactionRepository, never()).save(any());

        verify(idempotencyService, never()).save(any());

        verifyNoInteractions(referenceGenerator, internalAccountRepository, fxRateService);
    }

    @Test
    void crossCurrencyDepositWithoutLockIsRejected() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.USD,
                null
        );

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

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("Lock ID is required for cross-currency operation.");

        verifyNoInteractions(bankTransactionRepository, internalAccountRepository, referenceGenerator, fxRateService, idempotencyService);
    }

    @Test
    void sameCurrencyDepositWithLockIsRejected() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.TRY,
                lockId
        );

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

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Lock ID is not required for same currency operation.");

        verifyNoInteractions(bankTransactionRepository, internalAccountRepository, referenceGenerator, fxRateService, idempotencyService);
    }

    @Test
    void crossCurrencyDepositRejectsLockWithDifferentBaseCurrency() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

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

        LedgerAccount internalLedgerAccount = LedgerAccount.createNew(
                "9f3c8a21d9e64b5fa2c17e9084bd6a31",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        InternalAccount internalAccount = InternalAccount.createNew(
                InternalAccountPurpose.SETTLEMENT,
                internalLedgerAccount
        );

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test",
                CurrencyCode.USD,
                lockId
        );

        FxRateLockResponse fxResponse = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-08-30T12:00:00Z"),
                CurrencyCode.EUR,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.14"),
                        CurrencyCode.GBP, new BigDecimal("0.74"),
                        CurrencyCode.EUR, new BigDecimal("0.86")
                )
        );

        String requestHash = requestHasher.hashRequest(String.join(
                        "|",
                        TransactionType.DEPOSIT.name(),
                        accountNumber,
                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        request.requestedCurrency().name(),
                        account.getCurrency().name(),
                        lockId,
                        request.note().trim()
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        given(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, request.requestedCurrency()))
                .willReturn(Optional.of(internalAccount));

        given(referenceGenerator.generate())
                .willReturn(transactionReference);

        given(fxRateService.getCachedRate(email, lockId))
                .willReturn(fxResponse);

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("The base currency in the cached fx rate must be the same as the requested currency");

        verifyNoInteractions(bankTransactionRepository);
    }
}
