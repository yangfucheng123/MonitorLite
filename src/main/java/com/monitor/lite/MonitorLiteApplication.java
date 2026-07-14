package com.monitor.lite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MonitorLiteApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonitorLiteApplication.class, args);
    }
}
