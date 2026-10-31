package com.seniorinsight.insight.adapters.out.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxEventoRepository extends JpaRepository<OutboxEvento, UUID> {

    /**
     * Trava até {@code limite} eventos pendentes, do mais antigo para o mais novo. Linhas já travadas por outra
     * instância são puladas, então várias réplicas podem rodar o relay sem publicar o mesmo evento em paralelo.
     */
    @Query(value = """
            SELECT *
              FROM outbox_evento
             WHERE publicado_em IS NULL
             ORDER BY criado_em
             LIMIT :limite
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvento> travarPendentes(@Param("limite") int limite);
}
