package com.neobank.neobank.transaction.history;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TransactionHistorySortHelper {

    public static Pageable normalize(Pageable pageable) {
        Sort requestedSort = pageable.getSort().isSorted()
                ? pageable.getSort()
                : Sort.by(Sort.Order.desc("createdAt"));

        List<Sort.Order> orders = requestedSort.toList();
        if (orders.size() != 1) {
            throw new InvalidHistorySortException("Only one sort field may be requested.");
        }

        Sort.Order order = orders.getFirst();
        String entityProperty = switch (order.getProperty()) {
            case "createdAt" -> "createdAt";
            case "appliedAmount" -> "amount";
            default -> throw new InvalidHistorySortException("Supported sort fields are createdAt and appliedAmount.");
        };

        if (order.isIgnoreCase()) {
            throw new InvalidHistorySortException("Ignore-case sorting is not supported for history.");
        }

        Sort sort = Sort.by(order.withProperty(entityProperty), Sort.Order.desc("id"));

        return pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort)
                : Pageable.unpaged(sort);
    }
}
