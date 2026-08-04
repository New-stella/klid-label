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
import kr.co.cudo.authoring.user.entity.LsAcntUser;
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

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 권한 자가 부여 서비스.
 *
 * <p>인증은 되었으나 role 클레임이 없는 사용자가 관리자 공유 패스워드와 함께
 * 본인에게 {@link Role#WORKER} 또는 {@link Role#REVIEWER} 역할을 부여한다. 사용자 마스터
 * 행이 없으면 이 시점에 <b>자동등록</b>된다(V169 — 아래 {@code claim} 참조).
 *
 * <h3>★ REVIEWER 자가부여 개방 (2026-08-04 사용자 확정 — 되돌리지 말 것)</h3>
 * <p>{@link #allowedClaimRoles()} 는 이제 {@code WORKER}·{@code REVIEWER} 를 모두 허용한다.
 * <b>사용자가 인지하고 수용한 잔여 위험</b>: 관리자 공유 패스워드를 아는 사람은 누구나 REVIEWER
 * (사용자 관리·시스템 설정·검수 승인)가 된다 — 즉 <b>그 패스워드의 관리 수준이 시스템 전체의 권한
 * 경계</b>다.
 *
 * <p><b>구 정책(폐기) — 경위 보존</b>: 원래는 A-ISSUE-17(CWE-269/CWE-1392)을 근거로 WORKER 단일
 * 화이트리스트였고, REVIEWER 는 "기존 REVIEWER 의 {@code /manage} 경로로만 부여"하도록 했다. 그러나
 * 그 전제(=이미 REVIEWER 인 사람이 존재한다)가 <b>온프렘 신규 설치에서 성립하지 않았다</b> — 최초
 * REVIEWER 를 만들 정규 경로가 없어 운영 문서가 dev 편의 경로({@code /dev/login})를 부트스트랩으로
 * 안내하고 있었다(그쪽이 더 위험하다). 정책을 뒤집은 것이 아니라, 부트스트랩 결함을 정규 경로로
 * 흡수한 것이다. ⚠ "보안 강화" 명목으로 WORKER 단일로 되돌리지 말 것.
 *
 * <p><b>개방과 함께 그대로 유지되는 방어</b>(하나라도 빼면 개방이 성립하지 않는다):
 * rate limit(CWE-307) · BCrypt 상수시간 비교(CWE-203) · 평문 패스워드 로그 금지(CWE-532) ·
 * INTERNAL 채널 + {@code role==null} 게이트(CWE-863) · {@code PORTAL_USER} 거절.
 *
 * <p>보안 (security-rules.md 준수):
 * <ul>
 *   <li><b>CWE-256 Plaintext Password Storage</b> — application.yml 에 BCrypt 해시만 저장 (cost ≥ 12).
 *       평문은 어디에도 저장되지 않는다.</li>
 *   <li><b>CWE-307 Improper Restriction of Excessive Authentication Attempts</b> —
 *       {@link RoleClaimRateLimiter} 가 계정 축 + 엔드포인트 전역 축을, 노드 공유 저장소와 함께 강제한다.
 *       초과 시 {@link ErrorCode#TOO_MANY_REQUESTS}.</li>
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

    /** 새 토큰의 TTL — 기존 DevTokenService 의 기본값과 동일하게 1시간. */
    private static final long ISSUED_TOKEN_TTL_SECONDS = 3600L;

    /** {@code LS_ACNT_USER.USER_ID} 컬럼 길이(명V20). DTO {@code @Size} 와 같은 값이어야 한다. */
    static final int MAX_USER_ID_LENGTH = 20;
    /** {@code LS_ACNT_USER.USER_NM} 컬럼 길이(명V100). DTO {@code @Size} 와 같은 값이어야 한다. */
    static final int MAX_USER_NM_LENGTH = 100;

    private final UserRepository userRepository;
    private final LsUserRoleRepository lsUserRoleRepository;
    private final UserRoleResolver userRoleResolver;
    private final JwtKeyResolver keyResolver;
    private final RoleClaimRateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final String adminPasswordHash;
    private final String issuer;

    public RoleClaimService(
            UserRepository userRepository,
            LsUserRoleRepository lsUserRoleRepository,
            UserRoleResolver userRoleResolver,
            JwtKeyResolver keyResolver,
            RoleClaimRateLimiter rateLimiter,
            @Value("${authoring.auth.admin-claim-password-hash}") String adminPasswordHash,
            @Value("${authoring.jwt.issuer:klid-auth}") String issuer
    ) {
        this.userRepository = userRepository;
        this.lsUserRoleRepository = lsUserRoleRepository;
        this.userRoleResolver = userRoleResolver;
        this.keyResolver = keyResolver;
        this.rateLimiter = rateLimiter;
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
        // ① CWE-269 — 자가부여 가능 역할 화이트리스트(WORKER·REVIEWER)를 **가장 먼저** 강제한다.
        //    화이트리스트 참조가 없으면 Role enum 확장 시 새 역할이 자동으로 자가부여 대상이 된다.
        //    rate limit 보다 앞에 두어야 잘못된 role 시도가 정상 사용자의 쿼터를 소모하지 않는다.
        if (!allowedClaimRoles().contains(req.role())) {
            log.warn("[RoleClaim] denied userNo={} role={} reason=role_not_self_claimable",
                    sanitize(actor.sub()), req.role());
            throw new CustomException(ErrorCode.FORBIDDEN,
                    "해당 역할은 자가 부여할 수 없습니다. 검수자에게 권한 부여를 요청하세요.");
        }
        // ② CWE-863 — fail-closed 화이트리스트: INTERNAL 채널 + 역할 미보유(role==null) actor 만 허용.
        // PORTAL_USER(channel=PORTAL) 의 교차채널 자가부여(INTERNAL WORKER/REVIEWER 상승)와 이미
        // 권한 보유자(WORKER/REVIEWER)를 모두 거절한다. (deny-by-default)
        if (actor.channel() != Channel.INTERNAL || actor.role() != null) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 권한이 부여된 사용자입니다.");
        }

        // ③ CWE-307 — rate limit. 패스워드 검증(BCrypt)/DB 작업 전에 차단한다.
        //    계정 축 + 전역 축 + 노드 공유 축을 모두 강제한다(A-ISSUE-18).
        rateLimiter.consumeOrReject(actor.sub());

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

        // CWE-863 — JWT role 이 비어있어도(stale token) 저작도구 소유 역할이 이미 있으면 409.
        // ★자동등록보다 <먼저> 판정한다 — 어차피 거절될 요청이 사용자 행을 만들지 않게 한다.
        if (lsUserRoleRepository.findByUserNo(userNo).isPresent()) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 권한이 부여된 사용자입니다.");
        }

        // ④ 사용자 자동등록 (V169) — 없으면 만들고, 있으면 표시 정보를 갱신한다.
        //    구 구현은 여기서 404 를 던졌다: 관제가 사용자 마스터를 채워 준다는 전제였는데
        //    실제로는 아무도 채우지 않아 <DBA 가 손으로 넣기 전까지 아무도 역할을 받을 수 없었다>.
        //    이제 관제가 localStorage 로 인계한 표시 정보(userId·userNm)로 우리가 등록한다.
        //
        //    ★userNo 는 위에서 파싱한 <JWT subject 값>이며 요청 바디에는 이 필드가 없다.
        //      (요청 바디로 받으면 남의 행 표시명을 바꿀 수 있다 — CWE-639. 회귀 가드:
        //       RoleClaimServiceTest.userNo_는_JWT_sub_에서만_취한다)
        //
        //    ★왜 REQUIRES_NEW 경계를 두지 않는가 (Critical — 되돌리기 전에 읽을 것)
        //      ① 이 등록은 <역할 부여의 전제 조건>이다. 사용자 행이 없으면 표시명도 후속 화면도
        //         성립하지 않으므로, 등록이 실패하면 <클레임도 실패하는 것이 맞다>. 이벤트유형
        //         자동등록(EventTypeRegistrationTx)이 REQUIRES_NEW + 예외 흡수인 것은 그쪽이
        //         <실패해도 본류(영상 적재)를 막으면 안 되는 부가 기능>이기 때문이며, 성격이 다르다.
        //      ② 동시성 사고(2노드 동시 클레임)는 REQUIRES_NEW 가 아니라 <원자 upsert>가 막는다.
        //         조회 후 INSERT 였다면 PK 위반 → PostgreSQL 이 트랜잭션 전체를 abort → 클레임 실패.
        //      ⚠ 정확히 말하면 ON CONFLICT 가 없애는 것은 <PK 위반으로 인한 abort> 하나뿐이다.
        //        lock timeout·deadlock·statement timeout·커넥션 순단은 여전히 이 트랜잭션을 abort
        //        시킬 수 있다. 그것들은 ①에 따라 <클레임 실패로 드러나는 것이 옳은> 사건이다.
        userRepository.upsertUser(userNo, normalize(req.userId(), MAX_USER_ID_LENGTH),
                normalize(req.userNm(), MAX_USER_NM_LENGTH));
        LsAcntUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_ERROR,
                        "사용자 등록에 실패했습니다."));

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

    /** CWE-117 Log Injection 방어 — CR/LF 등 제어문자 제거. */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        // 32자 이상은 잘라내고 (예측 가능 길이), 제어문자(개행/탭/캐리지리턴) 제거.
        String trimmed = value.length() > 32 ? value.substring(0, 32) : value;
        return trimmed.replaceAll("[\\r\\n\\t]", "_");
    }

    /**
     * 자가부여 가능 역할 화이트리스트 (deny-by-default).
     *
     * <p>{@link Role#WORKER}·{@link Role#REVIEWER} 를 허용한다(2026-08-04 사용자 확정 — 클래스
     * Javadoc §REVIEWER 자가부여 개방 참조). {@link Role#PORTAL_USER} 는 별도 채널이라 목록에 없고
     * {@code claim} 진입부에서도 400 으로 거절된다.
     *
     * <p>화이트리스트 자체는 유지된다 — {@link Role} enum 이 확장될 때 새 역할이 <b>자동으로</b>
     * 자가부여 대상이 되면 안 되기 때문이다(deny-by-default).
     */
    public static List<Role> allowedClaimRoles() {
        return List.of(Role.WORKER, Role.REVIEWER);
    }

    /**
     * 관제 인계 표시 정보(userId·userNm) 정규화 — 저장 직전 마지막 방어선.
     *
     * <ul>
     *   <li><b>공백 전용 → null</b>: "값을 보내지 않음"과 같게 취급한다. 빈 문자열이 기존 이름을
     *       지우면 안 된다(upsert 의 COALESCE/NULLIF 와 짝).</li>
     *   <li><b>제어문자 제거</b>(CWE-117): 이 값은 화면 표시 + JWT {@code name} 클레임 + 다른 코드의
     *       로그로 흘러간다. 저장 시점에 개행·구분자를 걷어내 로그 라인 위조 소지를 없앤다.</li>
     *   <li><b>길이 상한</b>: DTO {@code @Size} 가 먼저 거절하지만, 다른 호출자가 생겨도 컬럼 길이를
     *       넘지 않도록 여기서도 자른다(방어 심층화).</li>
     * </ul>
     */
    static String normalize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
