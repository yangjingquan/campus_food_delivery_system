package com.campusfood.rider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RiderApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiderApiApplication.class, args);
    }
}
