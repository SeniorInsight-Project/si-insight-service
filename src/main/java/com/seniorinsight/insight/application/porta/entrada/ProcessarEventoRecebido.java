package com.seniorinsight.insight.application.porta.entrada;

import java.util.UUID;

/** Trata um evento de outro serviço recebido pela fila {@code insight.q}. */
public interface ProcessarEventoRecebido {

    /** Retorna {@code false} se o evento já tinha sido processado (reentrega) e foi ignorado. */
    boolean processar(EventoRecebido evento);

    /** {@code id} é o id do evento na outbox de origem ({@code message_id} da mensagem). */
    record EventoRecebido(UUID id, String tipo, String payload) {
    }
}
