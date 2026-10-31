package com.seniorinsight.insight.application.porta.saida;

/**
 * Modelo de linguagem usado para gerar os insights (Gemini). Recebe só prompts aprovados ({@code versao_prompt}) com
 * dados já pseudonimizados.
 */
public interface ClienteIa {

    RespostaIa gerar(PromptIa prompt);

    /** @param versaoPrompt identificador da versão aprovada do prompt; {@code conteudo} é o texto enviado ao modelo. */
    record PromptIa(String versaoPrompt, String conteudo) {
    }

    record RespostaIa(String conteudo, String modelo, int tokensEntrada, int tokensSaida) {
    }
}
