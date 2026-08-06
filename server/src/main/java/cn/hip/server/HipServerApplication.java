package cn.hip.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "cn.hip")
@EntityScan(basePackages = "cn.hip")
@EnableJpaRepositories(basePackages = "cn.hip")
@EnableScheduling
public class HipServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HipServerApplication.class, args);
    }
}
