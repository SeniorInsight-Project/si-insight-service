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

    private final String chaveApi;
    private final String modelo;
    private final CircuitBreaker circuito = CircuitBreaker.of("gemini", CircuitBreakerConfig.custom()
            .slidingWindowSize(5).minimumNumberOfCalls(5).failureRateThreshold(60)
            .waitDurationInOpenState(Duration.ofMinutes(5)).build());

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
        try {
            return circuito.executeSupplier(() -> {
                try (Client cliente = Client.builder().apiKey(chaveApi).httpOptions(HttpOptions.builder()
                        .timeout(30_000).retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build()) {
                    var resposta = cliente.models.generateContent(modelo, prompt.conteudo(), CONFIG);
                    var uso = resposta.usageMetadata().orElse(null);
                    return new RespostaIa(resposta.text(), resposta.modelVersion().orElse(modelo),
                            uso == null ? 0 : uso.promptTokenCount().orElse(0),
                            uso == null ? 0 : Math.max(0,
                                    uso.totalTokenCount().orElse(0) - uso.promptTokenCount().orElse(0)));
                }
            });
        } catch (RuntimeException e) {
            // Exceções do provedor podem incluir partes da solicitação ou credenciais.
            throw new IllegalStateException("Gemini indisponível");
        }
    }
}
