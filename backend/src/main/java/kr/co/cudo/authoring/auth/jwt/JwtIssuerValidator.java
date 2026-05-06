package kr.co.cudo.authoring.auth.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtIssuerValidator {

    private final Set<String> allowed;

    public JwtIssuerValidator(@Value("${authoring.jwt.allowed-issuers:klid,klid-portal,klid-auth}") List<String> allowedIssuers) {
        this.allowed = allowedIssuers.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return false;
        }
        return allowed.contains(issuer);
    }
}
