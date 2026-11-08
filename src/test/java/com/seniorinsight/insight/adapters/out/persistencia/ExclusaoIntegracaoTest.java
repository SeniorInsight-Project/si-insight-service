package com.seniorinsight.insight.adapters.out.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import java.util.UUID;

import com.seniorinsight.insight.TesteIntegracao;
import com.seniorinsight.insight.application.lgpd.TratarExclusao;

import com.seniorinsight.insight.application.porta.saida.CacheSemaforo;
import com.seniorinsight.insight.domain.semaforo.Semaforo;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@TesteIntegracao
@Transactional
class ExclusaoIntegracaoTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entidades;
    @Autowired TratarExclusao tratador;
    @Autowired CacheSemaforo cache;

    @Test
    void apagaScoreProjecoesECacheEImpedeRecriacao() {
        UUID titular = UUID.randomUUID();
        UUID protocolo = UUID.randomUUID();
        jdbc.update("INSERT INTO score (idoso_id, valor, faixa) VALUES (?, 70, 'VERDE')", titular);
        jdbc.update("INSERT INTO projecao_direito_acesso (idoso_id, familiar_id) VALUES (?, ?)",
                titular, UUID.randomUUID());
        cache.guardar(titular, Semaforo.VERDE);

        enviar(protocolo, titular, "IDOSO", "sub-idoso");
        enviar(protocolo, titular, "IDOSO", "sub-idoso");

        assertThat(jdbc.queryForList("SELECT id FROM score WHERE idoso_id = ?", UUID.class, titular)).isEmpty();
        assertThat(jdbc.queryForList("SELECT idoso_id FROM projecao_direito_acesso WHERE idoso_id = ?",
                UUID.class, titular)).isEmpty();
        cache.guardar(titular, Semaforo.VERDE);
        assertThat(cache.buscar(titular)).isEmpty();
        confirmar(protocolo);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO score (idoso_id, valor, faixa) VALUES (?, 70, 'VERDE')",
                titular)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void excluiProjecaoDoFamiliarPreservandoOsScoresDoIdoso() {
        UUID familiar = UUID.randomUUID();
        UUID idoso = UUID.randomUUID();
        UUID protocolo = UUID.randomUUID();
        jdbc.update("INSERT INTO score (idoso_id, valor, faixa) VALUES (?, 70, 'VERDE')", idoso);
        jdbc.update("INSERT INTO projecao_direito_acesso (idoso_id, familiar_id) VALUES (?, ?)", idoso, familiar);

        enviar(protocolo, familiar, "FAMILIAR", "sub-familiar");

        assertThat(jdbc.queryForList("SELECT id FROM score WHERE idoso_id = ?", UUID.class, idoso)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT idoso_id FROM projecao_direito_acesso WHERE familiar_id = ?",
                UUID.class, familiar)).isEmpty();
        confirmar(protocolo);
    }
    private void confirmar(UUID protocolo) {
        entidades.flush();
        assertThat(jdbc.queryForList("""
                SELECT payload->>'protocolo' FROM outbox_evento
                WHERE agregado_id = ? AND tipo_evento = 'lgpd.erasure.completed'
                """, String.class, protocolo)).containsExactly(protocolo.toString());
    }

    private void enviar(UUID protocolo, UUID titular, String tipo, String sub) {
        UUID mensagem = UUID.randomUUID();
        String corpo = """
                {"specversion":"1.0","id":"%s","source":"/report-service","type":"lgpd.erasure.requested",
                 "time":"2026-11-08T15:30:00Z","datacontenttype":"application/json",
                 "dataschema":"https://raw.githubusercontent.com/SeniorInsight-Project/si-contracts/main/events/lgpd.erasure.requested.v1.json",
                 "subject":"%s","data":{"protocolo":"%s","titularId":"%s","tipoTitular":"%s","titularSub":"%s"}}
                """.formatted(mensagem, protocolo, protocolo, titular, tipo, sub);
        tratador.tratar(mensagem, corpo);
    }
}
