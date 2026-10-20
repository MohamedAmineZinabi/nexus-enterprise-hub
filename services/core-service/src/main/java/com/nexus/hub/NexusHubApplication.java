package com.nexus.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class NexusHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusHubApplication.class, args);
    }
}
