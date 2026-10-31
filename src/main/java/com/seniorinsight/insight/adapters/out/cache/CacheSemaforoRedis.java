package com.seniorinsight.insight.adapters.out.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.seniorinsight.insight.application.porta.saida.CacheSemaforo;
import com.seniorinsight.insight.domain.semaforo.Semaforo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Semáforo no Redis em {@code insight:semaforo:{idosoId}}, com expiração {@code insight.semaforo.ttl} (15 min). */
@Component
class CacheSemaforoRedis implements CacheSemaforo {

    static final String PREFIXO_CHAVE = "insight:semaforo:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    CacheSemaforoRedis(StringRedisTemplate redis, @Value("${insight.semaforo.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<Semaforo> buscar(UUID idosoId) {
        return Optional.ofNullable(redis.opsForValue().get(chave(idosoId))).map(Semaforo::valueOf);
    }

    @Override
    public void guardar(UUID idosoId, Semaforo semaforo) {
        redis.opsForValue().set(chave(idosoId), semaforo.name(), ttl);
    }

    @Override
    public void remover(UUID idosoId) {
        redis.delete(chave(idosoId));
    }

    static String chave(UUID idosoId) {
        return PREFIXO_CHAVE + idosoId;
    }
}
