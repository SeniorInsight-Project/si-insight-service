package com.seniorinsight.insight.domain.semaforo;

public final class RegrasScore {
    private RegrasScore() {
    }

    public static Semaforo calcular(double valor, Semaforo anterior, Double ultimoValor) {
        if (!Double.isFinite(valor) || valor < 0 || valor > 100) {
            throw new IllegalArgumentException("Score fora da faixa");
        }
        Semaforo nova = faixa(valor);
        if (anterior != null && nova.ordinal() < anterior.ordinal()) {
            Semaforo anteriorBruta = ultimoValor == null ? anterior : faixa(ultimoValor);
            Semaforo menorConfirmada = anteriorBruta.ordinal() > nova.ordinal() ? anteriorBruta : nova;
            return menorConfirmada.ordinal() < anterior.ordinal() ? menorConfirmada : anterior;
        }
        return nova;
    }

    private static Semaforo faixa(double valor) {
        return valor < 40 ? Semaforo.VERDE : valor < 70 ? Semaforo.AMARELO : Semaforo.VERMELHO;
    }
}
