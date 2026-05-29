package org.stagepass.bookingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.stagepass.bookingservice.config.FeignConfig;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "org.stagepass.bookingservice.client", defaultConfiguration = FeignConfig.class)
@EnableScheduling
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }

}
