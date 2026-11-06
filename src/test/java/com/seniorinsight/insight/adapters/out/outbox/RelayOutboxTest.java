package com.seniorinsight.insight.adapters.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** Desfechos do publisher confirm, sem broker: o caminho feliz com RabbitMQ real está no teste de integração. */
class RelayOutboxTest {

    private final OutboxEventoRepository repositorio = mock(OutboxEventoRepository.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RelayOutbox relay = new RelayOutbox(repositorio, rabbitTemplate, 100, Duration.ofMillis(200));

    private OutboxEvento evento;

    @BeforeEach
    void eventoPendente() {
        evento = new OutboxEvento("score", UUID.randomUUID(), "insight.score.changed", "{}", Instant.now());
        ReflectionTestUtils.setField(evento, "id", UUID.randomUUID());
        when(repositorio.travarPendentes(100)).thenReturn(List.of(evento));
    }

    @Test
    void marcaPublicadoQuandoOBrokerConfirma() {
        brokerResponde(new CorrelationData.Confirm(true, null));

        relay.publicarPendentes();

        assertThat(evento.getPublicadoEm()).isNotNull();
        assertThat(evento.getTentativas()).isZero();
    }

    @Test
    void incrementaTentativasQuandoOBrokerRecusa() {
        brokerResponde(new CorrelationData.Confirm(false, "nack"));

        relay.publicarPendentes();

        assertThat(evento.getPublicadoEm()).isNull();
        assertThat(evento.getTentativas()).isEqualTo(1);
    }

    @Test
    void incrementaTentativasQuandoNaoHaConfirmacaoNoPrazo() {
        relay.publicarPendentes();

        assertThat(evento.getPublicadoEm()).isNull();
        assertThat(evento.getTentativas()).isEqualTo(1);
    }

    @Test
    void incrementaTentativasQuandoOBrokerEstaFora() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.publicarPendentes();
        relay.publicarPendentes();

        assertThat(evento.getPublicadoEm()).isNull();
        assertThat(evento.getTentativas()).isEqualTo(2);
    }

    @Test
    void interrompeOLoteQuandoOBrokerEstaFora() {
        OutboxEvento seguinte = new OutboxEvento("score", UUID.randomUUID(), "insight.score.changed", "{}", Instant.now());
        ReflectionTestUtils.setField(seguinte, "id", UUID.randomUUID());
        when(repositorio.travarPendentes(100)).thenReturn(List.of(evento, seguinte));
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.publicarPendentes();

        assertThat(evento.getTentativas()).isEqualTo(1);
        assertThat(seguinte.getTentativas()).isZero();
        verify(rabbitTemplate, times(1))
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private void brokerResponde(CorrelationData.Confirm confirm) {
        doAnswer(invocacao -> {
            invocacao.<CorrelationData>getArgument(3).getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
