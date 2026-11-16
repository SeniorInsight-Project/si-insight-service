package com.seniorinsight.insight.adapters.out.care;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.seniorinsight.insight.application.porta.saida.PerfilScore;
import com.seniorinsight.insight.application.porta.saida.VinculoFamiliar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class CareScoreRest implements PerfilScore, VinculoFamiliar {
    private static final Set<String> PAPEIS = Set.of("FAMILIAR_ADMINISTRADOR", "FAMILIAR_APOIO");
    private static final Set<String> COMORBIDADES = Set.of("hipertensao", "diabetes", "cardiopatia",
            "dpoc", "osteoporose", "historico de quedas");

    private final RestClient care;
    private final RestClient tokens;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    CareScoreRest(RestClient.Builder builder, @Value("${insight.care.base-url}") String baseUrl,
            @Value("${insight.care.token-url}") String tokenUrl,
            @Value("${insight.care.client-id}") String clientId,
            @Value("${insight.care.client-secret}") String clientSecret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.tokens = builder.clone().requestFactory(factory).build();
        this.care = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public boolean confirmado(UUID idosoId, String sub, String token) {
        try {
            var vinculo = care.get().uri("/internal/idosos/{idosoId}/vinculo/{sub}", idosoId, sub)
                    .headers(h -> h.setBearerAuth(token)).retrieve().body(Vinculo.class);
            return vinculo != null && vinculo.papel() != null && PAPEIS.contains(vinculo.papel());
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden falha) {
            return false;
        } catch (RestClientException falha) {
            throw new VinculoFamiliar.Indisponivel();
        }
    }

    @Override
    public Optional<Perfil> buscar(UUID idosoId) {
        if (tokenUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            return Optional.empty();
        }
        try {
            var parametros = new LinkedMultiValueMap<String, String>();
            parametros.add("grant_type", "client_credentials");
            var token = tokens.post().uri(tokenUrl).headers(h -> h.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(parametros)
                    .retrieve().body(Token.class);
            if (token == null || token.access_token() == null || token.access_token().isBlank()) {
                return Optional.empty();
            }
            var dados = care.get().uri("/internal/idosos/{id}/contexto-score", idosoId)
                    .headers(h -> h.setBearerAuth(token.access_token())).retrieve().body(PerfilCare.class);
            if (dados == null || dados.idade() == null || dados.idade() < 0 || dados.idade() > 130
                    || dados.comorbidades() == null) {
                return Optional.empty();
            }
            return Optional.of(new Perfil(dados.idade(), dados.comorbidades().stream()
                    .filter(java.util.Objects::nonNull).map(CareScoreRest::normalizar)
                    .filter(COMORBIDADES::contains).distinct().sorted().toList()));
        } catch (RestClientException falha) {
            return Optional.empty();
        }
    }

    private static String normalizar(String texto) {
        return Normalizer.normalize(texto.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private record Vinculo(String papel) {
    }

    private record Token(String access_token) {
    }

    private record PerfilCare(Integer idade, List<String> comorbidades) {
    }
}
