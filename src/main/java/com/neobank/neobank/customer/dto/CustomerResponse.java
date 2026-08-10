package com.neobank.neobank.customer.dto;

import java.time.Instant;

public record CustomerResponse(
        String email,
        String fullName,
        Instant createdAt
) {
}
