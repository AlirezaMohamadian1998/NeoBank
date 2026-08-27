package com.neobank.neobank.fx;

import com.neobank.neobank.fx.dto.SnapshotRateResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fx/rates")
public class FxRateController {

    private final FxRateService fxRateService;

    @GetMapping
    public ResponseEntity<SnapshotRateResponse> getLatestRates(@RequestParam(name = "base") CurrencyCode baseCurrency) {
        return ResponseEntity.status(HttpStatus.OK).body(fxRateService.getLatestRates(baseCurrency));
    }
}
