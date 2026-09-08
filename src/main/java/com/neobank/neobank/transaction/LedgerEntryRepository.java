package com.neobank.neobank.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long>, JpaSpecificationExecutor<LedgerEntry> {

    @EntityGraph(attributePaths = "bankTransaction")
    Page<LedgerEntry> findAll(Specification<LedgerEntry> spec, Pageable pageable);
}
