package com.seniorinsight.insight.application.servico;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seniorinsight.insight.application.porta.saida.ClienteIa;
import com.seniorinsight.insight.application.porta.saida.ClienteIa.PromptIa;
import com.seniorinsight.insight.application.porta.saida.ClienteIa.RespostaIa;
import com.seniorinsight.insight.application.porta.saida.PerfilScore;
import com.seniorinsight.insight.application.porta.saida.PublicadorEventos;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Trabalho;
import com.seniorinsight.insight.domain.semaforo.MedidasScore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class CalcularScore {
    private final ScoreRepositorio scores;
    private final PerfilScore perfis;
    private final ClienteIa ia;
    private final PersistirScore persistencia;
    private final TransactionOperations transacoes;
    private final FormatoScoreIa formato;
    private final String promptVersionado;
    private final String modelo;
    private final long teto;
    private final long precoEntrada;
    private final long precoSaida;

    public CalcularScore(ScoreRepositorio scores, PerfilScore perfis, ClienteIa ia, PublicadorEventos eventos,
            TransactionOperations transacoes, ObjectMapper json,
            @Value("classpath:prompts/score-v1.txt") Resource prompt,
            @Value("${insight.gemini.modelo}") String modelo,
            @Value("${insight.gemini.teto-diario-micros}") long teto,
            @Value("${insight.gemini.preco-entrada-micros-por-milhao}") long precoEntrada,
            @Value("${insight.gemini.preco-saida-micros-por-milhao}") long precoSaida) throws IOException {
        this.scores = scores;
        this.perfis = perfis;
        this.ia = ia;
        this.persistencia = new PersistirScore(scores, eventos, transacoes);
        this.transacoes = transacoes;
        this.formato = new FormatoScoreIa(json);
        this.promptVersionado = prompt.getContentAsString(StandardCharsets.UTF_8).strip();
        this.modelo = modelo;
        this.teto = teto;
        this.precoEntrada = precoEntrada;
        this.precoSaida = precoSaida;
    }

    public void calcular(Trabalho trabalho) {
        UUID id = trabalho.idosoId();
        try {
            calcularSeElegivel(trabalho);
        } finally {
            scores.liberar(id);
        }
    }

    private void calcularSeElegivel(Trabalho trabalho) {
        UUID id = trabalho.idosoId();
        var contexto = scores.contexto(id);
        if (contexto.revisao() != trabalho.revisao() || MedidasScore.diasValidos(contexto.agregados()) < 3
                || teto <= 0 || precoEntrada <= 0 || precoSaida <= 0) {
            return;
        }
        var prompt = scores.promptAprovado(modelo).filter(p -> p.conteudo().strip().equals(promptVersionado));
        if (prompt.isEmpty()) {
            return;
        }
        var perfil = perfis.buscar(id);
        if (perfil.isEmpty()) {
            return;
        }

        var evidencias = MedidasScore.explicacoes(contexto.agregados());
        String dados = formato.montar(perfil.get(), contexto.agregados(), evidencias.keySet());
        String conteudo = promptVersionado + "\n" + dados;
        int bytes = conteudo.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 64_000) {
            return;
        }
        long reserva = custo(bytes + 256, 512);

        for (int tentativa = 0; tentativa < 3; tentativa++) {
            var chamada = transacoes.execute(status -> {
                scores.bloquear(id);
                if (scores.contexto(id).revisao() != trabalho.revisao()) {
                    return java.util.Optional.<UUID>empty();
                }
                return scores.reservarChamada(id, prompt.get().id(), modelo, teto, reserva);
            });
            if (chamada == null || chamada.isEmpty()) {
                return;
            }
            long inicio = System.nanoTime();
            RespostaIa resposta;
            try {
                resposta = ia.gerar(new PromptIa("score:v1", conteudo));
            } catch (RuntimeException falha) {
                scores.registrarChamada(chamada.get(), modelo, "ERRO", 0, 0, latencia(inicio), reserva);
                if (tentativa == 2 || !aguardar(250L << tentativa)) {
                    return;
                }
                continue;
            }

            var saida = formato.validar(resposta, evidencias);
            long custo = custo(Math.max(0, resposta.tokensEntrada()), Math.max(0, resposta.tokensSaida()));
            scores.registrarChamada(chamada.get(), resposta.modelo(),
                    saida == null ? "RESPOSTA_INVALIDA" : "SUCESSO", resposta.tokensEntrada(),
                    resposta.tokensSaida(), latencia(inicio), custo);
            if (saida != null) {
                persistencia.guardar(trabalho, saida, chamada.get(), FormatoScoreIa.hash(dados));
            }
            return;
        }
    }

    private static int latencia(long inicio) {
        return (int) ((System.nanoTime() - inicio) / 1_000_000);
    }

    private long custo(int entrada, int saida) {
        return Math.ceilDiv(Math.addExact(Math.multiplyExact(entrada, precoEntrada),
                Math.multiplyExact(saida, precoSaida)), 1_000_000);
    }

    private static boolean aguardar(long milissegundos) {
        try {
            Thread.sleep(milissegundos);
            return true;
        } catch (InterruptedException falha) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

}
