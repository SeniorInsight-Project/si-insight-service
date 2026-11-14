package com.seniorinsight.insight.application.servico;

import java.time.Instant;
import java.util.UUID;

import com.seniorinsight.insight.application.porta.saida.PublicadorEventos;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Score;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Trabalho;
import com.seniorinsight.insight.application.servico.FormatoScoreIa.Saida;
import com.seniorinsight.insight.domain.evento.FaixaScoreAlterada;
import com.seniorinsight.insight.domain.semaforo.RegrasScore;
import org.springframework.transaction.support.TransactionOperations;

final class PersistirScore {
    private final ScoreRepositorio scores;
    private final PublicadorEventos eventos;
    private final TransactionOperations transacoes;

    PersistirScore(ScoreRepositorio scores, PublicadorEventos eventos, TransactionOperations transacoes) {
        this.scores = scores;
        this.eventos = eventos;
        this.transacoes = transacoes;
    }

    void guardar(Trabalho trabalho, Saida saida, UUID chamada, String hash) {
        transacoes.executeWithoutResult(status -> {
            UUID id = trabalho.idosoId();
            scores.bloquear(id);
            if (scores.contexto(id).revisao() != trabalho.revisao()) {
                return;
            }
            Score anterior = scores.recente(id).orElse(null);
            var faixaAnterior = anterior == null ? null : anterior.faixa();
            Double valorAnterior = anterior == null || anterior.revisao() != trabalho.revisao()
                    ? null : anterior.valor();
            var faixa = RegrasScore.calcular(saida.valor(), faixaAnterior, valorAnterior);
            Instant agora = Instant.now();
            scores.guardar(id, new Score(saida.valor(), faixa, agora, saida.fatores(), trabalho.revisao()), chamada, hash);
            if (faixa != faixaAnterior) {
                eventos.publicar(new FaixaScoreAlterada(id, faixaAnterior, faixa, agora));
            }
        });
    }

}
