package com.hallucination.detection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CsrHallucinationDetectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CsrHallucinationDetectionApplication.class, args);
    }

}
