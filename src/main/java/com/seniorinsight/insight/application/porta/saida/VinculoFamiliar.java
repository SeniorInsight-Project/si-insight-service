package com.seniorinsight.insight.application.porta.saida;

import java.util.UUID;

public interface VinculoFamiliar {
    boolean confirmado(UUID idosoId, String sub, String token);

    class Indisponivel extends RuntimeException {
        public Indisponivel() {
            super("Verificação do vínculo indisponível");
        }
    }
}
