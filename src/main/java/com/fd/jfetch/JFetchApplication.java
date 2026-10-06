package com.fd.jfetch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JFetchApplication {

    public static void main(String[] args) {
        SpringApplication.run(JFetchApplication.class, args);
    }

}
