package com.seniorinsight.insight.application.servico;

import com.seniorinsight.insight.application.porta.entrada.ProcessarEventoRecebido;
import com.seniorinsight.insight.application.lgpd.TratarExclusao;
import com.seniorinsight.insight.application.porta.saida.RegistroEventosRecebidos;
import com.seniorinsight.insight.application.porta.saida.ProjetarDadosScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplica pelo id do evento e, na mesma transação, aplica o efeito da exclusão do titular.
 */
@Service
class ProcessadorEventosRecebidos implements ProcessarEventoRecebido {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorEventosRecebidos.class);

    private final RegistroEventosRecebidos registro;
    private final ProjetarDadosScore projecoes;
    private final TratarExclusao exclusao;

    ProcessadorEventosRecebidos(RegistroEventosRecebidos registro, ProjetarDadosScore projecoes, TratarExclusao exclusao) {
        this.registro = registro;
        this.projecoes = projecoes;
        this.exclusao = exclusao;
    }

    @Override
    @Transactional
    public boolean processar(EventoRecebido evento) {
        if (!registro.registrarSeNovo(evento.id(), evento.tipo())) {
            log.info("Evento {} ({}) já processado: ignorado", evento.id(), evento.tipo());
            return false;
        }
        if (TratarExclusao.TIPO.equals(evento.tipo())) {
            exclusao.tratar(evento.id(), evento.payload());
        } else {
            projecoes.projetar(evento);
        }
        log.debug("Evento {} ({}) recebido", evento.id(), evento.tipo());
        return true;
    }
}
