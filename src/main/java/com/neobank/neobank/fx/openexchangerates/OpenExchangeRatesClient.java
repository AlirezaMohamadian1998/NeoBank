package com.neobank.neobank.fx.openexchangerates;

import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.FxProviderUnavailableException;
import com.neobank.neobank.fx.FxRateProvider;
import com.neobank.neobank.shared.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Qualifier("openExchangeRates")
@RequiredArgsConstructor
public class OpenExchangeRatesClient implements FxRateProvider {

    private final RestClient restClient;

    private final OpenExchangeRatesProperties properties;

    private final ObjectMapper objectMapper;

    private static final String SUPPORTED_SYMBOLS = Arrays.stream(CurrencyCode.values())
            .map(currency -> currency.name())
            .collect(Collectors.joining(","));


    @Override
    public FxProviderRates fetchLatestRates() {
        try {
            var response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/latest.json")
                            .queryParam("app_id", properties.appId())
                            .queryParam("symbols", SUPPORTED_SYMBOLS)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            (req, res) -> {
                                OpenExchangeRatesErrorResponse error;
                                try {
                                    error = objectMapper.readValue(
                                            res.getBody(),
                                            OpenExchangeRatesErrorResponse.class
                                    );
                                } catch (JacksonException | IOException e) {
                                    throw new FxProviderUnavailableException(
                                            "Open Exchange Rates returned an error response", e
                                    );
                                }

                                if (error == null) {
                                    throw new FxProviderUnavailableException(
                                            "Open Exchange Rates returned an empty error response"
                                    );
                                }

                                throw new FxProviderUnavailableException(
                                        "Open Exchange Rates returned %s: %s - %s"
                                                .formatted(
                                                        res.getStatusCode(),
                                                        error.message(),
                                                        error.description()
                                                )
                                );
                            }
                    )
                    .body(OpenExchangeRatesResponse.class);

            if (response == null) {
                throw new FxProviderUnavailableException(
                        "Open Exchange Rates returned an empty response"
                );
            }

            return mapResponse(response);

        } catch (RestClientException e) {
            throw new FxProviderUnavailableException(
                    "Open Exchange Rates request failed",
                    e
            );
        }
    }

    private FxProviderRates mapResponse(OpenExchangeRatesResponse response) {
        if (!CurrencyCode.USD.name().equals(response.base())) {
            throw new FxProviderUnavailableException("Provider response must use USD as base currency");
        }

        if (response.rates() == null) {
            throw new FxProviderUnavailableException("Provider response is missing rates");
        }

        try {
            Map<CurrencyCode, BigDecimal> rates = new EnumMap<>(CurrencyCode.class);
            response.rates().forEach((currency, rate) ->
                    rates.put(CurrencyCode.valueOf(currency), rate)
            );

            rates.put(CurrencyCode.USD, BigDecimal.ONE);

            return new FxProviderRates(
                    "openexchangerates",
                    CurrencyCode.USD,
                    rates,
                    Instant.ofEpochSecond(response.timestamp())
            );
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new FxProviderUnavailableException("Provider response contains invalid FX data", e);
        }
    }
}
