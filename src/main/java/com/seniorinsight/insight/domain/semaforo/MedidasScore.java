package com.seniorinsight.insight.domain.semaforo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MedidasScore {
    private MedidasScore() {
    }

    public static int diasValidos(List<Agregado> agregados) {
        return (int) agregados.stream().filter(a -> a.horasComDados() >= 12)
                .map(Agregado::dia).distinct().count();
    }

    public static Map<String, String> explicacoes(List<Agregado> agregados) {
        var explicacoes = new TreeMap<String, String>();
        LocalDate ultimoDia = agregados.stream().filter(a -> a.horasComDados() >= 12)
                .map(Agregado::dia).max(LocalDate::compareTo).orElse(null);
        for (Agregado a : agregados) {
            if (!a.dia().equals(ultimoDia) || a.linhaBase14Dias() == null || a.linhaBase14Dias() <= 0) {
                continue;
            }
            long variacao = Math.round((a.valor() / a.linhaBase14Dias() - 1) * 100);
            switch (a.tipo()) {
                case "steps" -> {
                    if (variacao <= -10) {
                        explicacoes.put("PASSOS_QUEDA", "Os passos caíram " + (-variacao) + "% em relação à linha de base.");
                    }
                }
                case "resting_heart_rate" -> {
                    if (variacao >= 10) {
                        explicacoes.put("BATIMENTO_ELEVADO", "O batimento em repouso ficou " + variacao
                                + "% acima da linha de base.");
                    }
                }
                case "oxygen_saturation" -> {
                    if (variacao <= -5) {
                        explicacoes.put("OXIGENACAO_QUEDA", "A oxigenação ficou abaixo da linha de base.");
                    }
                }
                case "sleep" -> {
                    if (variacao <= -10) {
                        explicacoes.put("SONO_REDUZIDO", "O tempo de sono ficou abaixo da linha de base.");
                    }
                }
                default -> { }
            }
        }
        return explicacoes;
    }

    public record Agregado(LocalDate dia, String tipo, double valor, Double linhaBase14Dias, int horasComDados) {
    }
}
