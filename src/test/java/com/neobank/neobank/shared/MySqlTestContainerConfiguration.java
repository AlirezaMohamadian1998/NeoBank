package com.neobank.neobank.shared;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestContainerConfiguration {

    @Bean
    @ServiceConnection
    public MySQLContainer mysql() {
        return new MySQLContainer("mysql:latest");
    }
}
