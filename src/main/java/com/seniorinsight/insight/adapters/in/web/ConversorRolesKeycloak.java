package com.seniorinsight.insight.adapters.in.web;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Converte as roles de realm do Keycloak ({@code realm_access.roles}) em authorities {@code ROLE_<role>}, mantendo o
 * nome da role como está no realm (ex.: {@code familiar} vira {@code ROLE_familiar}).
 */
public class ConversorRolesKeycloak implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String CLAIM_REALM_ACCESS = "realm_access";
    static final String PREFIXO = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaims().get(CLAIM_REALM_ACCESS) instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(PREFIXO + role))
                .toList();
    }
}
