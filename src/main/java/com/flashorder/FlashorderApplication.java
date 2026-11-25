package com.flashorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class FlashorderApplication {

	public static void main(String[] args) {
		SpringApplication.run(FlashorderApplication.class, args);
	}

}
