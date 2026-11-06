package com.seniorinsight.insight.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class ConversorRolesKeycloakTest {

    private final ConversorRolesKeycloak conversor = new ConversorRolesKeycloak();

    @Test
    void mapeiaRolesDeRealmParaAuthoritiesRole() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("familiar", "offline_access"))).build();

        assertThat(conversor.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_familiar", "ROLE_offline_access");
    }

    @Test
    void tokenSemRealmAccessNaoTemRoles() {
        assertThat(conversor.convert(token().build())).isEmpty();
        assertThat(conversor.convert(token().claim("realm_access", Map.of()).build())).isEmpty();
    }

    @Test
    void autenticacaoUsaOSubComoNomeEJuntaScopesERoles() {
        Jwt jwt = token()
                .claim("scope", "openid profile")
                .claim("realm_access", Map.of("roles", List.of("idoso")))
                .build();

        AbstractAuthenticationToken autenticacao = SegurancaConfig.conversorAutenticacao().convert(jwt);

        assertThat(autenticacao.getName()).isEqualTo("sub-123");
        assertThat(autenticacao.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile", "ROLE_idoso");
    }

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("sub-123");
    }
}
