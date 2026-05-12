package kr.co.cudo.authoring.dev.service;

import io.jsonwebtoken.Jwts;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.dev.dto.DevTokenRequest;
import kr.co.cudo.authoring.dev.dto.DevTokenResponse;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [개발/검수 전용] 테스트 JWT 발급 서비스.
 *
 * <p>운영(prd) 환경에서는 {@code @Profile("!prd")} 로 빈 자체가 등록되지 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li>HS256 명시 ({@code Jwts.SIG.HS256}) — alg none 차단 (CWE-347).</li>
 *   <li>secret 은 {@link JwtKeyResolver} 통해서만 접근 — 응답/로그에 절대 노출 X (CWE-200/798).</li>
 *   <li>role-channel 일관성 검증: PORTAL_USER ↔ PORTAL, REVIEWER/WORKER ↔ INTERNAL.</li>
 *   <li>expSeconds 60 ~ 86400(24h) 이중 가드 (Bean Validation + Service).</li>
 *   <li>로그는 발급 사실(role/channel/userNo)만, 토큰 자체는 앞 12자만 마스킹 출력.</li>
 * </ul>
 */
@Slf4j
@Service
@Profile("!prd")
public class DevTokenService {

    private static final int DEFAULT_EXP_SECONDS = 3600;
    private static final int MIN_EXP_SECONDS = 60;
    private static final int MAX_EXP_SECONDS = 86400;
    // 기본 USER_NO 는 dev-seed.sql 과 1:1 매칭되어야 한다.
    //   1001=REVIEWER(김검수) · 2001=WORKER(최라벨) · 3001=PORTAL_USER(홍길동)
    // 과거 1002(WORKER)·2001(PORTAL) 매핑은 시드와 어긋나 WORKER 토큰의 sub 가
    // 실제 REVIEWER 사용자를 가리켜 LABELER 배정 0건이 반환되는 버그가 있었다.
    private static final String DEFAULT_USER_NO_REVIEWER = "1001";
    private static final String DEFAULT_USER_NO_WORKER = "2001";
    private static final String DEFAULT_USER_NO_PORTAL = "3001";
    private static final String DEFAULT_NAME_REVIEWER = "검수자";
    private static final String DEFAULT_NAME_WORKER = "라벨러";
    private static final String DEFAULT_NAME_PORTAL = "포털사용자";

    private final JwtKeyResolver keyResolver;
    private final UserRepository userRepository;
    private final String issuer;

    public DevTokenService(
            JwtKeyResolver keyResolver,
            UserRepository userRepository,
            @Value("${authoring.jwt.allowed-issuers:klid-auth,klid,klid-portal}") List<String> allowedIssuers
    ) {
        this.keyResolver = keyResolver;
        this.userRepository = userRepository;
        this.issuer = resolveIssuer(allowedIssuers);
    }

    private static String resolveIssuer(List<String> allowedIssuers) {
        if (allowedIssuers == null) {
            return "klid-auth";
        }
        return allowedIssuers.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse("klid-auth");
    }

    public DevTokenResponse issue(DevTokenRequest req) {
        Role role = req.role();
        Channel channel = req.channel();
        validateRoleChannelConsistency(role, channel);

        int expSeconds = clampExp(req.expSeconds());
        String userNo = resolveUserNo(req.userNo(), role);
        String name = resolveName(req.name(), role, userNo);

        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expSeconds);

        String token = Jwts.builder()
                .subject(userNo)
                .issuer(issuer)
                .claim("role", role.name())
                .claim("channel", channel.name())
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(keyResolver.resolve(), Jwts.SIG.HS256)
                .compact();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userNo);
        claims.put("iss", issuer);
        claims.put("role", role.name());
        claims.put("channel", channel.name());
        claims.put("name", name);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", exp.getEpochSecond());

        log.info("[DevToken] issued role={} channel={} userNo={} expSec={} tokenPrefix={}",
                role, channel, userNo, expSeconds, mask(token));

        return new DevTokenResponse(
                token,
                "Bearer",
                exp.toEpochMilli(),
                claims,
                "Bearer " + token
        );
    }

    private static void validateRoleChannelConsistency(Role role, Channel channel) {
        boolean ok = switch (role) {
            case REVIEWER, WORKER -> channel == Channel.INTERNAL;
            case PORTAL_USER -> channel == Channel.PORTAL;
        };
        if (!ok) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT,
                    "role-channel 조합이 유효하지 않습니다. (role=" + role + ", channel=" + channel + ")");
        }
    }

    private static int clampExp(Integer expSeconds) {
        if (expSeconds == null) {
            return DEFAULT_EXP_SECONDS;
        }
        // Bean Validation 이 1차 가드, 서비스 레벨 이중 방어.
        if (expSeconds < MIN_EXP_SECONDS || expSeconds > MAX_EXP_SECONDS) {
            throw new CustomException(
                    ErrorCode.INVALID_INPUT,
                    "expSeconds 는 " + MIN_EXP_SECONDS + " ~ " + MAX_EXP_SECONDS + " 범위여야 합니다.");
        }
        return expSeconds;
    }

    private static String resolveUserNo(String userNo, Role role) {
        if (userNo != null && !userNo.isBlank()) {
            return userNo.trim();
        }
        return switch (role) {
            case REVIEWER -> DEFAULT_USER_NO_REVIEWER;
            case WORKER -> DEFAULT_USER_NO_WORKER;
            case PORTAL_USER -> DEFAULT_USER_NO_PORTAL;
        };
    }

    private String resolveName(String name, Role role, String userNo) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (userNo != null && !userNo.isBlank()) {
            try {
                Long userNoLong = Long.parseLong(userNo.trim());
                Optional<MngAcctUser> user = userRepository.findByUserNo(userNoLong);
                if (user.isPresent() && user.get().getUserNm() != null
                        && !user.get().getUserNm().isBlank()) {
                    return user.get().getUserNm();
                }
            } catch (NumberFormatException ignored) {
                // userNo 가 숫자가 아니면 fallback
            }
        }
        return defaultNameByRole(role);
    }

    private static String defaultNameByRole(Role role) {
        return switch (role) {
            case REVIEWER -> DEFAULT_NAME_REVIEWER;
            case WORKER -> DEFAULT_NAME_WORKER;
            case PORTAL_USER -> DEFAULT_NAME_PORTAL;
        };
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 12) {
            return "***";
        }
        return token.substring(0, 12) + "...";
    }
}
