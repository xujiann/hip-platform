package cn.hip.bureau;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "cn.hip")
@EntityScan(basePackages = "cn.hip")
@EnableJpaRepositories(basePackages = "cn.hip")
public class BureauServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BureauServerApplication.class, args);
    }
}
