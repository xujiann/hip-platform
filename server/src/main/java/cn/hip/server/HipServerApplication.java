package cn.hip.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "cn.hip")
@EntityScan(basePackages = "cn.hip")
@EnableJpaRepositories(basePackages = "cn.hip")
public class HipServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HipServerApplication.class, args);
    }
}
