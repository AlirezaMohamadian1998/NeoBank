package com.neobank.neobank.fx.openexchangerates;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Map;

public record OpenExchangeRatesResponse(
        @JsonProperty("timestamp")
        long timestamp,

        @JsonProperty("base")
        String base,

        @JsonProperty("rates")
        Map<String, BigDecimal> rates
) {
}
