package com.qoder.ride.push;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableDubbo(scanBasePackages = {"com.qoder.ride.push", "com.qoder.ride.common"})
public class PushApplication {

    public static void main(String[] args) {
        SpringApplication.run(PushApplication.class, args);
    }
}