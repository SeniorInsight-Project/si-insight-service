package com.seniorinsight.insight.application.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seniorinsight.insight.application.porta.saida.ClienteIa;
import com.seniorinsight.insight.application.porta.saida.ClienteIa.PromptIa;
import com.seniorinsight.insight.application.porta.saida.ClienteIa.RespostaIa;
import com.seniorinsight.insight.application.porta.saida.PerfilScore;
import com.seniorinsight.insight.application.porta.saida.PublicadorEventos;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Contexto;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Prompt;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Score;
import com.seniorinsight.insight.application.porta.saida.ScoreRepositorio.Trabalho;
import com.seniorinsight.insight.domain.semaforo.MedidasScore.Agregado;
import com.seniorinsight.insight.domain.semaforo.Semaforo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionOperations;

class CalcularScoreTest {
    private final UUID idoso = UUID.randomUUID();
    private final UUID chamada = UUID.randomUUID();
    private final UUID prompt = UUID.randomUUID();
    private final ScoreRepositorio scores = mock(ScoreRepositorio.class);
    private final ClienteIa ia = mock(ClienteIa.class);
    private final PerfilScore perfil = mock(PerfilScore.class);
    private final PublicadorEventos eventos = mock(PublicadorEventos.class);
    private final ClassPathResource arquivo = new ClassPathResource("prompts/score-v1.txt");
    private final Trabalho trabalho = new Trabalho(idoso, 1);
    private CalcularScore calcular;

    @BeforeEach
    void preparar() throws Exception {
        calcular = new CalcularScore(scores, perfil, ia, eventos, TransactionOperations.withoutTransaction(),
                new ObjectMapper().findAndRegisterModules(), arquivo, "gemini", 100_000, 1_000, 1_000);
        when(scores.contexto(idoso)).thenReturn(new Contexto(1, Set.of("PASSOS"), List.of(
                new Agregado(LocalDate.parse("2026-11-04"), "steps", 5_000, 5_000.0, 12),
                new Agregado(LocalDate.parse("2026-11-05"), "steps", 5_000, 5_000.0, 12),
                new Agregado(LocalDate.parse("2026-11-06"), "steps", 1_000, 5_000.0, 12))));
        when(scores.promptAprovado()).thenReturn(Optional.of(new Prompt(prompt,
                arquivo.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))));
        when(perfil.buscar(idoso)).thenReturn(Optional.of(new PerfilScore.Perfil(82, List.of("diabetes"))));
        when(scores.reservarChamada(eq(idoso), eq(prompt), eq("gemini"), anyLong(), anyLong()))
                .thenReturn(Optional.of(chamada));
    }

    @Test
    void calculaFaixaLocalmenteEEnviaSomenteContextoSemIdentificador() {
        when(ia.gerar(any())).thenReturn(new RespostaIa("""
                {"score":65,"fatores":["PASSOS_QUEDA"]}
                """, "gemini", 100, 20));
        calcular.calcular(trabalho);

        var enviado = ArgumentCaptor.forClass(PromptIa.class);
        verify(ia).gerar(enviado.capture());
        assertThat(enviado.getValue().conteudo()).contains("diabetes", "PASSOS_QUEDA")
                .doesNotContain(idoso.toString());
        var salvo = ArgumentCaptor.forClass(Score.class);
        verify(scores).guardar(eq(idoso), salvo.capture(), eq(chamada), anyString());
        assertThat(salvo.getValue().faixa()).isEqualTo(Semaforo.AMARELO);
        assertThat(salvo.getValue().fatores()).singleElement().asString().contains("80%");
        verify(eventos).publicar(any());
    }

    @Test
    void respostaForaDaFaixaNaoSubstituiUltimoScore() {
        when(ia.gerar(any())).thenReturn(new RespostaIa("""
                {"score":101,"fatores":[]}
                """, "gemini", 100, 20));
        calcular.calcular(trabalho);

        verify(scores, never()).guardar(any(), any(), any(), any());
        verify(scores).registrarChamada(eq(chamada), eq("gemini"), eq("RESPOSTA_INVALIDA"),
                eq(100), eq(20), org.mockito.ArgumentMatchers.anyInt(), anyLong());
        verify(eventos, never()).publicar(any());
    }

    @Test
    void promptSemAprovacaoNaoChegaAoModelo() {
        when(scores.promptAprovado()).thenReturn(Optional.empty());
        calcular.calcular(trabalho);
        verify(ia, never()).gerar(any());
    }
}
