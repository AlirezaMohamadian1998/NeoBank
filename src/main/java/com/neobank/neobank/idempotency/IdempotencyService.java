package com.neobank.neobank.idempotency;

import lombok.RequiredArgsConstructor;
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
        return idempotencyRepository.save(idempotencyRecord);
    }
}
