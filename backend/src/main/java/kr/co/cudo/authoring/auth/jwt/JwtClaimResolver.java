package kr.co.cudo.authoring.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class JwtClaimResolver {

    private final JwtKeyResolver keyResolver;

    public Resolved resolve(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(keyResolver.resolve())
                .build()
                .parseSignedClaims(token);
        Claims body = jws.getPayload();

        String roleStr = body.get("role", String.class);
        String channelStr = body.get("channel", String.class);
        Role role = roleStr == null ? null : Role.valueOf(roleStr);
        Channel channel = channelStr == null ? Channel.INTERNAL : Channel.valueOf(channelStr);
        Instant exp = body.getExpiration() == null ? null : Instant.ofEpochMilli(body.getExpiration().getTime());

        TokenClaims claims = new TokenClaims(body.getSubject(), role, channel, exp);
        String name = body.get("name", String.class);
        return new Resolved(claims, body.getIssuer(), name);
    }

    public record Resolved(TokenClaims claims, String issuer, String name) {}
}
