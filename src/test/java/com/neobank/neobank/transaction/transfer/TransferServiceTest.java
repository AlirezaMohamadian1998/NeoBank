package com.neobank.neobank.transaction.transfer;

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
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final String SOURCE_LEDGER_REFERENCE = "1f3c8a21d9e64b5fa2c17e9084bd6a35";
    private static final String DESTINATION_LEDGER_REFERENCE = "2f3c8a21d9e64b5fa2c17e9084bd6a36";
    private static final String TRANSACTION_REFERENCE = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
    private static final String SOURCE_ENTRY_REFERENCE = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
    private static final String DESTINATION_ENTRY_REFERENCE = "af3c8a21d9e64b5fa2c17e9084bd6a34";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private FxRateService fxRateService;

    private final RequestHasher requestHasher = new RequestHasher();

    private TransferService transferService;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @Captor
    private ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        transferService = new TransferService(
                bankTransactionRepository,
                referenceGenerator,
                accountRepository,
                ledgerPostingService,
                idempotencyService,
                requestHasher,
                fxRateService
        );
    }

    @Test
    void transferDebitsSourceAndCreditsDestinationAndSavesCompletedTransactionWithBothEntries() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");
        String idempotencyKey = "11111111111111111111111111111111";

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.TRY,
                null
        );

        String requestHash = hash(sourceAccount, destinationAccount, request, null);

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));
        given(referenceGenerator.generate())
                .willReturn(
                        TRANSACTION_REFERENCE,
                        SOURCE_ENTRY_REFERENCE,
                        DESTINATION_ENTRY_REFERENCE
                );
        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, sourceCustomer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        var response = transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        );

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());
        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();
        List<LedgerEntry> entries = savedTransaction.getEntries();

        assertThat(entries).hasSize(2);
        LedgerEntry sourceEntry = entries.get(0);
        LedgerEntry destinationEntry = entries.get(1);

        assertThat(savedTransaction.getReference())
                .isEqualTo(TRANSACTION_REFERENCE);
        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.TRANSFER);
        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);
        assertThat(savedTransaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(savedTransaction.getRequestedCurrency())
                .isSameAs(request.currency());
        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(sourceEntry.getReference())
                .isEqualTo(SOURCE_ENTRY_REFERENCE);
        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(sourceEntry.getBalanceAfter())
                .isEqualByComparingTo(sourceAccount.getBalance());
        assertThat(sourceEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);
        assertThat(sourceEntry.getCurrency())
                .isSameAs(sourceAccount.getCurrency());
        assertThat(sourceEntry.getLedgerAccount())
                .isSameAs(sourceAccount.getLedgerAccount());
        assertThat(sourceEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(destinationEntry.getReference())
                .isEqualTo(DESTINATION_ENTRY_REFERENCE);
        assertThat(destinationEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(destinationEntry.getBalanceAfter())
                .isEqualByComparingTo(destinationAccount.getBalance());
        assertThat(destinationEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);
        assertThat(destinationEntry.getCurrency())
                .isSameAs(destinationAccount.getCurrency());
        assertThat(destinationEntry.getLedgerAccount())
                .isSameAs(destinationAccount.getLedgerAccount());
        assertThat(destinationEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());
        assertThat(response.sourceEntryReference())
                .isEqualTo(sourceEntry.getReference());
        assertThat(response.transactionType())
                .isSameAs(TransactionType.TRANSFER);
        assertThat(response.sourceAccountNumber())
                .isEqualTo(sourceAccount.getAccountNumber());
        assertThat(response.destinationAccountNumber())
                .isEqualTo(destinationAccount.getAccountNumber());
        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());
        assertThat(response.sourceBalanceAfter())
                .isEqualByComparingTo(sourceEntry.getBalanceAfter());
        assertThat(response.currency())
                .isSameAs(request.currency());
        assertThat(response.note())
                .isEqualTo(request.note());
        assertThat(response.createdAt())
                .isEqualTo(savedTransaction.getCreatedAt());

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("500.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("500.00");

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);
        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(sourceCustomer);
        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(savedTransaction);
        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(hash(sourceAccount, destinationAccount, request, null));


        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository).findByAccountNumber(destinationAccount.getAccountNumber());
        verify(referenceGenerator, times(3)).generate();
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void transferThrowsAccountNotFoundExceptionWhenSourceAccountDoesNotExist() {
        String destinationAccountNumber = "12345678900987";
        String sourceAccountNumber = "98765432100123";
        String email = "source@example.com";
        String idempotencyKey = "11111111111111111111111111111111";

        TransferRequest request = createTransferRequest(
                destinationAccountNumber,
                new BigDecimal("500.00"),
                CurrencyCode.TRY,
                null
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(request, sourceAccountNumber, email, idempotencyKey))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, email);
        verify(accountRepository, never()).findByAccountNumber(any(String.class));
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(bankTransactionRepository, referenceGenerator, idempotencyService);
    }

    @Test
    void transferThrowsInvalidTransferExceptionWhenSourceAndDestinationHaveSameAccountNumber() {
        String idempotencyKey = "11111111111111111111111111111111";
        String sameAccountNumber = "12345678900987";
        Customer sourceCustomer = createCustomer("source@example.com");
        Account sourceAccount = createAccount(
                sameAccountNumber,
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );

        TransferRequest request = createTransferRequest(
                sameAccountNumber,
                new BigDecimal("500.00"),
                CurrencyCode.TRY,
                null
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        )).isInstanceOf(InvalidTransferException.class)
                .hasMessage("Source and destination accounts cannot be the same");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository, never()).findByAccountNumber(sameAccountNumber);
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(bankTransactionRepository, referenceGenerator, idempotencyService);
    }

    @Test
    void transferThrowsAccountNotFoundExceptionWhenDestinationAccountDoesNotExist() {
        String idempotencyKey = "11111111111111111111111111111111";
        String destinationAccountNumber = "98765432100123";
        Customer sourceCustomer = createCustomer("source@example.com");
        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );

        TransferRequest request = createTransferRequest(
                destinationAccountNumber,
                new BigDecimal("500.00"),
                CurrencyCode.TRY,
                null
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(destinationAccountNumber))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        )).isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Destination account not found");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository).findByAccountNumber(request.destinationAccountNumber());
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(bankTransactionRepository, referenceGenerator, idempotencyService);
    }

    @Test
    void transferThrowsInsufficientFundsWhenSourceBalanceIsLessThanAmount() {
        String idempotencyKey = "11111111111111111111111111111111";
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");
        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("100.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.TRY,
                null
        );

        String requestHash = hash(sourceAccount, destinationAccount, request, null);

        given(idempotencyService.findAndValidateRecord(idempotencyKey, sourceCustomer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));
        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE, SOURCE_ENTRY_REFERENCE);

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        )).isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("100.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository).findByAccountNumber(destinationAccount.getAccountNumber());
        verify(referenceGenerator, times(2)).generate();
        verify(accountRepository, never()).save(any(Account.class));
        verify(idempotencyService, never()).save(any());

        verifyNoInteractions(bankTransactionRepository);
    }

    @Test
    void transferThrowsInvalidIdempotencyKeyWhenKeyInvalid() {
        String email = "customer@example.com";
        String idempotencyKey = "1111111111111111!111111111111111";

        TransferRequest request = createTransferRequest(
                "98765432100123",
                new BigDecimal("500.00"),
                CurrencyCode.TRY,
                null
        );

        assertThatThrownBy(() -> transferService.transfer(request, "12345678900321", email, idempotencyKey))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(referenceGenerator, bankTransactionRepository, idempotencyService, accountRepository);
    }

    @Test
    void transferReturnsOldResponseWhenIdempotencyKeyAlreadyExists() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");
        String idempotencyKey = "11111111111111111111111111111111";

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.TRY,
                null
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));

        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        String requestHash = hash(sourceAccount, destinationAccount, request, null);

        BankTransaction savedTransaction = BankTransaction.createNew(
                request.amount(),
                request.currency(),
                TransactionType.TRANSFER,
                TRANSACTION_REFERENCE,
                request.note()
        );

        BigDecimal sourceBalanceAfter = sourceAccount.getLedgerAccount().debit(request.amount());

        savedTransaction.addEntry(
                SOURCE_ENTRY_REFERENCE,
                request.amount(),
                sourceBalanceAfter,
                EntryDirection.DEBIT,
                sourceAccount.getLedgerAccount()
        );

        BigDecimal destinationBalanceAfter = destinationAccount.getLedgerAccount().credit(request.amount());

        savedTransaction.addEntry(
                DESTINATION_ENTRY_REFERENCE,
                request.amount(),
                destinationBalanceAfter,
                EntryDirection.CREDIT,
                destinationAccount.getLedgerAccount()
        );

        savedTransaction.complete();

        ReflectionTestUtils.setField(sourceAccount.getLedgerAccount(), "id", 1L);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                sourceCustomer,
                savedTransaction
        );

        given(idempotencyService.findAndValidateRecord(idempotencyKey, sourceCustomer.getEmail(), requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        TransferResponse response = transferService.transfer(request, sourceAccount.getAccountNumber(), sourceCustomer.getEmail(), idempotencyKey);

        assertThat(response.sourceBalanceAfter())
                .isEqualByComparingTo(sourceAccount.getBalance());

        assertThat(response.sourceAccountNumber())
                .isEqualTo(sourceAccount.getAccountNumber());

        assertThat(response.transactionReference())
                .isEqualTo(TRANSACTION_REFERENCE);

        assertThat(response.sourceEntryReference())
                .isEqualTo(SOURCE_ENTRY_REFERENCE);

        assertThat(response.transactionType())
                .isSameAs(TransactionType.TRANSFER);

        assertThat(response.destinationAccountNumber())
                .isEqualTo(destinationAccount.getAccountNumber());

        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.currency())
                .isSameAs(request.currency());

        assertThat(response.note())
                .isEqualTo(request.note());

        verify(bankTransactionRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(referenceGenerator);
    }


    @Test
    void crossCurrencyTransferConvertsBothAccountsWhenRequestCurrencyDiffersFromBoth() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");

        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.EUR,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.USD,
                lockId
        );

        String requestHash = hash(sourceAccount, destinationAccount, request, lockId);

        FxRateLockResponse fxResponse = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-09-05T12:00:00Z"),
                request.currency(),
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.00"),
                        CurrencyCode.GBP, new BigDecimal("0.75"),
                        CurrencyCode.EUR, new BigDecimal("0.85")
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));

        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, sourceCustomer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE, SOURCE_ENTRY_REFERENCE, DESTINATION_ENTRY_REFERENCE);

        given(fxRateService.getCachedRate(sourceCustomer.getEmail(), lockId))
                .willReturn(fxResponse);

        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        TransferResponse response = transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        );

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction transaction = bankTransactionCaptor.getValue();

        assertThat(transaction.getEntries())
                .hasSize(2);

        LedgerEntry sourceEntry = transaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry destinationEntry = transaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(response.transactionReference())
                .isEqualTo(transaction.getReference());

        assertThat(response.sourceEntryReference())
                .isEqualTo(sourceEntry.getReference());

        assertThat(response.transactionType())
                .isSameAs(transaction.getTransactionType());

        assertThat(response.sourceAccountNumber())
                .isEqualTo(sourceAccount.getAccountNumber());

        assertThat(response.destinationAccountNumber())
                .isEqualTo(destinationAccount.getAccountNumber());

        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.sourceBalanceAfter())
                .isEqualByComparingTo(sourceEntry.getBalanceAfter());

        assertThat(response.currency())
                .isSameAs(request.currency());

        assertThat(response.note())
                .isEqualTo(transaction.getNote());

        assertThat(transaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(transaction.getRequestedCurrency())
                .isSameAs(request.currency());

        assertThat(transaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(transaction.getTransactionType())
                .isSameAs(TransactionType.TRANSFER);

        assertThat(transaction.getReference())
                .isEqualTo(TRANSACTION_REFERENCE);

        assertThat(transaction.getNote())
                .isEqualTo(request.note());

        assertThat(transaction.getEntries())
                .extracting(LedgerEntry::getDirection)
                .doesNotHaveDuplicates();

        assertThat(transaction.getFxInfo().getLockId())
                .isEqualTo(lockId);

        assertThat(transaction.getFxInfo().getRates())
                .extracting(FxRate::getCurrencyContext, FxRate::getCurrency, FxRate::getRate)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyContext.REQUEST, request.currency(), BigDecimal.ONE),
                        tuple(CurrencyContext.SOURCE, sourceAccount.getCurrency(), new BigDecimal("0.85")),
                        tuple(CurrencyContext.DESTINATION, destinationAccount.getCurrency(), new BigDecimal("48.00"))
                );

        assertThat(sourceEntry.getReference())
                .isEqualTo(SOURCE_ENTRY_REFERENCE);

        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo(request.amount().multiply(fxResponse.rates().get(sourceAccount.getCurrency())));

        assertThat(sourceEntry.getBalanceAfter())
                .isEqualByComparingTo(sourceAccount.getBalance());

        assertThat(sourceEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(sourceEntry.getCurrency())
                .isSameAs(sourceAccount.getCurrency());

        assertThat(sourceEntry.getLedgerAccount())
                .isSameAs(sourceAccount.getLedgerAccount());

        assertThat(sourceEntry.getBankTransaction())
                .isSameAs(transaction);

        assertThat(destinationEntry.getReference())
                .isEqualTo(DESTINATION_ENTRY_REFERENCE);

        assertThat(destinationEntry.getAmount())
                .isEqualByComparingTo(request.amount().multiply(fxResponse.rates().get(destinationAccount.getCurrency())));

        assertThat(destinationEntry.getBalanceAfter())
                .isEqualByComparingTo(destinationAccount.getBalance());

        assertThat(destinationEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(destinationEntry.getCurrency())
                .isSameAs(destinationAccount.getCurrency());

        assertThat(destinationEntry.getLedgerAccount())
                .isSameAs(destinationAccount.getLedgerAccount());

        assertThat(destinationEntry.getBankTransaction())
                .isSameAs(transaction);

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("575.00"));

        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("24000.00"));

        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(requestHash);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(sourceCustomer);

        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(transaction);

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );

        verify(accountRepository).findByAccountNumber(destinationAccount.getAccountNumber());

        verify(referenceGenerator, times(3)).generate();

        verify(accountRepository, never()).save(any(Account.class));

        verify(fxRateService).getCachedRate(sourceCustomer.getEmail(), lockId);
    }

    @Test
    void transferBetweenSameCurrencyAccountsUsesLockWhenRequestCurrencyDiffers() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");

        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.EUR,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.EUR,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.USD,
                lockId
        );

        String requestHash = hash(sourceAccount, destinationAccount, request, lockId);

        FxRateLockResponse fxResponse = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-09-07T12:00:00Z"),
                request.currency(),
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.00"),
                        CurrencyCode.GBP, new BigDecimal("0.75"),
                        CurrencyCode.EUR, new BigDecimal("0.85")
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));

        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        given(fxRateService.getCachedRate(sourceCustomer.getEmail(), lockId))
                .willReturn(fxResponse);

        given(idempotencyService.findAndValidateRecord(idempotencyKey, sourceCustomer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE, SOURCE_ENTRY_REFERENCE, DESTINATION_ENTRY_REFERENCE);

        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        TransferResponse response = transferService.transfer(request, sourceAccount.getAccountNumber(), sourceCustomer.getEmail(), idempotencyKey);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());
        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        BankTransaction transaction = bankTransactionCaptor.getValue();
        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(transaction.getEntries())
                .hasSize(2);

        LedgerEntry sourceEntry = transaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry destinationEntry = transaction.getEntries()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(response.transactionReference())
                .isEqualTo(transaction.getReference());

        assertThat(response.sourceEntryReference())
                .isEqualTo(sourceEntry.getReference());

        assertThat(response.transactionType())
                .isSameAs(transaction.getTransactionType());

        assertThat(response.sourceAccountNumber())
                .isEqualTo(sourceAccount.getAccountNumber());

        assertThat(response.destinationAccountNumber())
                .isEqualTo(destinationAccount.getAccountNumber());

        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.sourceBalanceAfter())
                .isEqualByComparingTo(sourceEntry.getBalanceAfter());

        assertThat(response.currency())
                .isSameAs(request.currency());

        assertThat(response.note())
                .isEqualTo(transaction.getNote());

        assertThat(transaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(transaction.getRequestedCurrency())
                .isSameAs(request.currency());

        assertThat(transaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(transaction.getTransactionType())
                .isSameAs(TransactionType.TRANSFER);

        assertThat(transaction.getReference())
                .isEqualTo(TRANSACTION_REFERENCE);

        assertThat(transaction.getNote())
                .isEqualTo(request.note());

        assertThat(transaction.getEntries())
                .extracting(LedgerEntry::getDirection)
                .doesNotHaveDuplicates();

        assertThat(transaction.getFxInfo().getLockId())
                .isEqualTo(lockId);

        assertThat(transaction.getFxInfo().getRates())
                .extracting(FxRate::getCurrencyContext, FxRate::getCurrency, FxRate::getRate)
                .containsExactlyInAnyOrder(
                        tuple(CurrencyContext.REQUEST, request.currency(), fxResponse.rates().get(request.currency())),
                        tuple(CurrencyContext.SOURCE, sourceAccount.getCurrency(), fxResponse.rates().get(sourceAccount.getCurrency())),
                        tuple(CurrencyContext.DESTINATION, destinationAccount.getCurrency(), fxResponse.rates().get(destinationAccount.getCurrency()))
                );

        assertThat(sourceEntry.getReference())
                .isEqualTo(SOURCE_ENTRY_REFERENCE);

        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo(request.amount().multiply(fxResponse.rates().get(sourceAccount.getCurrency())));

        assertThat(sourceEntry.getBalanceAfter())
                .isEqualByComparingTo(sourceAccount.getBalance());

        assertThat(sourceEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(sourceEntry.getCurrency())
                .isSameAs(sourceAccount.getCurrency());

        assertThat(sourceEntry.getLedgerAccount())
                .isSameAs(sourceAccount.getLedgerAccount());

        assertThat(sourceEntry.getBankTransaction())
                .isSameAs(transaction);

        assertThat(destinationEntry.getReference())
                .isEqualTo(DESTINATION_ENTRY_REFERENCE);

        assertThat(destinationEntry.getAmount())
                .isEqualByComparingTo(request.amount().multiply(fxResponse.rates().get(destinationAccount.getCurrency())));

        assertThat(destinationEntry.getBalanceAfter())
                .isEqualByComparingTo(destinationAccount.getBalance());

        assertThat(destinationEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(destinationEntry.getCurrency())
                .isSameAs(destinationAccount.getCurrency());

        assertThat(destinationEntry.getLedgerAccount())
                .isSameAs(destinationAccount.getLedgerAccount());

        assertThat(destinationEntry.getBankTransaction())
                .isSameAs(transaction);

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("575.00"));

        assertThat(destinationAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("425.00"));

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);

        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(requestHash);

        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(sourceCustomer);

        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(transaction);

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );

        verify(accountRepository).findByAccountNumber(destinationAccount.getAccountNumber());

        verify(referenceGenerator, times(3)).generate();

        verify(accountRepository, never()).save(any(Account.class));

        verify(fxRateService).getCachedRate(sourceCustomer.getEmail(), lockId);

    }

    @Test
    void crossCurrencyIdempotentReplayDoesNotReloadRateLock() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");

        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.EUR,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.USD,
                lockId
        );

        String requestHash = hash(sourceAccount, destinationAccount, request, lockId);

        FxInfo fxInfo = FxInfo.createNew(
                lockId,
                Set.of(
                        FxRate.createNew(
                                CurrencyContext.REQUEST,
                                request.currency(),
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.SOURCE,
                                sourceAccount.getCurrency(),
                                new BigDecimal("0.85")
                        ),
                        FxRate.createNew(
                                CurrencyContext.DESTINATION,
                                destinationAccount.getCurrency(),
                                new BigDecimal("48.00")
                        )
                )
        );

        BankTransaction transaction = BankTransaction.createNew(
                request.amount(),
                request.currency(),
                TransactionType.TRANSFER,
                TRANSACTION_REFERENCE,
                request.note()
        );

        transaction.addFxInfo(fxInfo);

        BigDecimal sourceMoneyMovementAmount = request.amount().multiply(fxInfo.getRate(CurrencyContext.SOURCE)).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal destinationMoneyMovementAmount = request.amount().multiply(fxInfo.getRate(CurrencyContext.DESTINATION)).setScale(2, RoundingMode.HALF_EVEN);

        sourceAccount.getLedgerAccount().debit(sourceMoneyMovementAmount);
        destinationAccount.getLedgerAccount().credit(destinationMoneyMovementAmount);

        LedgerEntry sourceEntry = transaction.addEntry(
                SOURCE_ENTRY_REFERENCE,
                sourceMoneyMovementAmount,
                sourceAccount.getBalance(),
                EntryDirection.DEBIT,
                sourceAccount.getLedgerAccount()
        );

        LedgerEntry destinationEntry = transaction.addEntry(
                DESTINATION_ENTRY_REFERENCE,
                destinationMoneyMovementAmount,
                destinationAccount.getBalance(),
                EntryDirection.CREDIT,
                destinationAccount.getLedgerAccount()
        );

        transaction.complete();

        ReflectionTestUtils.setField(sourceAccount.getLedgerAccount(), "id", 1L);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                sourceCustomer,
                transaction
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));

        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, sourceCustomer.getEmail(), requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        TransferResponse response = transferService.transfer(request, sourceAccount.getAccountNumber(), sourceCustomer.getEmail(), idempotencyKey);

        assertThat(response.transactionReference())
                .isEqualTo(transaction.getReference());

        assertThat(response.sourceEntryReference())
                .isEqualTo(sourceEntry.getReference());

        assertThat(response.transactionType())
                .isSameAs(transaction.getTransactionType());

        assertThat(response.sourceAccountNumber())
                .isEqualTo(sourceAccount.getAccountNumber());

        assertThat(response.destinationAccountNumber())
                .isEqualTo(destinationAccount.getAccountNumber());

        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.sourceBalanceAfter())
                .isEqualByComparingTo(sourceEntry.getBalanceAfter());

        assertThat(response.currency())
                .isSameAs(request.currency());

        assertThat(response.note())
                .isEqualTo(request.note());

        verify(idempotencyService, never()).save(any(IdempotencyRecord.class));

        verifyNoInteractions(fxRateService, referenceGenerator, bankTransactionRepository);
    }

    @Test
    void crossCurrencyTransferWithoutLockIsRejected() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");

        String idempotencyKey = "11111111111111111111111111111111";

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.EUR,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.USD,
                null
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));

        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        ))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("Lock ID is required for cross-currency operation.");

        verifyNoInteractions(fxRateService, referenceGenerator, bankTransactionRepository, idempotencyService);

    }

    @Test
    void sameCurrencyTransferWithLockIsRejected() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");

        String idempotencyKey = "11111111111111111111111111111111";

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.EUR,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.EUR,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.EUR,
                "4ea56d0d-aa07-4d26-be23-ce971c0976a0"
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));

        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        ))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Lock ID is not required for same currency operation.");

        verifyNoInteractions(fxRateService, referenceGenerator, bankTransactionRepository, idempotencyService);
    }

    @Test
    void crossCurrencyTransferRejectsLockWithDifferentBaseCurrency() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");

        String idempotencyKey = "11111111111111111111111111111111";
        String lockId = "4ea56d0d-aa07-4d26-be23-ce971c0976a0";

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.EUR,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.USD,
                lockId
        );

        String requestHash = hash(sourceAccount, destinationAccount, request, lockId);

        FxRateLockResponse fxResponse = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-09-07T12:00:00Z"),
                CurrencyCode.GBP,
                Map.of(
                        CurrencyCode.GBP, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("65.50"),
                        CurrencyCode.USD, new BigDecimal("1.35"),
                        CurrencyCode.EUR, new BigDecimal("1.15")
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));

        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, sourceCustomer.getEmail(), requestHash))
                .willReturn(Optional.empty());

        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE);

        given(fxRateService.getCachedRate(sourceCustomer.getEmail(), lockId))
                .willReturn(fxResponse);


        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        ))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("The base currency in the cached fx rate must be the same as the requested currency");

        verifyNoInteractions(bankTransactionRepository);
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
            Customer customer
    ) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                ledgerReference,
                LedgerAccountType.LIABILITY,
                currency
        );

        return Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );
    }

    private TransferRequest createTransferRequest(
            String destinationAccountNumber,
            BigDecimal amount,
            CurrencyCode currency,
            String lockId
    ) {
        return new TransferRequest(
                amount,
                destinationAccountNumber,
                "Test",
                currency,
                lockId
        );
    }

    private String hash(Account sourceAccount, Account destinationAccount, TransferRequest request, String lockId) {
        return requestHasher.hashRequest(String.join(
                        "|",
                        TransactionType.TRANSFER.name(),
                        sourceAccount.getAccountNumber(),
                        destinationAccount.getAccountNumber(),
                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        request.currency().name(),
                        sourceAccount.getCurrency().name(),
                        destinationAccount.getCurrency().name(),
                        lockId != null ? lockId : "",
                        request.note().trim()
                )
        );
    }
}
