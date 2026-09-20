package com.neobank.neobank.idempotency;

import com.neobank.neobank.customer.Customer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class IdempotencyRecordTest {

    @Mock
    private Customer customer;

    @Test
    void validCreationPreservesAllFields() {
        String resultReference = "1".repeat(32);

        var idempotencyRecord = IdempotencyRecord.createNew(
                "22222222222222222222222222222222",
                "2222222222222222222222222222222211111111111111111111111111111111",
                customer,
                resultReference
        );


        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo("22222222222222222222222222222222");
        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo("2222222222222222222222222222222211111111111111111111111111111111");
        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);
        assertThat(idempotencyRecord.getResultReference())
                .isEqualTo(resultReference);
    }

    @Test
    void wrongKeyFormatIsRejected() {
        String resultReference = "1".repeat(32);

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "222222222222222222!22222222222222",
                        "2222222222222222222222222222222211111111111111111111111111111111",
                        customer,
                        resultReference
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Idempotency key must contain only hex characters [a-f, 0-9] and be exactly 32 characters long");
    }

    @Test
    void nullKeyIsRejected() {
        String resultReference = "1".repeat(32);

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        null,
                        "2222222222222222222222222222222211111111111111111111111111111111",
                        customer,
                        resultReference
                ))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullCustomerIsRejected() {
        String resultReference = "1".repeat(32);

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "22222222222222222222222222222222",
                        "2222222222222222222222222222222211111111111111111111111111111111",
                        null,
                        resultReference
                ))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullResultReferenceIsRejected() {
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
        String resultReference = "1".repeat(32);

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "22222222222222222222222222222222",
                        null,
                        customer,
                        resultReference
                ))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void wrongRequestHashFormatIsRejected() {
        String resultReference = "1".repeat(32);

        assertThatThrownBy(
                () -> IdempotencyRecord.createNew(
                        "22222222222222222222222222222222",
                        "22222222222222222222222222222222!?11111111111111111111111111111111",
                        customer,
                        resultReference
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request hash must contain only hex characters [a-f, 0-9] and be exactly 64 characters long");
    }
}
