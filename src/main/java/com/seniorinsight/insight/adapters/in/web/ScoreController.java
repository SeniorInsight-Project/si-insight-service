package com.seniorinsight.insight.adapters.in.web;

import java.util.UUID;

import com.seniorinsight.insight.application.porta.saida.VinculoFamiliar;
import com.seniorinsight.insight.application.servico.ConsultarScore;
import com.seniorinsight.insight.application.servico.ConsultarScore.Resultado;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ScoreController {
    private final ConsultarScore consultar;

    ScoreController(ConsultarScore consultar) {
        this.consultar = consultar;
    }

    @GetMapping("/v1/idosos/{idosoId}/score-risco/recente")
    Resultado recente(@PathVariable UUID idosoId, @AuthenticationPrincipal Jwt jwt) {
        try {
            return consultar.buscar(idosoId, jwt.getSubject(), jwt.getTokenValue());
        } catch (VinculoFamiliar.Indisponivel falha) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, falha.getMessage());
        }
    }
}
