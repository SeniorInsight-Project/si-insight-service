-- Estrutura inicial do insight-service. Roda no schema "insight" (spring.flyway.default-schema).

CREATE TABLE versao_prompt (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    nome         VARCHAR(100) NOT NULL,
    versao       INTEGER      NOT NULL,
    conteudo     TEXT         NOT NULL,
    aprovado_em  TIMESTAMPTZ,
    aprovado_por VARCHAR(255),
    criado_em    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_versao_prompt PRIMARY KEY (id),
    CONSTRAINT uk_versao_prompt_nome_versao UNIQUE (nome, versao),
    CONSTRAINT ck_versao_prompt_versao CHECK (versao > 0)
);

COMMENT ON TABLE versao_prompt IS 'Prompts versionados; só versões aprovadas (aprovado_em preenchido) são enviadas à IA';
COMMENT ON COLUMN versao_prompt.aprovado_por IS 'Claim "sub" de quem aprovou no Keycloak';

CREATE TABLE chamada_ia (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    idoso_id         UUID         NOT NULL,
    versao_prompt_id UUID         NOT NULL,
    modelo           VARCHAR(100) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    tokens_entrada   INTEGER,
    tokens_saida     INTEGER,
    latencia_ms      INTEGER,
    erro             TEXT,
    criado_em        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_chamada_ia PRIMARY KEY (id),
    CONSTRAINT fk_chamada_ia_versao_prompt FOREIGN KEY (versao_prompt_id) REFERENCES versao_prompt (id),
    CONSTRAINT ck_chamada_ia_status CHECK (status IN ('SUCESSO', 'ERRO', 'RESPOSTA_INVALIDA'))
);

COMMENT ON TABLE chamada_ia IS 'Auditoria das chamadas ao Gemini: não guarda o prompt nem dados identificáveis';

CREATE INDEX ix_chamada_ia_idoso_id ON chamada_ia (idoso_id, criado_em);

CREATE TABLE score (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    idoso_id       UUID         NOT NULL,
    valor          NUMERIC(5,2) NOT NULL,
    faixa          VARCHAR(10)  NOT NULL,
    chamada_ia_id  UUID,
    calculado_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_score PRIMARY KEY (id),
    CONSTRAINT fk_score_chamada_ia FOREIGN KEY (chamada_ia_id) REFERENCES chamada_ia (id),
    CONSTRAINT ck_score_valor CHECK (valor BETWEEN 0 AND 100),
    CONSTRAINT ck_score_faixa CHECK (faixa IN ('VERDE', 'AMARELO', 'VERMELHO'))
);

COMMENT ON COLUMN score.faixa IS 'Semáforo calculado com histerese (RN-008); a última linha do idoso é a faixa atual';

CREATE INDEX ix_score_idoso_id_calculado_em ON score (idoso_id, calculado_em DESC);

-- Projeção dos agregados publicados pelo telemetry-service (telemetry.ingested).
CREATE TABLE projecao_agregado (
    idoso_id      UUID         NOT NULL,
    metrica       VARCHAR(50)  NOT NULL,
    periodo_inicio TIMESTAMPTZ NOT NULL,
    periodo_fim   TIMESTAMPTZ  NOT NULL,
    valores       JSONB        NOT NULL,
    atualizado_em TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_projecao_agregado PRIMARY KEY (idoso_id, metrica, periodo_inicio),
    CONSTRAINT ck_projecao_agregado_periodo CHECK (periodo_fim > periodo_inicio)
);

-- Projeção do direito de acesso do familiar aos insights do idoso (consent.changed e subscription.changed do billing).
CREATE TABLE projecao_direito_acesso (
    idoso_id             UUID        NOT NULL,
    familiar_id          UUID        NOT NULL,
    consentimento_ativo  BOOLEAN     NOT NULL DEFAULT false,
    assinatura_ativa     BOOLEAN     NOT NULL DEFAULT false,
    assinatura_expira_em TIMESTAMPTZ,
    atualizado_em        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_projecao_direito_acesso PRIMARY KEY (idoso_id, familiar_id)
);

CREATE INDEX ix_projecao_direito_acesso_familiar_id ON projecao_direito_acesso (familiar_id);

-- Eventos já consumidos da fila insight.q: deduplicação pelo message_id (id do evento na outbox de origem).
CREATE TABLE evento_recebido (
    id            UUID         NOT NULL,
    tipo_evento   VARCHAR(150) NOT NULL,
    processado_em TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_evento_recebido PRIMARY KEY (id)
);

CREATE TABLE outbox_evento (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    tipo_agregado VARCHAR(100) NOT NULL,
    agregado_id   UUID         NOT NULL,
    tipo_evento   VARCHAR(150) NOT NULL,
    payload       JSONB        NOT NULL,
    criado_em     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    publicado_em  TIMESTAMPTZ,
    tentativas    INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbox_evento PRIMARY KEY (id)
);

COMMENT ON COLUMN outbox_evento.tipo_evento IS 'Routing key na exchange si.events, ex.: insight.score.changed';

-- O relay só lê pendentes, em ordem de criação.
CREATE INDEX ix_outbox_evento_pendentes ON outbox_evento (criado_em) WHERE publicado_em IS NULL;
