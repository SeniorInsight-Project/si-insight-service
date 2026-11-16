package com.seniorinsight.insight.application.servico;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seniorinsight.insight.application.porta.saida.ClienteIa.RespostaIa;
import com.seniorinsight.insight.application.porta.saida.PerfilScore;
import com.seniorinsight.insight.domain.semaforo.MedidasScore.Agregado;

final class FormatoScoreIa {
    private final ObjectMapper json;

    FormatoScoreIa(ObjectMapper json) {
        this.json = json;
    }

    String montar(PerfilScore.Perfil perfil, List<Agregado> agregados, Set<String> fatores) {
        return serializar(new EntradaIa(perfil, agregados, List.copyOf(fatores)));
    }

    Saida validar(RespostaIa resposta, Map<String, String> evidencias) {
        if (resposta.conteudo() == null || resposta.conteudo().length() > 4096) {
            return null;
        }
        try {
            var no = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
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

    static String hash(String dados) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(dados.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException falha) {
            throw new IllegalStateException(falha);
        }
    }

    record Saida(double valor, List<String> fatores) {
    }

    private record EntradaIa(PerfilScore.Perfil perfil, List<Agregado> agregados, List<String> fatoresPermitidos) {
    }
}
