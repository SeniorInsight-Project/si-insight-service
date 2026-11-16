package com.seniorinsight.insight.adapters.out.ia;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Schema;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Type;
import com.seniorinsight.insight.application.porta.saida.ClienteIa;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.annotation.PreDestroy;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ClienteGemini implements ClienteIa {

    private static final Schema SAIDA = Schema.builder().type(Type.Known.OBJECT)
            .required(List.of("score", "fatores"))
            .properties(Map.of(
                    "score", Schema.builder().type(Type.Known.NUMBER).minimum(0.0).maximum(100.0).build(),
                    "fatores", Schema.builder().type(Type.Known.ARRAY)
                            .items(Schema.builder().type(Type.Known.STRING).build()).build()))
            .build();
    private static final GenerateContentConfig CONFIG = GenerateContentConfig.builder()
            .responseMimeType("application/json").responseSchema(SAIDA).maxOutputTokens(512)
            .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build()).build();

    private final Client cliente;
    private final Function<PromptIa, RespostaIa> chamada;
    private final CircuitBreaker circuito;

    @Autowired
    ClienteGemini(@Value("${insight.gemini.api-key}") String chaveApi,
                  @Value("${insight.gemini.modelo}") String modelo,
                  @Value("${insight.gemini.circuito.janela}") int janela,
                  @Value("${insight.gemini.circuito.limiar}") float limiar,
                  @Value("${insight.gemini.circuito.espera}") Duration espera) {
        this.cliente = chaveApi.isBlank() ? null : Client.builder().apiKey(chaveApi)
                .httpOptions(HttpOptions.builder().timeout(30_000)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
        this.chamada = cliente == null ? null : prompt -> consultar(cliente, modelo, prompt);
        this.circuito = CircuitBreaker.of("gemini", CircuitBreakerConfig.custom()
                .slidingWindowSize(janela).minimumNumberOfCalls(janela).failureRateThreshold(limiar)
                .waitDurationInOpenState(espera).build());
    }

    ClienteGemini(Function<PromptIa, RespostaIa> chamada, CircuitBreaker circuito) {
        this.cliente = null;
        this.chamada = chamada;
        this.circuito = circuito;
    }

    @PreDestroy
    void fechar() {
        if (cliente != null) {
            cliente.close();
        }
    }

    @Override
    public RespostaIa gerar(PromptIa prompt) {
        if (chamada == null) {
            throw new IllegalStateException("GEMINI_API_KEY não configurada");
        }
        try {
            return circuito.executeSupplier(() -> chamada.apply(prompt));
        } catch (RuntimeException e) {
            // Exceções do provedor podem incluir partes da solicitação ou credenciais.
            throw new IllegalStateException("Gemini indisponível");
        }
    }

    private static RespostaIa consultar(Client cliente, String modelo, PromptIa prompt) {
        var resposta = cliente.models.generateContent(modelo, prompt.conteudo(), CONFIG);
        var uso = resposta.usageMetadata().orElse(null);
        int entrada = uso == null ? 0 : uso.promptTokenCount().orElse(0);
        int total = uso == null ? 0 : uso.totalTokenCount().orElse(0);
        return new RespostaIa(resposta.text(), resposta.modelVersion().orElse(modelo), entrada, Math.max(0, total - entrada));
    }
}
