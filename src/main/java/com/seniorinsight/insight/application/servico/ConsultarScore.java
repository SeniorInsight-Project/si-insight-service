package com.seniorinsight.insight.application.servico;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seniorinsight.insight.application.porta.saida.CacheSemaforo;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.application.porta.saida.VinculoFamiliar;
import com.seniorinsight.insight.domain.semaforo.MedidasScore;
import com.seniorinsight.insight.domain.semaforo.Semaforo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class ConsultarScore {
    private static final String AVISO = "Informação de apoio ao cuidado; não substitui avaliação médica";
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final ScoreRepositorio scores;
    private final VinculoFamiliar vinculos;
    private final CacheSemaforo cache;
    private final TransactionOperations transacoes;
    private final ZoneId fuso;

    public ConsultarScore(ScoreRepositorio scores, VinculoFamiliar vinculos, CacheSemaforo cache,
            TransactionOperations transacoes, @Value("${insight.semaforo.fuso}") String fuso) {
        this.scores = scores;
        this.vinculos = vinculos;
        this.cache = cache;
        this.transacoes = transacoes;
        this.fuso = ZoneId.of(fuso);
    }

    public Resultado buscar(UUID idosoId, String sub, String token) {
        if (!scores.premium(sub) || !vinculos.confirmado(idosoId, sub, token)) {
            throw new AccessDeniedException("Vínculo e acesso Premium ou trial são necessários");
        }
        return transacoes.execute(status -> {
            scores.bloquear(idosoId);
            if (!scores.premium(sub)) {
                throw new AccessDeniedException("Acesso ao Score expirado");
            }
            return ler(idosoId);
        });
    }

    private Resultado ler(UUID idosoId) {
        var contexto = scores.contexto(idosoId);
        int dias = MedidasScore.diasValidos(contexto.agregados());
        if (contexto.tipos().isEmpty()) {
            return indisponivel(idosoId, "MONITORAMENTO_PAUSADO", dias, "Monitoramento pausado pelo idoso");
        }
        if (dias < 3) {
            String tempo = 3 - dias == 1 ? "falta 1 dia" : "faltam " + (3 - dias) + " dias";
            return indisponivel(idosoId, "CONHECENDO_ROTINA", dias, "Conhecendo a rotina: " + tempo);
        }
        var score = scores.recente(idosoId).filter(s -> s.revisao() == contexto.revisao());
        if (score.isEmpty()) {
            return indisponivel(idosoId, "SEM_ANALISE", dias, "Aguardando análise");
        }

        var s = score.get();
        var fatores = new ArrayList<>(s.fatores());
        if (fatores.isEmpty() && s.faixa() == Semaforo.VERDE) {
            fatores.add("Sinais dentro do normal");
        }
        if (!contexto.tipos().contains("PASSOS")) {
            fatores.add("Semáforo calculado sem dados de passos");
        }
        return new Resultado(idosoId, "DISPONIVEL", s.valor(), faixaAtual(idosoId, s.faixa()), dias,
                s.calculadoEm(), List.copyOf(fatores), "Análise de " + HORA.withZone(fuso).format(s.calculadoEm()), AVISO);
    }

    private Semaforo faixaAtual(UUID idosoId, Semaforo fonte) {
        try {
            var emCache = cache.buscar(idosoId);
            if (emCache.filter(fonte::equals).isPresent()) {
                return emCache.get();
            }
            cache.guardar(idosoId, fonte);
        } catch (org.springframework.dao.DataAccessException | IllegalArgumentException falha) {
            // O PostgreSQL continua sendo a fonte da faixa.
        }
        return fonte;
    }

    private static Resultado indisponivel(UUID id, String estado, int dias, String mensagem) {
        return new Resultado(id, estado, null, null, dias, null, List.of(), mensagem, AVISO);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resultado(UUID idosoId, String estado, Double score, Semaforo faixa, int diasComDadosValidos,
                            Instant coletadoEm, List<String> fatores, String mensagem, String aviso) {
    }
}
