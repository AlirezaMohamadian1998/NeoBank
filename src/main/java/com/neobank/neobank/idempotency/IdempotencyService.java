package com.neobank.neobank.idempotency;

import com.neobank.neobank.shared.persistence.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyService {

    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uk_idempotency_records_customer_key";

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
            if(ConstraintViolations.isViolationOf(e, IDEMPOTENCY_KEY_CONSTRAINT)) {
                throw new IdempotencyKeyRaceException(e);
            }

            throw e;
        }
    }
}
