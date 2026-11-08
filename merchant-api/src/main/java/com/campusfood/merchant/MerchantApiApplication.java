package com.campusfood.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MerchantApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(MerchantApiApplication.class, args);
    }
}
