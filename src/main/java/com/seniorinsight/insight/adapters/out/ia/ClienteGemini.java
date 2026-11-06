package com.seniorinsight.insight.adapters.out.ia;

import com.seniorinsight.insight.application.porta.saida.ClienteIa;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adaptador do Gemini, ainda sem chamada real. A chave vem só da variável de ambiente {@code GEMINI_API_KEY}
 * (Secret Manager em produção) e nunca vai para o log.
 */
@Component
class ClienteGemini implements ClienteIa {

    private final String chaveApi;
    private final String modelo;

    ClienteGemini(@Value("${insight.gemini.api-key}") String chaveApi,
                  @Value("${insight.gemini.modelo}") String modelo) {
        this.chaveApi = chaveApi;
        this.modelo = modelo;
    }

    @Override
    public RespostaIa gerar(PromptIa prompt) {
        if (chaveApi == null || chaveApi.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY não configurada");
        }
        throw new UnsupportedOperationException("Chamada ao Gemini (" + modelo + ") ainda não implementada");
    }
}
