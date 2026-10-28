package com.example.docinsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocInsightApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocInsightApplication.class, args);
    }
}
