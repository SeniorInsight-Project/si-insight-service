package com.seniorinsight.insight.adapters.out.persistencia;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seniorinsight.insight.application.porta.entrada.ProcessarEventoRecebido.EventoRecebido;
import com.seniorinsight.insight.application.porta.saida.ProjetarDadosScore;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.domain.semaforo.MedidasScore.Agregado;
import com.seniorinsight.insight.domain.semaforo.Semaforo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ScoreJdbc implements ScoreRepositorio, ProjetarDadosScore {
    private static final Map<String, String> TIPOS = Map.of("steps", "PASSOS", "heart_rate", "BATIMENTOS",
            "resting_heart_rate", "BATIMENTOS", "oxygen_saturation", "OXIGENACAO", "sleep", "SONO");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ScoreJdbc(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void projetar(EventoRecebido evento) {
        var corpo = ler(evento.payload());
        var dados = corpo.has("data") ? corpo.path("data") : corpo;
        switch (evento.tipo()) {
            case "consent.changed" -> projetarConsentimento(corpo, dados);
            case "telemetry.ingested" -> projetarAgregados(corpo, dados);
            case "subscription.changed" -> projetarAssinatura(corpo, dados);
            default -> { }
        }
    }

    private void projetarConsentimento(JsonNode corpo, JsonNode dados) {
        String tipo = dados.path("tipo").asText();
        if (!"REDE".equals(dados.path("destinatario").asText()) || !TIPOS.containsValue(tipo)) {
            return;
        }
        UUID id = UUID.fromString(dados.path("idosoId").asText());
        if (!dados.path("autorizado").isBoolean()) {
            throw new IllegalArgumentException("Consentimento sem estado");
        }
        bloquear(id);
        boolean autorizado = dados.path("autorizado").asBoolean();
        int atualizado = jdbc.update("""
                INSERT INTO consentimento_score (idoso_id, tipo, autorizado, ocorrido_em) VALUES (?, ?, ?, ?)
                ON CONFLICT (idoso_id, tipo) DO UPDATE SET autorizado = EXCLUDED.autorizado,
                    ocorrido_em = EXCLUDED.ocorrido_em
                WHERE consentimento_score.ocorrido_em < EXCLUDED.ocorrido_em
                    OR (consentimento_score.ocorrido_em = EXCLUDED.ocorrido_em
                        AND consentimento_score.autorizado AND NOT EXCLUDED.autorizado)
                """, id, tipo, autorizado, OffsetDateTime.parse(corpo.path("time").asText()));
        if (atualizado == 0) {
            return;
        }
        for (var metrica : TIPOS.entrySet()) {
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

    private void projetarAgregados(JsonNode corpo, JsonNode dados) {
        if (!dados.path("agregados").isArray()) {
            return;
        }
        UUID id = UUID.fromString(dados.path("idosoId").asText());
        bloquear(id);
        var horario = corpo.has("time") ? OffsetDateTime.parse(corpo.path("time").asText())
                : OffsetDateTime.now(ZoneOffset.UTC);
        var autorizados = contexto(id).tipos();
        for (JsonNode item : dados.path("agregados")) {
            String metrica = item.path("tipo").asText();
            String tipo = TIPOS.get(metrica);
            if (tipo == null || !autorizados.contains(tipo)) {
                continue;
            }
            var dia = java.time.LocalDate.parse(item.path("dia").asText());
            var inicio = dia.atStartOfDay().atOffset(ZoneOffset.UTC);
            String campo = Set.of("steps", "sleep").contains(metrica) ? "soma" : "media";
            if (!item.path(campo).isNumber() || !Double.isFinite(item.path(campo).asDouble())) {
                throw new IllegalArgumentException("Agregado sem valor válido");
            }
            var agregado = new Agregado(dia, metrica, item.path(campo).asDouble(),
                    item.path("linhaBase14Dias").isNumber() ? item.path("linhaBase14Dias").asDouble() : null,
                    Math.clamp(item.path("horasComDados").asInt(0), 0, 25));
            boolean relevante = agregado.linhaBase14Dias() != null && agregado.linhaBase14Dias() > 0
                    && Math.abs(agregado.valor() / agregado.linhaBase14Dias() - 1) >= 0.2;
            int inserido = jdbc.update("""
                    INSERT INTO projecao_agregado (idoso_id, metrica, periodo_inicio, periodo_fim, valores, atualizado_em)
                    SELECT ?, ?, ?, ?, CAST(? AS jsonb), ?
                    WHERE EXISTS (SELECT 1 FROM consentimento_score WHERE idoso_id = ? AND tipo = ?
                        AND autorizado AND ocorrido_em <= ?)
                    ON CONFLICT (idoso_id, metrica, periodo_inicio) DO UPDATE SET valores = EXCLUDED.valores,
                        atualizado_em = EXCLUDED.atualizado_em
                    WHERE projecao_agregado.atualizado_em <= EXCLUDED.atualizado_em
                        AND projecao_agregado.valores IS DISTINCT FROM EXCLUDED.valores
                    """, id, metrica, inicio, inicio.plusDays(1), escrever(agregado), horario, id, tipo, horario);
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

    @Override
    public Optional<Trabalho> reservar(Instant agora) {
        var hora = agora.atOffset(ZoneOffset.UTC);
        return jdbc.query("""
                WITH candidato AS (
                    SELECT e.idoso_id FROM estado_score e
                     WHERE e.proximo_calculo <= ? AND (e.em_calculo_ate IS NULL OR e.em_calculo_ate <= ?)
                       AND EXISTS (SELECT 1 FROM consentimento_score c WHERE c.idoso_id = e.idoso_id AND c.autorizado)
                     ORDER BY e.proximo_calculo LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE estado_score e SET em_calculo_ate = ? + interval '10 minutes',
                    proximo_calculo = ? + interval '6 hours'
                  FROM candidato WHERE candidato.idoso_id = e.idoso_id
                RETURNING e.idoso_id, e.revisao_consentimento
                """, (rs, row) -> new Trabalho(rs.getObject(1, UUID.class), rs.getLong(2)),
                hora, hora, hora, hora).stream().findFirst();
    }

    @Override
    public Contexto contexto(UUID idosoId) {
        long revisao = jdbc.query("SELECT revisao_consentimento FROM estado_score WHERE idoso_id = ?",
                (rs, row) -> rs.getLong(1), idosoId).stream().findFirst().orElse(0L);
        var tipos = Set.copyOf(jdbc.queryForList("""
                SELECT tipo FROM consentimento_score WHERE idoso_id = ? AND autorizado
                """, String.class, idosoId));
        var agregados = jdbc.queryForList("""
                SELECT valores::text FROM projecao_agregado WHERE idoso_id = ?
                  AND periodo_inicio >= date_trunc('day', now()) - interval '14 days'
                ORDER BY periodo_inicio, metrica
                """, String.class, idosoId).stream().map(this::ler)
                .map(no -> converter(no, Agregado.class)).filter(a -> tipos.contains(TIPOS.getOrDefault(a.tipo(), "")))
                .toList();
        return new Contexto(revisao, tipos, agregados);
    }

    @Override
    public Optional<Score> recente(UUID idosoId) {
        return jdbc.query("""
                SELECT valor, faixa, calculado_em, fatores::text, revisao_consentimento
                  FROM score WHERE idoso_id = ? ORDER BY calculado_em DESC, id DESC LIMIT 1
                """, (rs, row) -> new Score(rs.getDouble(1), Semaforo.valueOf(rs.getString(2)),
                rs.getObject(3, OffsetDateTime.class).toInstant(),
                Arrays.asList(converter(ler(rs.getString(4)), String[].class)), rs.getLong(5)), idosoId)
                .stream().findFirst();
    }

    @Override
    public Optional<Prompt> promptAprovado() {
        return jdbc.query("""
                SELECT id, conteudo FROM versao_prompt WHERE nome = 'score' AND versao = 1
                    AND aprovado_em <= now() AND length(trim(aprovado_por)) > 0
                """, (rs, row) -> new Prompt(rs.getObject(1, UUID.class), rs.getString(2))).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<UUID> reservarChamada(UUID idosoId, UUID promptId, String modelo, long teto, long reserva) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('insight:orcamento', 0))", rs -> null);
        return jdbc.query("""
                INSERT INTO chamada_ia (idoso_id, versao_prompt_id, modelo, status, custo_micros)
                SELECT ?, ?, ?, 'ERRO', ?
                WHERE COALESCE((SELECT SUM(custo_micros) FROM chamada_ia
                    WHERE criado_em >= date_trunc('day', now(), 'UTC')), 0) + ? <= ?
                RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), idosoId, promptId, modelo, reserva, reserva, teto)
                .stream().findFirst();
    }

    @Override
    public void registrarChamada(UUID id, String modelo, String estado, int entrada, int saida, int latencia, long custo) {
        jdbc.update("""
                UPDATE chamada_ia SET modelo = ?, status = ?, tokens_entrada = ?, tokens_saida = ?,
                    latencia_ms = ?, custo_micros = ? WHERE id = ?
                """, modelo, estado, entrada, saida, latencia, custo, id);
    }

    @Override
    public void guardar(UUID idosoId, Score score, UUID chamada, String hash) {
        jdbc.update("""
                INSERT INTO score (idoso_id, valor, faixa, calculado_em, chamada_ia_id, fatores,
                    hash_dados, revisao_consentimento)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """, idosoId, score.valor(), score.faixa().name(), score.calculadoEm().atOffset(ZoneOffset.UTC),
                chamada, escrever(score.fatores()), hash, score.revisao());
    }

    @Override
    public void liberar(UUID idosoId) {
        jdbc.update("UPDATE estado_score SET em_calculo_ate = NULL WHERE idoso_id = ?", idosoId);
    }

    @Override
    public boolean premium(String familiarSub) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM assinatura_score WHERE familiar_sub = ? AND ativa
                    AND (expira_em IS NULL OR expira_em > now()))
                """, Boolean.class, familiarSub));
    }

    @Override
    public void bloquear(UUID idosoId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "I:" + idosoId);
    }

    private JsonNode ler(String valor) {
        try {
            return json.readTree(valor);
        } catch (JsonProcessingException falha) {
            throw new IllegalArgumentException("JSON inválido");
        }
    }

    private String escrever(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException falha) {
            throw new IllegalArgumentException("Não foi possível serializar o Score");
        }
    }

    private <T> T converter(JsonNode valor, Class<T> tipo) {
        return json.convertValue(valor, tipo);
    }
}
