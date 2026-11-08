package com.seniorinsight.insight.domain.semaforo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegrasScoreTest {
    @Test
    void aplicaLimitesEExigeDoisValoresAbaixoDoLimiteDaFaixa() {
        assertThat(RegrasScore.calcular(18, null, null)).isEqualTo(Semaforo.VERDE);
        assertThat(RegrasScore.calcular(40, Semaforo.VERDE, 18.0)).isEqualTo(Semaforo.AMARELO);
        assertThat(RegrasScore.calcular(70, Semaforo.AMARELO, 40.0)).isEqualTo(Semaforo.VERMELHO);
        assertThat(RegrasScore.calcular(32, Semaforo.VERMELHO, 72.0)).isEqualTo(Semaforo.VERMELHO);
        assertThat(RegrasScore.calcular(32, Semaforo.VERMELHO, 51.0)).isEqualTo(Semaforo.AMARELO);
        assertThat(RegrasScore.calcular(32, Semaforo.AMARELO, 32.0)).isEqualTo(Semaforo.VERDE);
    }
}
