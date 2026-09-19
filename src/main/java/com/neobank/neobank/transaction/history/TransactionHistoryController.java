package com.neobank.neobank.transaction.history;

import com.neobank.neobank.transaction.history.dto.TransactionHistoryFilter;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/transactions")
public class TransactionHistoryController {

    private final TransactionHistoryService transactionHistoryService;

    @GetMapping
    public ResponseEntity<PagedModel<TransactionHistoryResponse>> getHistory(
            @Valid @ModelAttribute TransactionHistoryFilter filter,
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {
        Page<TransactionHistoryResponse> page = transactionHistoryService.getHistoryByAccount(
                filter,
                jwt.getSubject(),
                pageable
        );

        return ResponseEntity.ok(new PagedModel<>(page));
    }
}
