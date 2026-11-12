package com.seniorinsight.insight.application.lgpd;

import java.util.UUID;

import com.seniorinsight.insight.application.porta.saida.CacheSemaforo;
import com.seniorinsight.insight.application.porta.saida.ExclusaoDadosRepositorio;
import com.seniorinsight.insight.application.porta.saida.PublicadorEventos;
import com.seniorinsight.insight.domain.evento.ExclusaoConcluida;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TratarExclusao {

    private final ExclusaoDadosRepositorio repositorio;
    private final PublicadorEventos eventos;
    private final CacheSemaforo cache;

    public TratarExclusao(ExclusaoDadosRepositorio repositorio, PublicadorEventos eventos,
                          CacheSemaforo cache) {
        this.repositorio = repositorio;
        this.eventos = eventos;
        this.cache = cache;
    }

    @Transactional
    public void tratar(Pedido pedido) {
        if (!repositorio.reservar(pedido.protocolo())) {
            return;
        }
        boolean idoso = pedido.idoso();
        repositorio.excluir(pedido.titular(), idoso);
        if (idoso) {
            cache.remover(pedido.titular());
        }
        eventos.publicar(new ExclusaoConcluida(pedido.protocolo(), "insight-service"));
    }


    public record Pedido(UUID protocolo, UUID titular, String sub, boolean idoso) {
    }
}
