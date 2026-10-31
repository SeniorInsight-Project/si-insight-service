package com.seniorinsight.insight;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** O contexto subir já prova que o Hibernate validou o mapeamento ({@code ddl-auto: validate}) contra a V1. */
@TesteIntegracao
class EstruturaBancoTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAplicaAV1NoSchemaInsight() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'insight'", String.class))
                .contains("score", "chamada_ia", "versao_prompt", "projecao_agregado", "projecao_direito_acesso",
                        "evento_recebido", "outbox_evento", "flyway_schema_history");
    }

    @Test
    void outboxTemIndiceParcialDosPendentes() {
        String definicao = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'insight' AND indexname = ?",
                String.class, "ix_outbox_evento_pendentes");

        assertThat(definicao).contains("(criado_em)").contains("WHERE (publicado_em IS NULL)");
    }
}
