# si-insight-service

Serviço de insights: pseudonimiza os agregados, chama a API do Gemini só com prompts aprovados, valida a resposta e calcula a faixa do Score com histerese (RN-008). Consome `telemetry.ingested`, `consent.changed` e `subscription.changed`; publica `insight.score.changed`.

Parte do SeniorInsight — ver o Documento de Arquitetura de Software (seção 6) para as convenções.

Branches: `main` (produção) e `develop` (testes, branch padrão). Trabalhe na branch criada pelo card e abra PR para `develop` — ver [CONTRIBUTING.md](CONTRIBUTING.md).

## Rodar localmente

Pré-requisitos: Java 21, Maven e Docker.

```bash
mvn verify                 # testes unitários, de integração (Testcontainers) e de arquitetura (ArchUnit)
mvn spring-boot:test-run   # sobe o serviço com PostgreSQL, RabbitMQ e Redis do Testcontainers
```

Fora do Testcontainers, copie o `.env.example` para `.env` e preencha as variáveis. Nenhum segredo fica no código: a chave do Gemini vem só de `GEMINI_API_KEY` (Secret Manager em produção).

## Estrutura

| Pacote | Conteúdo |
|---|---|
| `domain` | Java puro, sem Spring nem JPA (garantido pelo `ArquiteturaTest`) |
| `application` | Portas de entrada e saída e casos de uso |
| `adapters.in` | Resource server (JWT do Keycloak) e consumidor da fila `insight.q` |
| `adapters.out` | Outbox e relay para a exchange `si.events`, cache do semáforo no Redis, persistência e cliente do Gemini |

- **Banco:** schema `insight` (Flyway), com `score`, `chamada_ia`, `versao_prompt`, `projecao_agregado` (do telemetry), `projecao_direito_acesso` (consentimento e billing), `evento_recebido` (idempotência do consumidor) e `outbox_evento`.
- **Mensageria:** publica pela outbox na exchange `si.events`; consome `telemetry.ingested`, `consent.changed` e `subscription.changed` pela fila `insight.q`, com ack manual, deduplicação pelo `message_id` e dead-letter em `insight.dlx` → `insight.q.dlq`.
- **Cache:** semáforo em `insight:semaforo:{idosoId}`, TTL de 15 minutos.

## Docker Compose

Trecho para o `compose.yaml` do si-infra (os serviços `postgres`, `rabbitmq`, `redis` e `keycloak` já existem lá):

```yaml
  insight-service:
    build:
      context: ../si-insight-service
      dockerfile: Dockerfile
    restart: unless-stopped
    environment:
      SERVER_PORT: 8080
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: ${DB_NAME:-seniorinsight}
      DB_USERNAME: ${DB_USERNAME:-seniorinsight}
      DB_PASSWORD: ${DB_PASSWORD:-seniorinsight}
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: 5672
      RABBITMQ_VHOST: /
      RABBITMQ_USERNAME: ${RABBITMQ_USERNAME:-seniorinsight}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-seniorinsight}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      KEYCLOAK_ISSUER_URI: http://localhost:${KEYCLOAK_PORT:-8180}/realms/seniorinsight
      SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI: http://keycloak:8080/realms/seniorinsight/protocol/openid-connect/certs
      GEMINI_API_KEY: ${GEMINI_API_KEY:-}
    expose:
      - "8080"
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1"]
      interval: 15s
      timeout: 3s
      retries: 5
      start_period: 60s
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      redis:
        condition: service_healthy
      keycloak:
        condition: service_started
```
