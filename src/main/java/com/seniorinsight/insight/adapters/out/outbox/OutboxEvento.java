package com.seniorinsight.insight.adapters.out.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Linha da tabela {@code outbox_evento}: evento gravado na transação do caso de uso, aguardando publicação. */
@Entity
@Table(name = "outbox_evento")
class OutboxEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String tipoAgregado;

    private UUID agregadoId;

    private String tipoEvento;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    private Instant criadoEm;

    private Instant publicadoEm;

    private int tentativas;

    protected OutboxEvento() {
    }

    OutboxEvento(String tipoAgregado, UUID agregadoId, String tipoEvento, String payload, Instant criadoEm) {
        this.tipoAgregado = tipoAgregado;
        this.agregadoId = agregadoId;
        this.tipoEvento = tipoEvento;
        this.payload = payload;
        this.criadoEm = criadoEm;
    }

    void marcarPublicado(Instant instante) {
        this.publicadoEm = instante;
    }

    void registrarFalha() {
        this.tentativas++;
    }

    UUID getId() {
        return id;
    }

    String getTipoAgregado() {
        return tipoAgregado;
    }

    UUID getAgregadoId() {
        return agregadoId;
    }

    String getTipoEvento() {
        return tipoEvento;
    }

    String getPayload() {
        return payload;
    }

    Instant getCriadoEm() {
        return criadoEm;
    }

    Instant getPublicadoEm() {
        return publicadoEm;
    }

    int getTentativas() {
        return tentativas;
    }
}
