package com.seniorinsight.insight.adapters.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seniorinsight.insight.TesteIntegracao;
import com.seniorinsight.insight.application.porta.saida.PublicadorEventos;
import com.seniorinsight.insight.domain.evento.EventoDominio;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Caminho completo: caso de uso grava na outbox, o job agendado publica no RabbitMQ e marca a linha. */
@TesteIntegracao
class RelayOutboxIntegracaoTest {

    @Autowired
    PublicadorEventos publicador;

    @Autowired
    OutboxEventoRepository repositorio;

    @Autowired
    TransactionTemplate transacao;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void publicaNaExchangeSiEventsComRoutingKeyIgualAoTipoDoEventoEMarcaPublicado() throws Exception {
        Queue fila = amqpAdmin.declareQueue();
        amqpAdmin.declareBinding(BindingBuilder.bind(fila)
                .to(new TopicExchange(OutboxConfig.EXCHANGE_EVENTOS))
                .with("insight.score.changed"));
        UUID idosoId = UUID.randomUUID();

        transacao.executeWithoutResult(status -> publicador.publicar(new ScoreAlterado(idosoId, "VERDE")));

        Message mensagem = rabbitTemplate.receive(fila.getName(), Duration.ofSeconds(15).toMillis());
        assertThat(mensagem).as("mensagem publicada pelo relay").isNotNull();
        MessageProperties propriedades = mensagem.getMessageProperties();
        assertThat(propriedades.getReceivedExchange()).isEqualTo("si.events");
        assertThat(propriedades.getReceivedRoutingKey()).isEqualTo("insight.score.changed");
        assertThat(propriedades.getType()).isEqualTo("insight.score.changed");
        assertThat(propriedades.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(propriedades.getAppId()).isEqualTo("insight-service");
        assertThat(propriedades.<String>getHeader("tipo_agregado")).isEqualTo("score");
        assertThat(propriedades.<String>getHeader("agregado_id")).isEqualTo(idosoId.toString());
        assertThat(objectMapper.readTree(mensagem.getBody()))
                .isEqualTo(objectMapper.createObjectNode().put("idosoId", idosoId.toString()).put("faixa", "VERDE"));

        UUID eventoId = UUID.fromString(propriedades.getMessageId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            OutboxEvento evento = repositorio.findById(eventoId).orElseThrow();
            assertThat(evento.getAgregadoId()).isEqualTo(idosoId);
            assertThat(evento.getPublicadoEm()).isNotNull();
            assertThat(evento.getTentativas()).isZero();
        });
    }

    @Test
    void eventoNaoEhGravadoQuandoATransacaoDoCasoDeUsoEhDesfeita() {
        UUID idosoId = UUID.randomUUID();

        transacao.executeWithoutResult(status -> {
            publicador.publicar(new ScoreAlterado(idosoId, "AMARELO"));
            status.setRollbackOnly();
        });

        assertThat(repositorio.findAll()).noneMatch(evento -> evento.getAgregadoId().equals(idosoId));
    }

    @Test
    void publicarExigeTransacaoAtiva() {
        assertThatThrownBy(() -> publicador.publicar(new ScoreAlterado(UUID.randomUUID(), "VERMELHO")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    record ScoreAlterado(UUID idosoId, String faixa) implements EventoDominio {

        @Override
        public String tipoAgregado() {
            return "score";
        }

        @Override
        public UUID agregadoId() {
            return idosoId;
        }

        @Override
        public String tipoEvento() {
            return "insight.score.changed";
        }
    }
}
