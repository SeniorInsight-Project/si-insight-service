package com.seniorinsight.insight.adapters.in.mensageria;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.rabbitmq.client.Channel;
import com.seniorinsight.insight.application.porta.entrada.ProcessarEventoRecebido;
import com.seniorinsight.insight.application.porta.entrada.ProcessarEventoRecebido.EventoRecebido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome a fila {@code insight.q} com ack manual. O ack só sai depois do commit do processamento; reentregas são
 * reconhecidas pelo {@code message_id} (id do evento na outbox de origem) e confirmadas sem efeito. Mensagem sem id
 * válido ou que falha no processamento recebe nack sem requeue e vai para a dead-letter {@code insight.q.dlq}.
 */
@Component
class ConsumidorEventos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorEventos.class);

    private final ProcessarEventoRecebido processar;

    ConsumidorEventos(ProcessarEventoRecebido processar) {
        this.processar = processar;
    }

    @RabbitListener(queues = MensageriaConfig.FILA, ackMode = "MANUAL")
    void receber(Message mensagem, Channel canal) throws IOException {
        MessageProperties propriedades = mensagem.getMessageProperties();
        long tag = propriedades.getDeliveryTag();
        try {
            processar.processar(paraEvento(propriedades, mensagem.getBody()));
            canal.basicAck(tag, false);
        } catch (RuntimeException e) {
            log.error("Evento {} ({}) enviado para a dead-letter: {}",
                    propriedades.getMessageId(), propriedades.getReceivedRoutingKey(), e.getMessage(), e);
            canal.basicNack(tag, false, false);
        }
    }

    static EventoRecebido paraEvento(MessageProperties propriedades, byte[] corpo) {
        String id = propriedades.getMessageId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("mensagem sem message_id");
        }
        String tipo = propriedades.getType() != null ? propriedades.getType() : propriedades.getReceivedRoutingKey();
        return new EventoRecebido(UUID.fromString(id), tipo, new String(corpo, StandardCharsets.UTF_8));
    }
}
