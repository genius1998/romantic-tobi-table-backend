package com.tomy.tomy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class TomyApplication {

	public static void main(String[] args) {
		SpringApplication.run(TomyApplication.class, args);
	}

}

