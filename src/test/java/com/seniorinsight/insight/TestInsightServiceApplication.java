package com.seniorinsight.insight;

import org.springframework.boot.SpringApplication;

/** Sobe o serviço localmente com PostgreSQL, RabbitMQ e Redis do Testcontainers: {@code mvn spring-boot:test-run}. */
public class TestInsightServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(InsightServiceApplication::main).with(ContainersConfig.class).run(args);
    }
}
