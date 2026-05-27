package com.swift.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SwiftPlatformApplication {
    public static void main(String[] args) {

        SpringApplication.run(SwiftPlatformApplication.class, args);
    }
}
