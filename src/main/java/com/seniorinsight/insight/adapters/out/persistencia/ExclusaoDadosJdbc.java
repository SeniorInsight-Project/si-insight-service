package com.seniorinsight.insight.adapters.out.persistencia;

import java.util.UUID;

import com.seniorinsight.insight.application.porta.saida.ExclusaoDadosRepositorio;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ExclusaoDadosJdbc implements ExclusaoDadosRepositorio {

    private final JdbcTemplate jdbc;

    ExclusaoDadosJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean reservar(UUID protocolo) {
        return jdbc.update("INSERT INTO exclusao_confirmada (protocolo) VALUES (?) ON CONFLICT DO NOTHING",
                protocolo) == 1;
    }

    @Override
    public void excluir(UUID titularId, boolean idoso) {
        String chave = (idoso ? "I:" : "F:") + titularId;
        bloquear(chave);
        jdbc.update("INSERT INTO titular_excluido (chave) VALUES (sha256(convert_to(?, 'UTF8'))) ON CONFLICT DO NOTHING",
                chave);
        if (idoso) {
            jdbc.update("DELETE FROM estado_score WHERE idoso_id = ?", titularId);
            jdbc.update("DELETE FROM consentimento_score WHERE idoso_id = ?", titularId);
            jdbc.update("DELETE FROM score WHERE idoso_id = ?", titularId);
            jdbc.update("DELETE FROM chamada_ia WHERE idoso_id = ?", titularId);
            jdbc.update("DELETE FROM projecao_agregado WHERE idoso_id = ?", titularId);
            jdbc.update("DELETE FROM projecao_direito_acesso WHERE idoso_id = ?", titularId);
        } else {
            jdbc.update("DELETE FROM assinatura_score WHERE familiar_sub = ?", titularId.toString());
            jdbc.update("DELETE FROM projecao_direito_acesso WHERE familiar_id = ?", titularId);
        }
        String campo = idoso ? "idosoId" : "familiarId";
        jdbc.update("""
                DELETE FROM outbox_evento WHERE agregado_id = ? OR payload ->> CAST(? AS text) = ?
                   OR (payload -> 'data') ->> CAST(? AS text) = ?
                """, titularId, campo, titularId.toString(), campo, titularId.toString());
    }

    @Override
    public boolean idosoExcluido(UUID idosoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM titular_excluido WHERE chave = sha256(convert_to(?, 'UTF8')))
                """, Boolean.class, "I:" + idosoId));
    }

    @Override
    public void bloquearIdoso(UUID idosoId) {
        bloquear("I:" + idosoId);
    }

    private void bloquear(String chave) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", rs -> null, chave);
    }
}
