package com.qoder.ride.passenger;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.ride.passenger", "com.qoder.ride.common"})
public class PassengerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PassengerApplication.class, args);
    }
}