package com.seniorinsight.insight.application.porta.saida;

import java.util.Optional;
import java.util.UUID;

import com.seniorinsight.insight.domain.semaforo.Semaforo;

/** Cache do semáforo atual de cada idoso. Ausência no cache não é erro: a fonte da verdade é a tabela {@code score}. */
public interface CacheSemaforo {

    Optional<Semaforo> buscar(UUID idosoId);

    void guardar(UUID idosoId, Semaforo semaforo);

    void remover(UUID idosoId);
}
