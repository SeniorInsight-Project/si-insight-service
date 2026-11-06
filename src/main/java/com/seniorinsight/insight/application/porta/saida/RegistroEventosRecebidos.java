package com.seniorinsight.insight.application.porta.saida;

import java.util.UUID;

/** Guarda o id dos eventos já processados, para que uma reentrega da mesma mensagem não tenha efeito. */
public interface RegistroEventosRecebidos {

    /** Registra o evento na transação corrente. Retorna {@code false} se o id já tinha sido registrado. */
    boolean registrarSeNovo(UUID eventoId, String tipoEvento);
}
