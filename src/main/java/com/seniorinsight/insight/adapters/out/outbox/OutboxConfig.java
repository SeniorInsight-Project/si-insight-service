package com.seniorinsight.insight.adapters.out.outbox;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxConfig {

    /** Exchange topic compartilhada pelos serviços do SeniorInsight. Declarada (idempotente) na conexão. */
    public static final String EXCHANGE_EVENTOS = "si.events";

    @Bean
    TopicExchange exchangeEventos() {
        return ExchangeBuilder.topicExchange(EXCHANGE_EVENTOS).durable(true).build();
    }
}
