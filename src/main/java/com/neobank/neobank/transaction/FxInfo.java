package com.neobank.neobank.transaction;

import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Entity
@Getter
@Table(name = "fx_info")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FxInfo extends BaseEntity {

    @Column(nullable = false, updatable = false, unique = true, length = 36)
    private String lockId;

    @ElementCollection
    @CollectionTable(
            name = "fx_rates",
            joinColumns = @JoinColumn(name = "fx_info_id"),
            uniqueConstraints = @UniqueConstraint(
                    columnNames = {"fx_info_id", "currency_context"}
            )
    )
    private Set<FxRate> rates = new HashSet<>();

    public Set<FxRate> getRates() {
        return Set.copyOf(rates);
    }

    public static FxInfo createNew(
            String lockId,
            Set<FxRate> rates
    ) {
        if(lockId == null) {
            throw new IllegalArgumentException("Lock ID must not be null");
        }

        if(lockId.isBlank()) {
            throw new IllegalArgumentException("Lock ID cannot be blank.");
        }

        if(rates == null) {
            throw new IllegalArgumentException("Rates must not be null");
        }

        if(rates.size() != 3) {
            throw new IllegalArgumentException("FX rates must have exactly 3 elements, one for each REQUEST, SOURCE and DESTINATION");
        }

        rates.forEach(fxRate -> {
            if(fxRate == null) {
                throw new IllegalArgumentException("None of the elements inside rates can be null");
            }
        });

        if(!rates.stream()
                .filter(fxRate -> fxRate.getCurrencyContext() == CurrencyContext.REQUEST)
                .map(fxRate -> fxRate.getRate().compareTo(BigDecimal.ONE) == 0)
                .findFirst()
                .orElseThrow()
        ) {
            throw new IllegalArgumentException("Fx rate for REQUEST must be equal to 1");
        }

        return new FxInfo(lockId, new HashSet<>(rates));
    }

    public BigDecimal getRate(CurrencyContext context) {
        return rates.stream()
                .filter(fxRate -> fxRate.getCurrencyContext() == context)
                .map(fxRate -> fxRate.getRate())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing FX rate for context: " + context));
    }
}
