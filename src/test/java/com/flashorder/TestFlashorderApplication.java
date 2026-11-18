package com.flashorder;

import org.springframework.boot.SpringApplication;

public class TestFlashorderApplication {

	public static void main(String[] args) {
		SpringApplication.from(FlashorderApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
