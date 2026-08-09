package com.neobank.neobank.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterCustomerRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 12, max = 128)
        String password,

        @NotBlank
        @Size(max = 120)
        String fullName
){}
