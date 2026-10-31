package com.seniorinsight.insight.adapters.out.outbox;

import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seniorinsight.insight.application.porta.saida.PublicadorEventos;
import com.seniorinsight.insight.domain.evento.EventoDominio;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava o evento na {@code outbox_evento} dentro da transação de quem chamou; o {@link RelayOutbox} publica depois.
 * Exige transação ativa para que o evento nunca seja gravado sem a mudança de estado que o originou.
 */
@Component
class PublicadorEventosOutbox implements PublicadorEventos {

    private final OutboxEventoRepository repositorio;
    private final ObjectMapper objectMapper;

    PublicadorEventosOutbox(OutboxEventoRepository repositorio, ObjectMapper objectMapper) {
        this.repositorio = repositorio;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicar(EventoDominio evento) {
        repositorio.save(new OutboxEvento(
                evento.tipoAgregado(),
                evento.agregadoId(),
                evento.tipoEvento(),
                serializar(evento),
                Instant.now()));
    }

    private String serializar(EventoDominio evento) {
        try {
            return objectMapper.writeValueAsString(evento);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Não foi possível serializar o evento " + evento.tipoEvento(), e);
        }
    }
}
