package kr.co.cudo.authoring.auth.service;

import io.jsonwebtoken.Jwts;
import kr.co.cudo.authoring.auth.dto.RoleClaimRequest;
import kr.co.cudo.authoring.auth.dto.RoleClaimResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.user.entity.MngAcctUser;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 권한 자가 부여 서비스.
 *
 * <p>인증은 되었으나 role 클레임이 없는 사용자가 관리자 공유 패스워드와 함께
 * 본인에게 {@link Role#WORKER} 또는 {@link Role#REVIEWER} 역할을 부여한다.
 *
 * <p>보안 (security-rules.md 준수):
 * <ul>
 *   <li><b>CWE-256 Plaintext Password Storage</b> — application.yml 에 BCrypt 해시만 저장 (cost ≥ 12).
 *       평문은 어디에도 저장되지 않는다.</li>
 *   <li><b>CWE-307 Improper Restriction of Excessive Authentication Attempts</b> — 호출자(userNo) 단위
 *       sliding window rate limiter 적용. 5회/분 초과 시 {@link ErrorCode#TOO_MANY_REQUESTS}.</li>
 *   <li><b>CWE-863 Incorrect Authorization</b> — actor 가 이미 WORKER/REVIEWER 라면 409 CONFLICT.
 *       PORTAL_USER 는 별도 채널이므로 본 API 진입 자체를 거절.</li>
 *   <li><b>CWE-117 Log Injection / CWE-532</b> — adminPassword 평문은 로그에 절대 출력하지 않으며,
 *       성공/실패 결과만 (userNo, role) 형식으로 로그한다.</li>
 *   <li><b>CWE-203 Observable Timing Discrepancy</b> — {@link BCryptPasswordEncoder#matches} 가 상수시간
 *       비교를 보장하므로 별도 조치 불필요.</li>
 * </ul>
 */
@Slf4j
@Service
public class RoleClaimService {

    /** 호출자 단위 sliding window. 5회/분 초과 시 429. */
    private static final int MAX_ATTEMPTS_PER_WINDOW = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** 새 토큰의 TTL — 기존 DevTokenService 의 기본값과 동일하게 1시간. */
    private static final long ISSUED_TOKEN_TTL_SECONDS = 3600L;

    private final UserRepository userRepository;
    private final LsUserRoleRepository lsUserRoleRepository;
    private final UserRoleResolver userRoleResolver;
    private final JwtKeyResolver keyResolver;
    private final PasswordEncoder passwordEncoder;
    private final String adminPasswordHash;
    private final String issuer;

    /** 호출자(sub=userNo) 단위 시도 카운터. */
    private final ConcurrentHashMap<String, AttemptCounter> attempts = new ConcurrentHashMap<>();

    public RoleClaimService(
            UserRepository userRepository,
            LsUserRoleRepository lsUserRoleRepository,
            UserRoleResolver userRoleResolver,
            JwtKeyResolver keyResolver,
            @Value("${authoring.auth.admin-claim-password-hash}") String adminPasswordHash,
            @Value("${authoring.jwt.issuer:klid-auth}") String issuer
    ) {
        this.userRepository = userRepository;
        this.lsUserRoleRepository = lsUserRoleRepository;
        this.userRoleResolver = userRoleResolver;
        this.keyResolver = keyResolver;
        this.passwordEncoder = new BCryptPasswordEncoder();
        if (adminPasswordHash == null || adminPasswordHash.isBlank()) {
            // 설정 누락 시 부트가 떠도 본 endpoint 는 항상 401 로 거절되도록 빈 문자열로 유지.
            // 평문이 들어오는 사고를 차단하기 위해 시작 형식만 검증한다.
            this.adminPasswordHash = "";
        } else {
            if (!isBcryptHash(adminPasswordHash)) {
                throw new IllegalStateException(
                        "authoring.auth.admin-claim-password-hash 는 BCrypt 해시여야 합니다 (시작 prefix $2a$/$2b$/$2y$).");
            }
            this.adminPasswordHash = adminPasswordHash;
        }
        this.issuer = (issuer == null || issuer.isBlank()) ? "klid-auth" : issuer;
    }

    private static boolean isBcryptHash(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }

    @Transactional("controlTransactionManager")
    public RoleClaimResponse claim(RoleClaimRequest req, TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        // PORTAL_USER 는 별도 채널 → 본 API 거절.
        if (req.role() == Role.PORTAL_USER) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "PORTAL_USER 역할은 본 API 로 부여할 수 없습니다.");
        }
        // CWE-863 — fail-closed 화이트리스트: INTERNAL 채널 + 역할 미보유(role==null) actor 만 허용.
        // PORTAL_USER(channel=PORTAL) 의 교차채널 자가부여(INTERNAL WORKER/REVIEWER 상승)와 이미
        // 권한 보유자(WORKER/REVIEWER)를 모두 거절한다. (deny-by-default)
        if (actor.channel() != Channel.INTERNAL || actor.role() != null) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 권한이 부여된 사용자입니다.");
        }

        // CWE-307 — rate limit 먼저 적용. 패스워드 검증/DB 작업 전에 차단.
        consumeAttemptOrReject(actor.sub());

        // CWE-203 — BCryptPasswordEncoder.matches 는 상수시간.
        // adminPasswordHash 가 비어 있어도 matches 는 false 를 반환하지만 명시적으로 차단.
        if (adminPasswordHash.isEmpty() ||
                !passwordEncoder.matches(req.adminPassword(), adminPasswordHash)) {
            // CWE-117 — JWT subject 는 signed 클레임이지만 방어적으로 CR/LF 제거.
            log.warn("[RoleClaim] denied userNo={} role={} reason=invalid_password",
                    sanitize(actor.sub()), req.role());
            throw new CustomException(ErrorCode.UNAUTHORIZED,
                    "관리자 패스워드가 일치하지 않습니다.");
        }

        Long userNo;
        try {
            userNo = Long.parseLong(actor.sub());
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "userNo 형식이 올바르지 않습니다.");
        }

        Optional<MngAcctUser> userOpt = userRepository.findByUserNo(userNo);
        if (userOpt.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        MngAcctUser user = userOpt.get();

        // CWE-863 — JWT role 이 비어있어도(stale token) 저작도구 소유 역할이 이미 있으면 409.
        if (lsUserRoleRepository.findByUserNo(userNo).isPresent()) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 권한이 부여된 사용자입니다.");
        }

        // 역할 부여 — LS_USER_ROLE 원자 upsert (역할 단일화, PK race 안전).
        lsUserRoleRepository.upsertRole(userNo, req.role().name());

        // Phase 3 — 인가 역할 캐시 무효화(AFTER_COMMIT). 직전까지 무권한(null)이라 캐시엔 항목이
        // 없을(unless=null) 가능성이 크지만, 일관성을 위해 부여 시에도 커밋 후 evict 한다.
        final long evictUserNo = userNo;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    userRoleResolver.evict(evictUserNo);
                }
            });
        } else {
            userRoleResolver.evict(evictUserNo);
        }

        // 새 토큰 발급 — channel=INTERNAL (관제 채널 본 API 진입 사용자), role=신규.
        String token = issueInternalToken(userNo, req.role(), user.getUserNm());

        // userNo 는 Long 으로 파싱 완료된 안전한 값. role 은 enum.
        log.info("[RoleClaim] granted userNo={} role={}", userNo, req.role().name());

        return new RoleClaimResponse(
                token,
                req.role().name(),
                userNo,
                user.getUserNm()
        );
    }

    private String issueInternalToken(Long userNo, Role role, String name) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ISSUED_TOKEN_TTL_SECONDS);
        return Jwts.builder()
                .subject(String.valueOf(userNo))
                .issuer(issuer)
                .claim("role", role.name())
                .claim("channel", Channel.INTERNAL.name())
                .claim("name", name)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(keyResolver.resolve(), Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 호출자(sub) 단위 sliding window rate limit.
     * 윈도우 시작 이후 시도가 {@link #MAX_ATTEMPTS_PER_WINDOW} 회를 초과하면 429.
     */
    private void consumeAttemptOrReject(String key) {
        Instant now = Instant.now();
        AttemptCounter counter = attempts.compute(key, (k, prev) -> {
            if (prev == null || now.isAfter(prev.windowStart.plus(WINDOW))) {
                return new AttemptCounter(now, new AtomicInteger(0));
            }
            return prev;
        });
        int current = counter.count.incrementAndGet();
        if (current > MAX_ATTEMPTS_PER_WINDOW) {
            log.warn("[RoleClaim] rate-limited userNo={} attempts={}", sanitize(key), current);
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "시도 횟수가 제한을 초과했습니다. 잠시 후 다시 시도해주세요.");
        }
    }

    /** CWE-117 Log Injection 방어 — CR/LF 등 제어문자 제거. */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        // 32자 이상은 잘라내고 (예측 가능 길이), 제어문자(개행/탭/캐리지리턴) 제거.
        String trimmed = value.length() > 32 ? value.substring(0, 32) : value;
        return trimmed.replaceAll("[\\r\\n\\t]", "_");
    }

    /** 테스트용 — 상태 초기화. 패키지 내부(테스트 전용)로만 노출한다. */
    void resetAttempts() {
        attempts.clear();
    }

    private record AttemptCounter(Instant windowStart, AtomicInteger count) {}

    /** 어떤 권한이 부여 가능한지 화이트리스트로 노출 (재사용 가능). */
    public static List<Role> allowedClaimRoles() {
        return List.of(Role.WORKER, Role.REVIEWER);
    }
}
