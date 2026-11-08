package com.seniorinsight.insight.adapters.in.agendamento;

import java.time.Instant;

import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.application.servico.CalcularScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "insight.gemini.habilitado", havingValue = "true")
class CalculoScoreJob {
    private static final Logger LOG = LoggerFactory.getLogger(CalculoScoreJob.class);
    private final ScoreRepositorio scores;
    private final CalcularScore calculo;

    CalculoScoreJob(ScoreRepositorio scores, CalcularScore calculo) {
        this.scores = scores;
        this.calculo = calculo;
    }

    @Scheduled(fixedDelayString = "${insight.gemini.intervalo-job}")
    void executar() {
        for (int i = 0; i < 10; i++) {
            var trabalho = scores.reservar(Instant.now());
            if (trabalho.isEmpty()) {
                return;
            }
            try {
                calculo.calcular(trabalho.get());
            } catch (RuntimeException falha) {
                LOG.warn("Cálculo do Score interrompido ({})", falha.getClass().getSimpleName());
            }
        }
    }
}
