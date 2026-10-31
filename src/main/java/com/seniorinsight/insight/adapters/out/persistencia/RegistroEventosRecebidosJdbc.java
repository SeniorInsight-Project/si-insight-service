package com.seniorinsight.insight.adapters.out.persistencia;

import java.util.UUID;

import com.seniorinsight.insight.application.porta.saida.RegistroEventosRecebidos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class RegistroEventosRecebidosJdbc implements RegistroEventosRecebidos {

    private final JdbcTemplate jdbc;

    RegistroEventosRecebidosJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A chave primária garante a unicidade mesmo com duas réplicas recebendo a mesma mensagem ao mesmo tempo. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean registrarSeNovo(UUID eventoId, String tipoEvento) {
        return jdbc.update("""
                INSERT INTO evento_recebido (id, tipo_evento) VALUES (?, ?)
                ON CONFLICT (id) DO NOTHING
                """, eventoId, tipoEvento) == 1;
    }
}
