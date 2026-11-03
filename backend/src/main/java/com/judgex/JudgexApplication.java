package com.judgex;

import com.judgex.config.JudgexProps;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(JudgexProps.class)
public class JudgexApplication {
    public static void main(String[] args) {
        SpringApplication.run(JudgexApplication.class, args);
    }
}
