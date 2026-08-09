package com.neobank.neobank.customer;

import com.neobank.neobank.customer.dto.CustomerResponse;
import com.neobank.neobank.customer.dto.RegisterCustomerRequest;
import lombok.experimental.UtilityClass;

@UtilityClass
public class CustomerMapper {
    public static CustomerResponse toResponse(Customer customer) {
        if (customer == null) throw new IllegalArgumentException("Customer cannot be null");

        return new CustomerResponse(
                customer.getEmail(),
                customer.getFullName(),
                customer.getCreatedAt()
        );
    }

    public static Customer toCustomerEntity(RegisterCustomerRequest request, String passwordHash) {
        if (request == null) throw new IllegalArgumentException("Request cannot be null");

        return Customer.createNew(
                request.email(),
                passwordHash,
                request.fullName()
        );
    }
}
