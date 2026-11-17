package com.qoder.ride.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** 注册中心进程：内嵌 ZooKeeper，供全部 Dubbo 服务注册/发现。 */
@SpringBootApplication
public class RegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(RegistryApplication.class, args);
    }

    @Bean
    public EmbeddedZooKeeper embeddedZooKeeper() {
        return new EmbeddedZooKeeper();
    }
}