package com.neobank.neobank.fx.openexchangerates;

import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.FxProviderUnavailableException;
import com.neobank.neobank.shared.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(
        value = OpenExchangeRatesClient.class,
        properties = {
                "fx.provider.base-url=https://openexchangerates.test",
                "fx.provider.app-id=test-app-id",
                "fx.provider.connect-timeout=2s",
                "fx.provider.read-timeout=5s"
        }
)
@EnableConfigurationProperties(OpenExchangeRatesProperties.class)
@Import(OpenExchangeRatesClientTest.RestClientConfiguration.class)
class OpenExchangeRatesClientTest {

    private static final long PROVIDER_TIMESTAMP = 1_787_731_200L;

    private final OpenExchangeRatesClient client;
    private final MockRestServiceServer server;

    @Autowired
    OpenExchangeRatesClientTest(
            OpenExchangeRatesClient client,
            MockRestServiceServer server
    ) {
        this.client = client;
        this.server = server;
    }

    @AfterEach
    void verifyRequest() {
        server.verify();
    }

    @Test
    void fetchLatestRatesRequestsAndMapsProviderRates() {
        server.expect(requestTo("https://openexchangerates.test/api/latest.json?app_id=test-app-id&symbols=USD,EUR,GBP,TRY"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(queryParam("app_id", "test-app-id"))
                .andExpect(queryParam("symbols", "USD,EUR,GBP,TRY"))
                .andRespond(withSuccess(successfulResponse(), MediaType.APPLICATION_JSON));

        FxProviderRates result = client.fetchLatestRates();

        assertThat(result.provider()).isEqualTo("openexchangerates");
        assertThat(result.baseCurrency()).isEqualTo(CurrencyCode.USD);
        assertThat(result.providerTimestamp()).isEqualTo(Instant.ofEpochSecond(PROVIDER_TIMESTAMP));
        assertThat(result.rates()).hasSize(4);
        assertThat(result.rates().get(CurrencyCode.USD)).isEqualByComparingTo("1");
        assertThat(result.rates().get(CurrencyCode.EUR)).isEqualByComparingTo("0.86");
        assertThat(result.rates().get(CurrencyCode.GBP)).isEqualByComparingTo("0.74");
        assertThat(result.rates().get(CurrencyCode.TRY)).isEqualByComparingTo("48.00");
    }

    @Test
    void fetchLatestRatesRejectsMissingSupportedCurrency() {
        server.expect(requestTo("https://openexchangerates.test/api/latest.json?app_id=test-app-id&symbols=USD,EUR,GBP,TRY"))
                .andRespond(withSuccess(responseWithoutTry(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchLatestRates())
                .isInstanceOf(FxProviderUnavailableException.class)
                .hasMessage("Provider response contains invalid FX data")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchLatestRatesRejectsUnexpectedBaseCurrency() {
        server.expect(requestTo("https://openexchangerates.test/api/latest.json?app_id=test-app-id&symbols=USD,EUR,GBP,TRY"))
                .andRespond(withSuccess(responseWithUnexpectedBase(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchLatestRates())
                .isInstanceOf(FxProviderUnavailableException.class)
                .hasMessage("Provider response must use USD as base currency");
    }

    @Test
    void fetchLatestRatesTranslatesProviderError() {
        server.expect(requestTo("https://openexchangerates.test/api/latest.json?app_id=test-app-id&symbols=USD,EUR,GBP,TRY"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "error": true,
                                  "status": 429,
                                  "message": "not_allowed",
                                  "description": "Too many requests"
                                }
                                """));

        assertThatThrownBy(() -> client.fetchLatestRates())
                .isInstanceOf(FxProviderUnavailableException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS")
                .hasMessageContaining("not_allowed")
                .hasMessageContaining("Too many requests");
    }

    @Test
    void fetchLatestRatesTranslatesMalformedResponse() {
        server.expect(requestTo("https://openexchangerates.test/api/latest.json?app_id=test-app-id&symbols=USD,EUR,GBP,TRY"))
                .andRespond(withSuccess("{not-json}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchLatestRates())
                .isInstanceOf(FxProviderUnavailableException.class)
                .hasMessage("Open Exchange Rates request failed")
                .hasCauseInstanceOf(RestClientException.class);
    }

    private String successfulResponse() {
        return """
                {
                  "timestamp": %d,
                  "base": "USD",
                  "rates": {
                    "EUR": 0.86,
                    "GBP": 0.74,
                    "TRY": 48.00
                  }
                }
                """.formatted(PROVIDER_TIMESTAMP);
    }

    private String responseWithoutTry() {
        return """
                {
                  "timestamp": %d,
                  "base": "USD",
                  "rates": {
                    "EUR": 0.86,
                    "GBP": 0.74
                  }
                }
                """.formatted(PROVIDER_TIMESTAMP);
    }

    private String responseWithUnexpectedBase() {
        return """
                {
                  "timestamp": %d,
                  "base": "EUR",
                  "rates": {
                    "EUR": 0.86,
                    "GBP": 0.74,
                    "TRY": 48.00
                  }
                }
                """.formatted(PROVIDER_TIMESTAMP);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestClientConfiguration {

        @Bean
        RestClient fxRestClient(
                RestClient.Builder builder,
                OpenExchangeRatesProperties properties
        ) {
            return builder
                    .baseUrl(properties.baseUrl().toString())
                    .build();
        }
    }
}
