package com.seniorinsight.insight.adapters.in.mensageria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import com.seniorinsight.insight.TesteIntegracao;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mensagens publicadas na {@code si.events} chegam à {@code insight.q} e são processadas uma única vez. */
@TesteIntegracao
class ConsumidorEventosIntegracaoTest {

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void processaOEventoEIgnoraReentregasComOMesmoId() {
        UUID eventoId = UUID.randomUUID();

        publicar("telemetry.ingested", eventoId.toString());
        publicar("telemetry.ingested", eventoId.toString());
        publicar("telemetry.ingested", eventoId.toString());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(quantidadeRegistrada(eventoId)).isEqualTo(1));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(quantidadeRegistrada(eventoId)).isEqualTo(1);
            assertThat(mensagensNaFila(MensageriaConfig.FILA)).isZero();
        });
        assertThat(jdbc.queryForObject("SELECT tipo_evento FROM evento_recebido WHERE id = ?", String.class, eventoId))
                .isEqualTo("telemetry.ingested");
    }

    @Test
    void mensagemSemIdValidoVaiParaADeadLetter() {
        publicar("consent.changed", "nao-eh-uuid");

        Message rejeitada = rabbitTemplate.receive(MensageriaConfig.FILA_DEAD_LETTER, Duration.ofSeconds(10).toMillis());

        assertThat(rejeitada).as("mensagem na insight.q.dlq").isNotNull();
        assertThat(rejeitada.getMessageProperties().getMessageId()).isEqualTo("nao-eh-uuid");
        assertThat(rejeitada.getMessageProperties().<Object>getHeader("x-first-death-queue")).hasToString("insight.q");
    }

    private void publicar(String tipo, String messageId) {
        Message mensagem = MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(messageId)
                .setType(tipo)
                .build();
        rabbitTemplate.send(MensageriaConfig.EXCHANGE_EVENTOS, tipo, mensagem);
    }

    private int quantidadeRegistrada(UUID eventoId) {
        return jdbc.queryForObject("SELECT count(*) FROM evento_recebido WHERE id = ?", Integer.class, eventoId);
    }

    private int mensagensNaFila(String fila) {
        return rabbitTemplate.execute(canal -> (int) canal.messageCount(fila));
    }
}
