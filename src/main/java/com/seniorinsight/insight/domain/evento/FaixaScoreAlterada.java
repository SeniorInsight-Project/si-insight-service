package com.seniorinsight.insight.domain.evento;

import java.time.Instant;
import java.util.UUID;

import com.seniorinsight.insight.domain.semaforo.Semaforo;

public record FaixaScoreAlterada(UUID idosoId, Semaforo faixaAnterior, Semaforo faixa, Instant calculadoEm)
        implements EventoDominio {
    @Override public String tipoAgregado() { return "score"; }
    @Override public UUID agregadoId() { return idosoId; }
    @Override public String tipoEvento() { return "insight.score.changed"; }
}
