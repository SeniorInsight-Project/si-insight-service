package com.seniorinsight.insight.adapters.out.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publica na exchange {@code si.events} os eventos pendentes da outbox, usando o tipo do evento como routing key.
 *
 * <p>Cada execução trava um lote com {@code FOR UPDATE SKIP LOCKED}, envia tudo, espera os publisher confirms e só
 * então marca {@code publicado_em}. Se o broker não confirmar, o evento continua pendente e {@code tentativas} é
 * incrementado. Se a transação falhar depois do confirm, o evento sai de novo na próxima execução: a entrega é "pelo
 * menos uma vez" e os consumidores devem deduplicar pelo {@code message_id} (= id da linha na outbox).
 */
@Component
class RelayOutbox {

    static final String APP_ID = "insight-service";

    private static final Logger log = LoggerFactory.getLogger(RelayOutbox.class);

    private final OutboxEventoRepository repositorio;
    private final RabbitTemplate rabbitTemplate;
    private final int tamanhoLote;
    private final Duration timeoutConfirmacao;

    RelayOutbox(OutboxEventoRepository repositorio,
                RabbitTemplate rabbitTemplate,
                @Value("${insight.outbox.relay.tamanho-lote}") int tamanhoLote,
                @Value("${insight.outbox.relay.timeout-confirmacao}") Duration timeoutConfirmacao) {
        this.repositorio = repositorio;
        this.rabbitTemplate = rabbitTemplate;
        this.tamanhoLote = tamanhoLote;
        this.timeoutConfirmacao = timeoutConfirmacao;
    }

    @Scheduled(fixedDelayString = "${insight.outbox.relay.intervalo}")
    @Transactional
    public void publicarPendentes() {
        List<OutboxEvento> pendentes = repositorio.travarPendentes(tamanhoLote);
        if (pendentes.isEmpty()) {
            return;
        }

        List<Envio> enviados = new ArrayList<>(pendentes.size());
        for (OutboxEvento evento : pendentes) {
            CorrelationData confirmacao = new CorrelationData(evento.getId().toString());
            try {
                rabbitTemplate.send(OutboxConfig.EXCHANGE_EVENTOS, evento.getTipoEvento(), paraMensagem(evento),
                        confirmacao);
                enviados.add(new Envio(evento, confirmacao));
            } catch (AmqpException e) {
                // Falha no envio é de conexão: o resto do lote fica pendente para o próximo ciclo.
                registrarFalha(evento, e.getMessage());
                break;
            }
        }

        long prazo = System.nanoTime() + timeoutConfirmacao.toNanos();
        int publicados = 0;
        for (Envio envio : enviados) {
            if (aguardarConfirmacao(envio, prazo)) {
                publicados++;
            }
        }
        log.debug("Outbox: {} de {} eventos publicados", publicados, pendentes.size());
    }

    private boolean aguardarConfirmacao(Envio envio, long prazo) {
        OutboxEvento evento = envio.evento();
        try {
            long restante = Math.max(0, prazo - System.nanoTime());
            CorrelationData.Confirm confirm = envio.confirmacao().getFuture().get(restante, TimeUnit.NANOSECONDS);
            if (confirm.isAck()) {
                evento.marcarPublicado(Instant.now());
                return true;
            }
            registrarFalha(evento, "nack do broker: " + confirm.getReason());
        } catch (TimeoutException e) {
            registrarFalha(evento, "sem confirmação do broker em " + timeoutConfirmacao);
        } catch (ExecutionException e) {
            registrarFalha(evento, e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            registrarFalha(evento, "relay interrompido");
        }
        return false;
    }

    private void registrarFalha(OutboxEvento evento, String motivo) {
        evento.registrarFalha();
        log.warn("Outbox: falha ao publicar o evento {} ({}), tentativa {}: {}",
                evento.getId(), evento.getTipoEvento(), evento.getTentativas(), motivo);
    }

    private static Message paraMensagem(OutboxEvento evento) {
        return MessageBuilder.withBody(evento.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(evento.getId().toString())
                .setType(evento.getTipoEvento())
                .setTimestamp(Date.from(evento.getCriadoEm()))
                .setAppId(APP_ID)
                .setHeader("tipo_agregado", evento.getTipoAgregado())
                .setHeader("agregado_id", evento.getAgregadoId().toString())
                .build();
    }

    private record Envio(OutboxEvento evento, CorrelationData confirmacao) {
    }
}
