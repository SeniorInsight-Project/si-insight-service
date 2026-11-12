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
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.domain.semaforo.MedidasScore.Agregado;
import com.seniorinsight.insight.domain.semaforo.Semaforo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class ScoreJdbc implements ScoreRepositorio {
    static final Map<String, String> TIPOS = Map.of("steps", "PASSOS", "heart_rate", "BATIMENTOS",
            "resting_heart_rate", "BATIMENTOS", "oxygen_saturation", "OXIGENACAO", "sleep", "SONO");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ScoreJdbc(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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
    public Optional<Prompt> promptAprovado(String modelo) {
        return jdbc.query("""
                SELECT id, conteudo FROM versao_prompt WHERE nome = 'score' AND versao = 1
                    AND aprovado_em <= now() AND length(trim(aprovado_por)) > 0 AND modelo_aprovado = ?
                """, (rs, row) -> new Prompt(rs.getObject(1, UUID.class), rs.getString(2)), modelo).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<UUID> reservarChamada(UUID idosoId, UUID promptId, String modelo, long teto, long reserva) {
        if (reserva > teto || jdbc.update("""
                INSERT INTO orcamento_ia (dia, reservado_micros)
                VALUES ((now() AT TIME ZONE 'UTC')::date, ?)
                ON CONFLICT (dia) DO UPDATE
                  SET reservado_micros = orcamento_ia.reservado_micros + EXCLUDED.reservado_micros
                WHERE orcamento_ia.reservado_micros <= ? - EXCLUDED.reservado_micros
                """, reserva, teto) == 0) {
            return Optional.empty();
        }
        return jdbc.query("""
                INSERT INTO chamada_ia (idoso_id, versao_prompt_id, modelo, status, custo_micros)
                VALUES (?, ?, ?, 'ERRO', ?)
                RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), idosoId, promptId, modelo, reserva)
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
        bloquearChave("I:" + idosoId);
    }

    void bloquearChave(String chave) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, chave);
    }

    boolean excluido(String chave) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM titular_excluido WHERE chave = sha256(convert_to(?, 'UTF8')))
                """, Boolean.class, chave));
    }

    JsonNode ler(String valor) {
        try {
            return json.readTree(valor);
        } catch (JsonProcessingException falha) {
            throw new IllegalArgumentException("JSON inválido");
        }
    }

    String escrever(Object valor) {
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
