package com.seniorinsight.insight.adapters.out.persistencia;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.seniorinsight.insight.application.porta.entrada.ProcessarEventoRecebido.EventoRecebido;
import com.seniorinsight.insight.application.porta.saida.ProjetarDadosScore;
import com.seniorinsight.insight.domain.semaforo.MedidasScore.Agregado;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ProjetarScoreJdbc implements ProjetarDadosScore {
    private final JdbcTemplate jdbc;
    private final ScoreJdbc scores;

    ProjetarScoreJdbc(JdbcTemplate jdbc, ScoreJdbc scores) {
        this.jdbc = jdbc;
        this.scores = scores;
    }

    @Override
    public void projetar(EventoRecebido evento) {
        var corpo = scores.ler(evento.payload());
        var dados = corpo.has("data") ? corpo.path("data") : corpo;
        switch (evento.tipo()) {
            case "consent.changed" -> projetarConsentimento(corpo, dados);
            case "telemetry.ingested" -> projetarAgregados(corpo, dados, evento.publicadoEm());
            case "subscription.changed" -> projetarAssinatura(corpo, dados);
            default -> { }
        }
    }

    private void projetarConsentimento(JsonNode corpo, JsonNode dados) {
        String tipo = dados.path("tipo").asText();
        if (!"REDE".equals(dados.path("destinatario").asText()) || !ScoreJdbc.TIPOS.containsValue(tipo)) {
            return;
        }
        UUID id = UUID.fromString(dados.path("idosoId").asText());
        if (!dados.path("autorizado").isBoolean()) {
            throw new IllegalArgumentException("Consentimento sem estado");
        }
        scores.bloquear(id);
        if (scores.excluido("I:" + id)) {
            return;
        }
        boolean autorizado = dados.path("autorizado").asBoolean();
        Boolean estadoAnterior = jdbc.query("SELECT autorizado FROM consentimento_score WHERE idoso_id = ? AND tipo = ?",
                (rs, row) -> rs.getBoolean(1), id, tipo).stream().findFirst().orElse(null);
        int atualizado = jdbc.update("""
                INSERT INTO consentimento_score (idoso_id, tipo, autorizado, ocorrido_em) VALUES (?, ?, ?, ?)
                ON CONFLICT (idoso_id, tipo) DO UPDATE SET autorizado = EXCLUDED.autorizado,
                    ocorrido_em = EXCLUDED.ocorrido_em
                WHERE consentimento_score.ocorrido_em < EXCLUDED.ocorrido_em
                    OR (consentimento_score.ocorrido_em = EXCLUDED.ocorrido_em
                        AND consentimento_score.autorizado AND NOT EXCLUDED.autorizado)
                """, id, tipo, autorizado, OffsetDateTime.parse(corpo.path("time").asText()));
        if (atualizado == 0 || Boolean.valueOf(autorizado).equals(estadoAnterior)) {
            return;
        }
        for (var metrica : ScoreJdbc.TIPOS.entrySet()) {
            if (metrica.getValue().equals(tipo)) {
                jdbc.update("DELETE FROM projecao_agregado WHERE idoso_id = ? AND metrica = ?", id, metrica.getKey());
            }
        }
        jdbc.update("""
                INSERT INTO estado_score (idoso_id, revisao_consentimento) VALUES (?, 1)
                ON CONFLICT (idoso_id) DO UPDATE SET revisao_consentimento = estado_score.revisao_consentimento + 1,
                    proximo_calculo = now()
                """, id);
    }

    private void projetarAgregados(JsonNode corpo, JsonNode dados, Instant publicadoEm) {
        if (!dados.path("agregados").isArray() || (!corpo.has("time") && publicadoEm == null)) {
            return;
        }
        UUID id = UUID.fromString(dados.path("idosoId").asText());
        scores.bloquear(id);
        if (scores.excluido("I:" + id)) {
            return;
        }
        var horario = corpo.has("time") ? OffsetDateTime.parse(corpo.path("time").asText())
                : publicadoEm.atOffset(ZoneOffset.UTC);
        var autorizados = scores.contexto(id).tipos();
        for (JsonNode item : dados.path("agregados")) {
            String metrica = item.path("tipo").asText();
            String tipo = ScoreJdbc.TIPOS.get(metrica);
            if (tipo == null || !autorizados.contains(tipo)) {
                continue;
            }
            var dia = java.time.LocalDate.parse(item.path("dia").asText());
            var inicio = dia.atStartOfDay().atOffset(ZoneOffset.UTC);
            String campo = Set.of("steps", "sleep").contains(metrica) ? "soma" : "media";
            if (!item.path(campo).isNumber() || !Double.isFinite(item.path(campo).asDouble())) {
                throw new IllegalArgumentException("Agregado sem valor válido");
            }
            var cobertura = item.path("horasComDados");
            int horas = cobertura.isIntegralNumber() && cobertura.canConvertToInt() ? cobertura.intValue() : 0;
            var agregado = new Agregado(dia, metrica, item.path(campo).asDouble(),
                    item.path("linhaBase14Dias").isNumber() ? item.path("linhaBase14Dias").asDouble() : null,
                    horas >= 0 && horas <= 25 ? horas : 0);
            boolean relevante = agregado.linhaBase14Dias() != null && agregado.linhaBase14Dias() > 0
                    && Math.abs(agregado.valor() / agregado.linhaBase14Dias() - 1) >= 0.2;
            int inserido = jdbc.update("""
                    INSERT INTO projecao_agregado (idoso_id, metrica, periodo_inicio, periodo_fim, valores, atualizado_em)
                    SELECT ?, ?, ?, ?, CAST(? AS jsonb), ?
                    WHERE EXISTS (SELECT 1 FROM consentimento_score WHERE idoso_id = ? AND tipo = ?
                        AND autorizado AND ocorrido_em <= ?
                        AND (ocorrido_em AT TIME ZONE 'America/Sao_Paulo')::date < CAST(? AS date))
                    ON CONFLICT (idoso_id, metrica, periodo_inicio) DO UPDATE SET valores = EXCLUDED.valores,
                        atualizado_em = EXCLUDED.atualizado_em
                    WHERE projecao_agregado.atualizado_em <= EXCLUDED.atualizado_em
                        AND projecao_agregado.valores IS DISTINCT FROM EXCLUDED.valores
                    """, id, metrica, inicio, inicio.plusDays(1), scores.escrever(agregado), horario, id, tipo, horario, dia);
            if (inserido > 0 && relevante) {
                jdbc.update("UPDATE estado_score SET proximo_calculo = LEAST(proximo_calculo, now()) WHERE idoso_id = ?", id);
            }
        }
    }

    private void projetarAssinatura(JsonNode corpo, JsonNode dados) {
        String sub = dados.path("familiarSub").asText();
        if (sub.isBlank() || !dados.path("ativa").isBoolean() || !corpo.has("time")) {
            return;
        }
        scores.bloquearChave("F:" + sub);
        if (scores.excluido("F:" + sub)) {
            return;
        }
        String expira = dados.path("expiraEm").asText("");
        jdbc.update("""
                INSERT INTO assinatura_score (familiar_sub, ativa, expira_em, ocorrido_em) VALUES (?, ?, ?, ?)
                ON CONFLICT (familiar_sub) DO UPDATE SET ativa = EXCLUDED.ativa, expira_em = EXCLUDED.expira_em,
                    ocorrido_em = EXCLUDED.ocorrido_em
                WHERE assinatura_score.ocorrido_em < EXCLUDED.ocorrido_em
                    OR (assinatura_score.ocorrido_em = EXCLUDED.ocorrido_em
                        AND assinatura_score.ativa AND NOT EXCLUDED.ativa)
                """, sub, dados.path("ativa").asBoolean(), expira.isBlank() ? null : OffsetDateTime.parse(expira),
                OffsetDateTime.parse(corpo.path("time").asText()));
    }

}
