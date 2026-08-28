package com.neobank.neobank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.resilience.annotation.EnableResilientMethods;

@SpringBootApplication
@EnableResilientMethods
@ConfigurationPropertiesScan
public class NeoBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(NeoBankApplication.class, args);
    }

}
