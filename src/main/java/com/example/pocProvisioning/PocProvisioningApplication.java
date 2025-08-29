package com.example.pocProvisioning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PocProvisioningApplication {

	public static void main(String[] args) {
		SpringApplication.run(PocProvisioningApplication.class, args);
	}

}
