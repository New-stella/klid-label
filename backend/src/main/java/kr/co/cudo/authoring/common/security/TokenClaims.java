package kr.co.cudo.authoring.common.security;

import java.time.Instant;

public record TokenClaims(String sub, Role role, Channel channel, Instant exp) {
}
