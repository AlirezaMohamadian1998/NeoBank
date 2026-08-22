package com.neobank.neobank.idempotency;

import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class IdempotencyRecordTest {

    @Mock
    private BankTransaction bankTransaction;

    @Mock
    private Customer customer;

    @Test
    void validCreationPreservesAllFields() {
        given(bankTransaction.getStatus())
                .willReturn(TransactionStatus.COMPLETED);

        var idempotencyRecord = IdempotencyRecord.createNew(
                "22222222222222222222222222222222",
                "2222222222222222222222222222222211111111111111111111111111111111",
                customer,
                bankTransaction
        );


        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo("22222222222222222222222222222222");
        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo("2222222222222222222222222222222211111111111111111111111111111111");
        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);
        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(bankTransaction);
    }

    @Test
    void wrongKeyFormatIsRejected() {
        given(bankTransaction.getStatus())
                .willReturn(TransactionStatus.COMPLETED);

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "222222222222222222!22222222222222",
                        "2222222222222222222222222222222211111111111111111111111111111111",
                        customer,
                        bankTransaction
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key must contain only hex characters [a-f, 0-9] and be exactly 32 characters long");
    }

    @Test
    void nullKeyIsRejected() {

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        null,
                        "2222222222222222222222222222222211111111111111111111111111111111",
                        customer,
                        bankTransaction
                ))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullCustomerIsRejected() {
        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "22222222222222222222222222222222",
                        "2222222222222222222222222222222211111111111111111111111111111111",
                        null,
                        bankTransaction
                ))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTransactionIsRejected() {
        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "22222222222222222222222222222222",
                        "2222222222222222222222222222222211111111111111111111111111111111",
                        customer,
                        null
                ))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullRequestHashIsRejected() {
        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "22222222222222222222222222222222",
                        null,
                        customer,
                        bankTransaction
                ))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void wrongRequestHashFormatIsRejected() {
        given(bankTransaction.getStatus())
                .willReturn(TransactionStatus.COMPLETED);

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "22222222222222222222222222222222",
                        "22222222222222222222222222222222!?11111111111111111111111111111111",
                        customer,
                        bankTransaction
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request hash must contain only hex characters [a-f, 0-9] and be exactly 64 characters long");
    }

    @Test
    void nonCompletedTransactionIsRejected() {
        given(bankTransaction.getStatus())
                .willReturn(TransactionStatus.PENDING);

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "22222222222222222222222222222222",
                        "2222222222222222222222222222222211111111111111111111111111111111",
                        customer,
                        bankTransaction
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction status must be COMPLETED");

    }
}
