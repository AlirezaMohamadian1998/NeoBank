package com.neobank.neobank.idempotency;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRepository;

    public Optional<IdempotencyRecord> findAndValidateRecord(String key, String email,  String requestHash) {
        var record = idempotencyRepository.findByIdempotencyKeyAndCustomer_EmailIgnoreCase(key, email);

        if(record.isPresent() && !record.get().getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("request hash mismatch");

        }
        return record;
    }

    public IdempotencyRecord save(IdempotencyRecord idempotencyRecord) {
        try {
            return idempotencyRepository.saveAndFlush(idempotencyRecord);
        } catch (DataIntegrityViolationException e) {
            if(hasIdempotencyConstraint(e)) {
                throw new IdempotencyKeyRaceException(e);
            }

            throw e;
        }
    }

    private boolean hasIdempotencyConstraint(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return "uk_idempotency_records_customer_key".equalsIgnoreCase(
                        violation.getConstraintName()
                );
            }

            current = current.getCause();
        }

        return false;
    }
}
