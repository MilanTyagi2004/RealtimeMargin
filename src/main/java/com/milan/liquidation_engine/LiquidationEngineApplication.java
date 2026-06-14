package com.milan.liquidation_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class LiquidationEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(LiquidationEngineApplication.class, args);
	}

}
