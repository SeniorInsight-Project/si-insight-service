package com.seniorinsight.insight.application.porta.saida;

import com.seniorinsight.insight.domain.evento.EventoDominio;

/**
 * Publica eventos de domínio para os outros serviços.
 *
 * <p>Deve ser chamado dentro da transação do caso de uso: o evento só sai se a transação for confirmada
 * (padrão outbox). A entrega é "pelo menos uma vez".
 */
public interface PublicadorEventos {

    void publicar(EventoDominio evento);
}
