package com.seniorinsight.insight.adapters.in.mensageria;

import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fila {@code insight.q} ligada à exchange {@code si.events} pelos eventos que o serviço consome. Mensagem rejeitada
 * vai para a exchange de dead-letter {@code insight.dlx} e fica na fila {@code insight.q.dlq} para análise.
 */
@Configuration(proxyBeanMethods = false)
public class MensageriaConfig {

    public static final String EXCHANGE_EVENTOS = "si.events";
    public static final String FILA = "insight.q";
    public static final String EXCHANGE_DEAD_LETTER = "insight.dlx";
    public static final String FILA_DEAD_LETTER = "insight.q.dlq";

    /** Routing keys consumidas da {@code si.events}. */
    static final List<String> EVENTOS_CONSUMIDOS = List.of("telemetry.ingested", "consent.changed", "subscription.changed");

    @Bean
    Declarables filaInsight() {
        TopicExchange eventos = ExchangeBuilder.topicExchange(EXCHANGE_EVENTOS).durable(true).build();
        DirectExchange deadLetter = ExchangeBuilder.directExchange(EXCHANGE_DEAD_LETTER).durable(true).build();
        Queue fila = QueueBuilder.durable(FILA)
                .deadLetterExchange(EXCHANGE_DEAD_LETTER)
                .deadLetterRoutingKey(FILA_DEAD_LETTER)
                .build();
        Queue filaDeadLetter = QueueBuilder.durable(FILA_DEAD_LETTER).build();

        List<Declarable> declaraveis = new ArrayList<>(List.of(eventos, deadLetter, fila, filaDeadLetter,
                BindingBuilder.bind(filaDeadLetter).to(deadLetter).with(FILA_DEAD_LETTER)));
        EVENTOS_CONSUMIDOS.forEach(evento -> declaraveis.add(BindingBuilder.bind(fila).to(eventos).with(evento)));
        return new Declarables(declaraveis);
    }
}
