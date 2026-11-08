package com.seniorinsight.insight.application.porta.saida;

import java.util.UUID;

public interface ExclusaoDadosRepositorio {

    boolean reservar(UUID protocolo);

    void excluir(UUID titularId, boolean idoso);

    boolean idosoExcluido(UUID idosoId);

    void bloquearIdoso(UUID idosoId);
}
