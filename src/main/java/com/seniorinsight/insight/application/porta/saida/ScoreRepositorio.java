package com.seniorinsight.insight.application.porta.saida;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.seniorinsight.insight.domain.semaforo.MedidasScore.Agregado;
import com.seniorinsight.insight.domain.semaforo.Semaforo;

public interface ScoreRepositorio {
    Optional<Trabalho> reservar(Instant agora);
    Contexto contexto(UUID idosoId);
    Optional<Score> recente(UUID idosoId);
    Optional<Prompt> promptAprovado(String modelo);
    Optional<UUID> reservarChamada(UUID idosoId, UUID promptId, String modelo, long teto, long reserva);
    void registrarChamada(UUID id, String modelo, String estado, int entrada, int saida, int latencia, long custo);
    void guardar(UUID idosoId, Score score, UUID chamada, String hash);
    void liberar(UUID idosoId);
    boolean premium(String familiarSub);
    void bloquear(UUID idosoId);

    record Trabalho(UUID idosoId, long revisao) {
    }

    record Contexto(long revisao, Set<String> tipos, List<Agregado> agregados) {
    }

    record Prompt(UUID id, String conteudo) {
    }

    record Score(double valor, Semaforo faixa, Instant calculadoEm, List<String> fatores, long revisao) {
    }
}
