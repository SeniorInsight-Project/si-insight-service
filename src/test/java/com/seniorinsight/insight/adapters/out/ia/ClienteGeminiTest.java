package com.seniorinsight.insight.adapters.out.ia;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import com.seniorinsight.insight.application.porta.saida.ClienteIa.PromptIa;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

class ClienteGeminiTest {

    private final PromptIa prompt = new PromptIa("resumo-semanal:v1", "texto");

    @Test
    void semChaveConfiguradaRecusaAChamada() {
        assertThatThrownBy(() -> new ClienteGemini("", "gemini-2.5-flash", 5, 60, Duration.ofMinutes(5)).gerar(prompt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void ocultaChaveNaFalhaEAbreCircuitoSemNovaChamada() {
        var circuito = CircuitBreaker.of("gemini-teste", CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).build());
        var chamadas = new AtomicInteger();
        var cliente = new ClienteGemini(entrada -> {
            chamadas.incrementAndGet();
            throw new IllegalStateException("provedor: chave-secreta");
        }, circuito);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> cliente.gerar(prompt)).hasMessage("Gemini indisponível").hasNoCause();
        }
        assertThat(chamadas).hasValue(2);
        assertThat(circuito.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
