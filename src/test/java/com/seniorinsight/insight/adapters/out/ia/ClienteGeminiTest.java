package com.seniorinsight.insight.adapters.out.ia;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seniorinsight.insight.application.porta.saida.ClienteIa.PromptIa;
import org.junit.jupiter.api.Test;

class ClienteGeminiTest {

    private final PromptIa prompt = new PromptIa("resumo-semanal:v1", "texto");

    @Test
    void semChaveConfiguradaRecusaAChamada() {
        assertThatThrownBy(() -> new ClienteGemini("", "gemini-2.5-flash").gerar(prompt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void chamadaRealAindaNaoImplementadaENaoExpoeAChave() {
        assertThatThrownBy(() -> new ClienteGemini("chave-de-teste", "gemini-2.5-flash").gerar(prompt))
                .isInstanceOf(UnsupportedOperationException.class)
                .message().doesNotContain("chave-de-teste");
    }
}
