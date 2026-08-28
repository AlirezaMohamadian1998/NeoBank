package com.neobank.neobank.fx.openexchangerates;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class OpenExchangeRatesConfig {

    @Bean
    RestClient fxRestClient(
            RestClient.Builder builder,
            OpenExchangeRatesProperties properties
    ) {
        var settings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        var requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
