package com.seniorinsight.insight.domain.evento;

import java.util.UUID;

/**
 * Fato relevante do domínio que deve ser propagado para os outros serviços.
 *
 * <p>Os campos do próprio evento (componentes do record) formam o payload JSON publicado. Os métodos desta
 * interface são metadados: identificam o agregado de origem e o tipo do evento, que vira a routing key na
 * exchange {@code si.events}.
 */
public interface EventoDominio {

    /** Tipo do agregado de origem, ex.: {@code score}. */
    String tipoAgregado();

    /** Identificador do agregado de origem. */
    UUID agregadoId();

    /** Tipo do evento no formato {@code <entidade>.<acao>}, ex.: {@code insight.score.changed}. */
    String tipoEvento();
}
