package com.seniorinsight.insight.domain.semaforo;

public final class RegrasScore {
    private static final int LIMITE_ATENCAO = 40;
    private static final int LIMITE_CRITICO = 70;
    private RegrasScore() {
    }

    public static Semaforo calcular(double valor, Semaforo anterior, Double ultimoValor) {
        if (!Double.isFinite(valor) || valor < 0 || valor > 100) {
            throw new IllegalArgumentException("Score fora da faixa");
        }
        Semaforo nova = faixa(valor);
        if (anterior == null || nivel(nova) >= nivel(anterior)) {
            return nova;
        }
        Semaforo ultima = ultimoValor == null ? anterior : faixa(ultimoValor);
        return confirmadaPorDuasLeituras(nova, ultima, anterior);
    }

    private static Semaforo confirmadaPorDuasLeituras(Semaforo nova, Semaforo ultima, Semaforo atual) {
        Semaforo maiorRisco = nivel(nova) >= nivel(ultima) ? nova : ultima;
        return nivel(maiorRisco) < nivel(atual) ? maiorRisco : atual;
    }

    private static int nivel(Semaforo faixa) {
        return switch (faixa) {
            case VERDE -> 0;
            case AMARELO -> 1;
            case VERMELHO -> 2;
        };
    }

    private static Semaforo faixa(double valor) {
        if (valor >= LIMITE_CRITICO) {
            return Semaforo.VERMELHO;
        }
        if (valor >= LIMITE_ATENCAO) {
            return Semaforo.AMARELO;
        }
        return Semaforo.VERDE;
    }
}
