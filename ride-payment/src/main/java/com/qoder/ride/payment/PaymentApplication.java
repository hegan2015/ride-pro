package com.qoder.ride.payment;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Properties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableDubbo(scanBasePackages = {"com.qoder.ride.payment", "com.qoder.ride.common"})
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    // 按厂商切换语句：MySQL 走 mapper/*.xml 的 databaseId="mysql" 覆盖，H2 走注解默认。
    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties properties = new Properties();
        properties.setProperty("H2", "h2");
        properties.setProperty("MySQL", "mysql");
        provider.setProperties(properties);
        return provider;
    }
}