package com.techeazy.notification.dispatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.techeazy.notification")
@EnableConfigurationProperties(DispatcherProperties.class)
public class DispatcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }
}
