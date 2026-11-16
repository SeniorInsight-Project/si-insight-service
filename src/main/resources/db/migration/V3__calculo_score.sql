ALTER TABLE score ADD COLUMN fatores JSONB NOT NULL DEFAULT '[]';
ALTER TABLE score ADD COLUMN hash_dados CHAR(64);
ALTER TABLE score ADD COLUMN revisao_consentimento BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chamada_ia ADD COLUMN custo_micros BIGINT NOT NULL DEFAULT 0;
ALTER TABLE versao_prompt ADD COLUMN modelo_aprovado VARCHAR(100);

CREATE TABLE consentimento_score (
    idoso_id UUID NOT NULL,
    tipo VARCHAR(32) NOT NULL,
    autorizado BOOLEAN NOT NULL,
    ocorrido_em TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (idoso_id, tipo)
);

CREATE TABLE estado_score (
    idoso_id UUID PRIMARY KEY,
    revisao_consentimento BIGINT NOT NULL DEFAULT 0,
    proximo_calculo TIMESTAMPTZ NOT NULL DEFAULT now(),
    em_calculo_ate TIMESTAMPTZ
);

CREATE TABLE assinatura_score (
    familiar_sub VARCHAR(255) PRIMARY KEY,
    ativa BOOLEAN NOT NULL,
    expira_em TIMESTAMPTZ,
    ocorrido_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_estado_score_proximo ON estado_score (proximo_calculo);
CREATE INDEX ix_chamada_ia_criado ON chamada_ia (criado_em);

CREATE TABLE orcamento_ia (
    dia DATE PRIMARY KEY,
    reservado_micros BIGINT NOT NULL CHECK (reservado_micros >= 0)
);

CREATE TRIGGER impedir_recriacao_consentimento_score
    BEFORE INSERT OR UPDATE OF idoso_id ON consentimento_score
    FOR EACH ROW EXECUTE FUNCTION impedir_recriacao_titular_excluido('idoso_id', 'I:');

CREATE TRIGGER impedir_recriacao_estado_score
    BEFORE INSERT OR UPDATE OF idoso_id ON estado_score
    FOR EACH ROW EXECUTE FUNCTION impedir_recriacao_titular_excluido('idoso_id', 'I:');

CREATE TRIGGER impedir_recriacao_assinatura_score
    BEFORE INSERT OR UPDATE OF familiar_sub ON assinatura_score
    FOR EACH ROW EXECUTE FUNCTION impedir_recriacao_titular_excluido('familiar_sub', 'F:');
