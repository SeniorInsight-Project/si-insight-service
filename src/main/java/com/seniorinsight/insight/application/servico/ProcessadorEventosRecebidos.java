package com.seniorinsight.insight.application.servico;

import com.seniorinsight.insight.application.porta.entrada.ProcessarEventoRecebido;
import com.seniorinsight.insight.application.porta.saida.RegistroEventosRecebidos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplica pelo id do evento e, na mesma transação, aplica o efeito. Por enquanto só registra o recebimento: a
 * atualização das projeções de agregados ({@code telemetry.ingested}) e de direito de acesso ({@code consent.changed},
 * {@code subscription.changed}) entra nos próximos cards.
 */
@Service
class ProcessadorEventosRecebidos implements ProcessarEventoRecebido {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorEventosRecebidos.class);

    private final RegistroEventosRecebidos registro;

    ProcessadorEventosRecebidos(RegistroEventosRecebidos registro) {
        this.registro = registro;
    }

    @Override
    @Transactional
    public boolean processar(EventoRecebido evento) {
        if (!registro.registrarSeNovo(evento.id(), evento.tipo())) {
            log.info("Evento {} ({}) já processado: ignorado", evento.id(), evento.tipo());
            return false;
        }
        log.debug("Evento {} ({}) recebido", evento.id(), evento.tipo());
        return true;
    }
}
