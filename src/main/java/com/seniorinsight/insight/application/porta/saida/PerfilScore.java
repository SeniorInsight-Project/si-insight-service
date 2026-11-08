package com.seniorinsight.insight.application.porta.saida;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PerfilScore {
    Optional<Perfil> buscar(UUID idosoId);

    record Perfil(int idade, List<String> comorbidades) {
    }
}
