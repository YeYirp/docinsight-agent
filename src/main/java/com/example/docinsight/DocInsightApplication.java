package com.example.docinsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocInsightApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DocInsightApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.run(args);
    }
}
