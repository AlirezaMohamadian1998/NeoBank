package com.neobank.neobank.transaction.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionHistorySortHelperTest {

    @Test
    void defaultsToNewestFirstAndPreservesPagination() {
        Pageable result = TransactionHistorySortHelper.normalize(PageRequest.of(2, 25));

        assertThat(result.getPageNumber())
                .isEqualTo(2);

        assertThat(result.getPageSize())
                .isEqualTo(25);

        assertThat(result.getSort())
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @ParameterizedTest
    @CsvSource({
            "createdAt, createdAt, ASC",
            "createdAt, createdAt, DESC",
            "appliedAmount, amount, ASC",
            "appliedAmount, amount, DESC"
    })
    void mapsPublicFieldsAndPreservesDirection(String publicField, String entityField, Sort.Direction direction) {
        Pageable input = PageRequest.of(1, 20, Sort.by(direction, publicField));

        Pageable result = TransactionHistorySortHelper.normalize(input);

        assertThat(result.getPageNumber()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getSort()).containsExactly(new Sort.Order(direction, entityField), Sort.Order.desc("id"));
    }

    @Test
    void rejectsMultipleRequestedSortFields() {
        Pageable input = PageRequest.of(0, 10, Sort.by(
                Sort.Order.asc("appliedAmount"), Sort.Order.desc("createdAt")));

        assertThatThrownBy(() -> TransactionHistorySortHelper.normalize(input))
                .isInstanceOf(InvalidHistorySortException.class)
                .hasMessage("Only one sort field may be requested.");
    }

    @Test
    void rejectsRepeatedSortFields() {
        Pageable input = PageRequest.of(0, 10, Sort.by(
                Sort.Order.asc("createdAt"), Sort.Order.desc("createdAt")));

        assertThatThrownBy(() -> TransactionHistorySortHelper.normalize(input))
                .isInstanceOf(InvalidHistorySortException.class)
                .hasMessage("Only one sort field may be requested.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "id", "bankTransaction.createdAt", "unknown", "CreatedAt"})
    void rejectsFieldsOutsidePublicOptions(String field) {
        Pageable input = PageRequest.of(0, 10, Sort.by(field));

        assertThatThrownBy(() -> TransactionHistorySortHelper.normalize(input))
                .isInstanceOf(InvalidHistorySortException.class);
    }

    @Test
    void rejectsIgnoreCaseSorting() {
        Pageable input = PageRequest.of(0, 10, Sort.by(Sort.Order.asc("createdAt").ignoreCase()));

        assertThatThrownBy(() -> TransactionHistorySortHelper.normalize(input))
                .isInstanceOf(InvalidHistorySortException.class);
    }

    @Test
    void preservesUnpagedRequestsWhileNormalizingSort() {
        Pageable result = TransactionHistorySortHelper.normalize(
                Pageable.unpaged(Sort.by(Sort.Order.asc("appliedAmount"))));

        assertThat(result.isUnpaged()).isTrue();
        assertThat(result.getSort()).containsExactly(Sort.Order.asc("amount"), Sort.Order.desc("id"));
    }
}
