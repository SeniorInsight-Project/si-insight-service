package com.seniorinsight.insight.adapters.out.persistencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.seniorinsight.insight.TesteIntegracao;
import com.seniorinsight.insight.application.porta.entrada.ProcessarEventoRecebido;
import com.seniorinsight.insight.application.porta.entrada.ProcessarEventoRecebido.EventoRecebido;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.domain.semaforo.MedidasScore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@TesteIntegracao
@Transactional
class ScoreIntegracaoTest {
    @Autowired ProcessarEventoRecebido processador;
    @Autowired ScoreRepositorio scores;
    @Autowired JdbcTemplate jdbc;

    @Test
    void dadosMinimosConsentimentoERevogacao() {
        UUID id = UUID.randomUUID();
        LocalDate hoje = LocalDate.now(ZoneOffset.UTC);
        Instant autorizacao = hoje.minusDays(5).atStartOfDay().toInstant(ZoneOffset.UTC);
        consentir(id, true, autorizacao);
        for (int dia = 3; dia >= 1; dia--) {
            LocalDate data = hoje.minusDays(dia);
            evento("telemetry.ingested", """
                    {"idosoId":"%s","agregados":[
                      {"dia":"%s","tipo":"steps","soma":5000,"horasComDados":12},
                      {"dia":"%s","tipo":"heart_rate","media":72,"horasComDados":12}
                    ]}
                    """.formatted(id, data, data));
        }

        var contexto = scores.contexto(id);
        assertThat(contexto.tipos()).containsExactly("PASSOS");
        assertThat(contexto.agregados()).hasSize(3);
        assertThat(MedidasScore.diasValidos(contexto.agregados())).isEqualTo(3);
        assertThat(scores.reservar(Instant.now())).isPresent();

        consentir(id, false, Instant.now());
        consentir(id, true, autorizacao.plusSeconds(5));
        assertThat(scores.contexto(id).tipos()).isEmpty();
        assertThat(scores.contexto(id).agregados()).isEmpty();
        assertThat(scores.reservar(Instant.now())).isEmpty();
    }

    @Test
    void projetaTrialEConservaOTetoMesmoComExclusaoDaAuditoria() {
        String sub = UUID.randomUUID().toString();
        evento("subscription.changed", """
                {"time":"%s","data":{"familiarSub":"%s","ativa":true,"expiraEm":"%s"}}
                """.formatted(Instant.now(), sub, Instant.now().plusSeconds(60)));
        assertThat(scores.premium(sub)).isTrue();
        evento("subscription.changed", """
                {"time":"%s","data":{"familiarSub":"%s","ativa":false}}
                """.formatted(Instant.now().plusSeconds(1), sub));
        assertThat(scores.premium(sub)).isFalse();

        UUID id = UUID.randomUUID();
        UUID promptId = jdbc.queryForObject("""
                INSERT INTO versao_prompt (nome, versao, conteudo) VALUES (?, 1, 'teste')
                RETURNING id
                """, UUID.class, id.toString());
        assertThat(scores.reservarChamada(id, promptId, "gemini", 100, 60)).isPresent();
        jdbc.update("DELETE FROM chamada_ia WHERE idoso_id = ?", id);
        assertThat(scores.reservarChamada(id, promptId, "gemini", 100, 60)).isEmpty();
    }

    private void consentir(UUID id, boolean autorizado, Instant quando) {
        evento("consent.changed", """
                {"time":"%s","data":{"idosoId":"%s","tipo":"PASSOS","destinatario":"REDE","autorizado":%b}}
                """.formatted(quando, id, autorizado));
    }

    private void evento(String tipo, String payload) {
        processador.processar(new EventoRecebido(UUID.randomUUID(), tipo, payload));
    }
}
