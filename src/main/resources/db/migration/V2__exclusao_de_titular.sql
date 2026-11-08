
CREATE TABLE exclusao_confirmada (
    protocolo UUID PRIMARY KEY
);

-- A impressão é usada só para impedir que mensagens antigas restaurem os dados excluídos.
CREATE TABLE titular_excluido (
    chave BYTEA PRIMARY KEY
);

CREATE FUNCTION impedir_recriacao_titular_excluido() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    chave_titular text := TG_ARGV[1] || (to_jsonb(NEW) ->> TG_ARGV[0]);
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(chave_titular, 0));
    IF EXISTS (SELECT 1 FROM titular_excluido WHERE chave = sha256(convert_to(chave_titular, 'UTF8'))) THEN
        RAISE EXCEPTION 'Dados de titular excluído não podem ser recriados' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER impedir_recriacao_score_idoso_id
    BEFORE INSERT OR UPDATE OF idoso_id ON score
    FOR EACH ROW EXECUTE FUNCTION impedir_recriacao_titular_excluido('idoso_id', 'I:');

CREATE TRIGGER impedir_recriacao_chamada_ia_idoso_id
    BEFORE INSERT OR UPDATE OF idoso_id ON chamada_ia
    FOR EACH ROW EXECUTE FUNCTION impedir_recriacao_titular_excluido('idoso_id', 'I:');

CREATE TRIGGER impedir_recriacao_projecao_agregado_idoso_id
    BEFORE INSERT OR UPDATE OF idoso_id ON projecao_agregado
    FOR EACH ROW EXECUTE FUNCTION impedir_recriacao_titular_excluido('idoso_id', 'I:');

CREATE TRIGGER impedir_recriacao_projecao_direito_acesso_idoso_id
    BEFORE INSERT OR UPDATE OF idoso_id ON projecao_direito_acesso
    FOR EACH ROW EXECUTE FUNCTION impedir_recriacao_titular_excluido('idoso_id', 'I:');

CREATE TRIGGER impedir_recriacao_projecao_direito_acesso_familiar_id
    BEFORE INSERT OR UPDATE OF familiar_id ON projecao_direito_acesso
    FOR EACH ROW EXECUTE FUNCTION impedir_recriacao_titular_excluido('familiar_id', 'F:');
