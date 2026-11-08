package com.seniorinsight.insight.domain.evento;

import java.util.UUID;

public record ExclusaoConcluida(UUID protocolo, String servico) implements EventoDominio {

    public static final String TIPO = "lgpd.erasure.completed";

    @Override
    public String tipoAgregado() {
        return "pedido_exclusao";
    }

    @Override
    public UUID agregadoId() {
        return protocolo;
    }

    @Override
    public String tipoEvento() {
        return TIPO;
    }
}
