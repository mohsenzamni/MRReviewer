package com.mohsenzamni.mrreviewer;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class MrReviewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MrReviewerApplication.class, args);
    }
}
