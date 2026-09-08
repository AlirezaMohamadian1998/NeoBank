package com.neobank.neobank.transaction.history;

import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionType;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class LedgerEntrySpecifications {

    public static Specification<LedgerEntry> belongsToAccount(List<Long> ledgerAccountId) {
        return (root, query, cb) ->
                root.get("ledgerAccount").get("id").in(ledgerAccountId);
    }

    public static Specification<LedgerEntry> hasTransactionType(TransactionType type) {
        return (root, query, cb) -> {
            if(type == null) {
                return cb.conjunction();
            }

            return cb.equal(root.get("bankTransaction").get("transactionType"), type);
        };
    }

    public static Specification<LedgerEntry> hasRequestedCurrency(CurrencyCode currency) {
        return (root, query, criteriaBuilder) -> {
            if(currency == null) {
                return criteriaBuilder.conjunction();
            }

            return criteriaBuilder.equal(root.get("bankTransaction").get("requestedCurrency"), currency);
        };
    }

    public static Specification<LedgerEntry> createdAfterOrEqual(Instant dateFrom) {
        return (root, query, cb) -> {
            if(dateFrom == null) {
                return cb.conjunction();
            }

            return cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom);
        };
    }

    public static Specification<LedgerEntry> createdBefore(Instant dateTo) {
        return (root, query, cb) -> {
            if(dateTo == null) {
                return cb.conjunction();
            }

            return cb.lessThan(root.get("createdAt"), dateTo);
        };
    }

    public static Specification<LedgerEntry> amountIsGreaterThanOrEqual(BigDecimal min) {
        return (root, query, cb) -> {
            if(min == null) {
                return cb.conjunction();
            }

            return cb.greaterThanOrEqualTo(root.get("amount"), min);
        };
    }

    public static Specification<LedgerEntry> amountIsLessThanOrEqual(BigDecimal max) {
        return (root, query, cb) -> {
            if(max == null) {
                return cb.conjunction();
            }

            return cb.lessThanOrEqualTo(root.get("amount"), max);
        };
    }
}
