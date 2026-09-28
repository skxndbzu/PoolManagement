package com.poolguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PoolGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(PoolGuardApplication.class, args);
    }
}
