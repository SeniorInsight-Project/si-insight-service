package com.seniorinsight.insight.adapters.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.seniorinsight.insight.TesteIntegracao;
import com.seniorinsight.insight.application.porta.saida.CacheSemaforo;
import com.seniorinsight.insight.domain.semaforo.Semaforo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

@TesteIntegracao
class CacheSemaforoRedisIntegracaoTest {

    @Autowired
    CacheSemaforo cache;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void guardaNaChaveDoIdosoComTtlDe15Minutos() {
        UUID idosoId = UUID.randomUUID();

        cache.guardar(idosoId, Semaforo.AMARELO);

        String chave = "insight:semaforo:" + idosoId;
        assertThat(redis.opsForValue().get(chave)).isEqualTo("AMARELO");
        assertThat(Duration.ofSeconds(redis.getExpire(chave, TimeUnit.SECONDS)))
                .isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));
        assertThat(cache.buscar(idosoId)).contains(Semaforo.AMARELO);
    }

    @Test
    void idosoSemSemaforoNoCacheRetornaVazio() {
        UUID idosoId = UUID.randomUUID();
        cache.guardar(idosoId, Semaforo.VERDE);

        cache.remover(idosoId);

        assertThat(cache.buscar(idosoId)).isEmpty();
        assertThat(cache.buscar(UUID.randomUUID())).isEmpty();
    }
}
