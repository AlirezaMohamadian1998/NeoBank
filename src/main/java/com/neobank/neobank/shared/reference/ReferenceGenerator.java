package com.neobank.neobank.shared.reference;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ReferenceGenerator {

    public String generate() {
            return UUID.randomUUID()
                    .toString()
                    .replace("-", "");
    }
}
