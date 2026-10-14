# si-insight-service

Serviço de insights: pseudonimiza os agregados, chama a API do Gemini só com prompts aprovados, valida a resposta e calcula a faixa do Score com histerese (RN-008). Consome `telemetry.ingested`, `consent.changed` e `subscription.changed`; publica `insight.score.changed`.

Parte do SeniorInsight — ver o Documento de Arquitetura de Software (seção 6) para as convenções.

Branches: `main` (produção) e `develop` (testes, branch padrão). Trabalhe na branch criada pelo card e abra PR para `develop` — ver [CONTRIBUTING.md](CONTRIBUTING.md).
