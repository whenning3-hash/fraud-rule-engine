package za.co.fraudruleengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class FraudRuleEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudRuleEngineApplication.class, args);
    }
}
