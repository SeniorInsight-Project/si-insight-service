package com.seniorinsight.insight.application.servico;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seniorinsight.insight.application.porta.saida.ClienteIa;
import com.seniorinsight.insight.application.porta.saida.ClienteIa.PromptIa;
import com.seniorinsight.insight.application.porta.saida.ClienteIa.RespostaIa;
import com.seniorinsight.insight.application.porta.saida.PerfilScore;
import com.seniorinsight.insight.application.porta.saida.PublicadorEventos;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Score;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Trabalho;
import com.seniorinsight.insight.domain.evento.FaixaScoreAlterada;
import com.seniorinsight.insight.domain.semaforo.MedidasScore;
import com.seniorinsight.insight.domain.semaforo.RegrasScore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class CalcularScore {
    private final ScoreRepositorio scores;
    private final PerfilScore perfis;
    private final ClienteIa ia;
    private final PublicadorEventos eventos;
    private final TransactionOperations transacoes;
    private final ObjectMapper json;
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
        this.eventos = eventos;
        this.transacoes = transacoes;
        this.json = json;
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
        var prompt = scores.promptAprovado().filter(p -> p.conteudo().strip().equals(promptVersionado));
        if (prompt.isEmpty()) {
            return;
        }
        var perfil = perfis.buscar(id);
        if (perfil.isEmpty()) {
            return;
        }

        var evidencias = MedidasScore.explicacoes(contexto.agregados());
        String dados = serializar(Map.of("perfil", perfil.get(), "agregados", contexto.agregados(),
                "fatoresPermitidos", evidencias.keySet()));
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

            Saida saida = validar(resposta, evidencias);
            long custo = custo(Math.max(0, resposta.tokensEntrada()), Math.max(0, resposta.tokensSaida()));
            scores.registrarChamada(chamada.get(), resposta.modelo(),
                    saida == null ? "RESPOSTA_INVALIDA" : "SUCESSO", resposta.tokensEntrada(),
                    resposta.tokensSaida(), latencia(inicio), custo);
            if (saida != null) {
                guardar(trabalho, saida, chamada.get(), hash(dados));
            }
            return;
        }
    }

    private void guardar(Trabalho trabalho, Saida saida, UUID chamada, String hash) {
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

    private Saida validar(RespostaIa resposta, Map<String, String> evidencias) {
        if (resposta.conteudo() == null || resposta.conteudo().length() > 4096) {
            return null;
        }
        try {
            var no = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(resposta.conteudo());
            var valor = no.path("score");
            var fatores = no.path("fatores");
            if (no.size() != 2 || !valor.isNumber() || !Double.isFinite(valor.asDouble())
                    || valor.asDouble() < 0 || valor.asDouble() > 100 || !fatores.isArray() || fatores.size() > 4) {
                return null;
            }
            var explicacoes = StreamSupport.stream(fatores.spliterator(), false)
                    .map(f -> f.isTextual() ? evidencias.get(f.asText()) : null).toList();
            return explicacoes.stream().anyMatch(Objects::isNull)
                    || explicacoes.stream().distinct().count() != explicacoes.size()
                    ? null : new Saida(valor.asDouble(), explicacoes);
        } catch (JsonProcessingException falha) {
            return null;
        }
    }

    private String serializar(Object objeto) {
        try {
            return json.writeValueAsString(objeto);
        } catch (JsonProcessingException falha) {
            throw new IllegalStateException("Contexto do Score inválido");
        }
    }

    private static String hash(String dados) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(dados.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException falha) {
            throw new IllegalStateException(falha);
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

    private record Saida(double valor, List<String> fatores) {
    }
}
