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
import kr.co.cudo.authoring.user.service.UserDisplayNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 관리자 부트스트랩 서비스 (역할 자가 부여).
 *
 * <p>인증은 되었으나 role 클레임이 없는 사용자가 관리자 공유 패스워드와 함께 본인에게
 * {@link Role#ADMIN} 역할을 부여한다. 사용자 마스터 행이 없으면 이 시점에 <b>자동등록</b>된다
 * (V169 — 아래 {@code claim} 참조).
 *
 * <h3>★ 이 창구는 <b>관리자 부트스트랩 전용</b>이다 (ADR-055)</h3>
 * <p>전제는 <b>둘</b>이며 둘이 모두 참일 때만 승인한다 — ① 내부 채널일 것 ② <b>시스템에
 * {@link Role#ADMIN} 이 한 명도 없을 것</b>. 관리자가 한 명이라도 생기면 이 창구는 누구에게도
 * 열리지 않는다(관리자 패스워드를 알아도 마찬가지). 그 뒤의 역할 부여는 관리자가 사용자 관리
 * 화면에서 하고, 일반 진입자는 작업자로 <b>자동 등록</b>된다.
 *
 * <h3>★★ 창이 열려 있는 동안에는 <b>역할 보유를 따지지 않는다</b> (되돌리면 교착이다)</h3>
 * <p>검수자·작업자도 이 창구로 관리자가 되며, 기존 역할은 관리자로 <b>교체</b>된다. 역할 보유를
 * 거절 사유로 삼는 것은 <b>창이 닫힌 뒤</b>에만 성립한다 — 창이 열려 있다는 것 자체가 아직 아무도
 * 관리자가 아니라는 뜻이라, 그 구간에서 역할 보유자를 막으면 최초 관리자를 만들 수 있는 사람이
 * 아무도 남지 않는다.
 *
 * <p><b>실제로 그 교착이 났다</b>(그래서 이 문단이 있다). 두 축이 각각은 규정대로였다:
 * <ul>
 *   <li><b>신규 설치</b> — 진입 시 자동 등록이 <b>같은 요청의 앞단</b>(인증 필터)에서 작업자 역할을
 *       부여하고, 그 클레임이 그대로 principal 이 되어 "이미 역할 보유" 로 거절됐다. FE 가 첫 화면에서
 *       {@code /v1/me} 만 불러도 같은 결과다.</li>
 *   <li><b>이미 운영 중인 시스템</b> — 자동 등록과 무관하게, 그곳에는 역할 없는 사용자가 <b>애초에
 *       없다</b>(전원 검수자 아니면 작업자). 업그레이드 배포에서는 어떤 수를 써도 최초 관리자가
 *       생기지 않았다.</li>
 * </ul>
 * <p>⇒ 아래 판정 순서에서 <b>관리자 0명 게이트가 채널 검사 바로 뒤</b>에 오는 것이 핵심이다.
 * 그 게이트가 "창이 닫혔으면 전원 거절" 을 이미 담당하므로 역할 보유 거절은 논리적으로 포섭된다.
 *
 * <p><b>부여 역할은 {@link Role#ADMIN} 고정</b>이다. 요청 바디의 {@code role} 은 더 이상 선택지가
 * 아니며 다른 값을 실어도 결과를 바꾸지 못한다({@code PORTAL_USER} 만 진입부에서 400 으로
 * 거절된다 — 별도 채널이라 이 창구의 대상이 아님을 요청자에게 알린다).
 *
 * <p><b>화이트리스트({@link #allowedClaimRoles()})는 값만 좁혔고 구조는 그대로다</b> —
 * {@link Role} enum 이 확장될 때 새 역할이 <b>자동으로</b> 자가부여 대상이 되면 안 되기 때문이다
 * (deny-by-default). 검사 대상이 요청값이 아니라 <b>부여할 역할</b>이라 현재 역할 집합에서는 이
 * 분기가 통과만 하지만, 그것이 이 장치의 목적이다.
 *
 * <p><b>구 정책(폐기) — 경위 보존</b>: ①원래는 WORKER 단일 화이트리스트였다(A-ISSUE-17,
 * CWE-269/CWE-1392). 그 전제(=이미 REVIEWER 인 사람이 존재한다)가 온프렘 신규 설치에서 성립하지
 * 않아 dev 편의 경로가 부트스트랩이 되어 있었고, 그래서 2026-08-04 에 REVIEWER 를 열었다.
 * ②그 개방은 "관리자 공유 패스워드를 아는 사람은 누구나 REVIEWER" 를 뜻해, 역할 획득과 관리자
 * 유효창이 <b>같은 비밀 하나</b>에 매달렸다. ADR-055 는 그 둘을 갈랐다 — 창구를 최초 1회로 닫아
 * 패스워드로는 더 이상 역할을 얻지 못하게 하고, 대신 관리자 역할을 신설했다. ⚠ "부트스트랩이
 * 막힌다" 는 이유로 조건 없는 개방으로 되돌리지 말 것 — 조건이 곧 이 결정이다.
 *
 * <p><b>그대로 유지되는 방어</b>(하나라도 빼면 부트스트랩 창구가 위험해진다):
 * rate limit(CWE-307) · BCrypt 상수시간 비교(CWE-203) · 평문 패스워드 로그 금지(CWE-532) ·
 * INTERNAL 채널 + {@code role==null} 게이트(CWE-863) · {@code PORTAL_USER} 거절.
 *
 * <p>보안 (security-rules.md 준수):
 * <ul>
 *   <li><b>CWE-256 Plaintext Password Storage</b> — application.yml 에 BCrypt 해시만 저장 (cost ≥ 12).
 *       평문은 어디에도 저장되지 않는다. 해시 보관·검증은 {@link AdminPasswordVerifier} 가 소유한다.</li>
 *   <li><b>CWE-307 Improper Restriction of Excessive Authentication Attempts</b> —
 *       {@link RoleClaimRateLimiter} 가 계정 축 + 엔드포인트 전역 축을, 노드 공유 저장소와 함께 강제한다.
 *       초과 시 {@link ErrorCode#TOO_MANY_REQUESTS}.</li>
 *   <li><b>CWE-863 Incorrect Authorization</b> — actor 가 이미 역할을 보유하면 409 CONFLICT.
 *       PORTAL_USER 는 별도 채널이므로 본 API 진입 자체를 거절.</li>
 *   <li><b>CWE-117 Log Injection / CWE-532</b> — adminPassword 평문은 로그에 절대 출력하지 않으며,
 *       성공/실패 결과만 (userNo, role) 형식으로 로그한다.</li>
 *   <li><b>CWE-203 Observable Timing Discrepancy</b> — {@link AdminPasswordVerifier} 의 BCrypt 비교가
 *       상수시간을 보장하므로 별도 조치 불필요.</li>
 * </ul>
 *
 * @design ADR-055
 * @design API-007
 */
@Slf4j
@Service
public class RoleClaimService {

    /** 새 토큰의 TTL — 기존 DevTokenService 의 기본값과 동일하게 1시간. */
    private static final long ISSUED_TOKEN_TTL_SECONDS = 3600L;

    /**
     * 이 창구가 부여하는 역할 — <b>고정</b>이다. 요청 바디의 {@code role} 은 무시된다(ADR-055).
     * 이 값을 넓히려면 {@link #allowedClaimRoles()} 와 함께 바꿔야 한다.
     */
    private static final Role BOOTSTRAP_ROLE = Role.ADMIN;

    /**
     * 표시 정보 컬럼 길이 — 진입 시 자동 등록과 <b>같은 상수</b>를 쓴다. 상한이 갈리면 한쪽 경로에서만
     * 컬럼 폭을 넘겨 INSERT 시점 DB 오류가 난다.
     */
    static final int MAX_USER_ID_LENGTH = UserDisplayNames.MAX_USER_ID_LENGTH;
    static final int MAX_USER_NM_LENGTH = UserDisplayNames.MAX_USER_NM_LENGTH;

    private final UserRepository userRepository;
    private final LsUserRoleRepository lsUserRoleRepository;
    private final UserRoleResolver userRoleResolver;
    private final JwtKeyResolver keyResolver;
    private final RoleClaimRateLimiter rateLimiter;
    /**
     * 관리자 공유 패스워드 판정 — 해시 보관·상수시간 비교는 {@link AdminPasswordVerifier} 한 곳이 소유한다.
     *
     * <p>과거에는 이 서비스가 해시를 직접 들고 있었다. 같은 패스워드를 확인하는 두 번째 경로
     * (관리자 단기 유효창)가 생기면서 판정이 둘로 갈릴 수 있게 되어, 보관·비교를 공용 판정기로 옮겼다.
     * 정책(해시만 저장 · 상수시간 비교 · 미설정이면 항상 거절)은 그대로다.
     */
    private final AdminPasswordVerifier adminPasswordVerifier;
    private final String issuer;

    public RoleClaimService(
            UserRepository userRepository,
            LsUserRoleRepository lsUserRoleRepository,
            UserRoleResolver userRoleResolver,
            JwtKeyResolver keyResolver,
            RoleClaimRateLimiter rateLimiter,
            AdminPasswordVerifier adminPasswordVerifier,
            @Value("${authoring.jwt.issuer:klid-auth}") String issuer
    ) {
        this.userRepository = userRepository;
        this.lsUserRoleRepository = lsUserRoleRepository;
        this.userRoleResolver = userRoleResolver;
        this.keyResolver = keyResolver;
        this.rateLimiter = rateLimiter;
        this.adminPasswordVerifier = adminPasswordVerifier;
        this.issuer = (issuer == null || issuer.isBlank()) ? "klid-auth" : issuer;
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
        // ① CWE-269 — 자가부여 가능 역할 화이트리스트를 **가장 먼저** 강제한다(deny-by-default).
        //    ★검사 대상은 요청값이 아니라 <부여할 역할>이다 — 부여 역할이 ADMIN 으로 고정된 뒤에도
        //      Role enum 이 확장될 때 새 역할이 자동으로 자가부여 대상이 되지 않게 하는 장치다.
        //      요청값을 검사하면 REVIEWER/WORKER 를 실은 구 클라이언트가 403 을 받는데, 설계상
        //      그 요청은 <결과를 바꾸지 못할 뿐> 거절 대상이 아니다(API-007).
        //    rate limit 보다 앞에 두어야 잘못된 시도가 정상 사용자의 쿼터를 소모하지 않는다.
        if (!allowedClaimRoles().contains(BOOTSTRAP_ROLE)) {
            log.warn("[RoleClaim] denied userNo={} reason=role_not_self_claimable",
                    sanitize(actor.sub()));
            throw new CustomException(ErrorCode.FORBIDDEN,
                    "해당 역할은 자가 부여할 수 없습니다. 관리자에게 권한 부여를 요청하세요.");
        }
        // ② CWE-863 — 채널 격리. PORTAL_USER(channel=PORTAL) 의 교차채널 자가부여를 거절한다.
        //    ★역할 보유 검사는 여기 두지 않는다 — 아래 ④(관리자 0명)가 "창이 닫혔으면 전원 거절" 을
        //      담당하고, 창이 열려 있는 동안에는 역할 보유자도 관리자가 돼야 한다(클래스 javadoc
        //      §창이 열려 있는 동안에는 역할 보유를 따지지 않는다). 여기로 되돌리면 부트스트랩이
        //      도달 불가능해진다 — 자동 등록이 같은 요청의 앞단에서 역할을 부여하기 때문이다.
        if (actor.channel() != Channel.INTERNAL) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이 창구는 내부 채널에서만 사용할 수 있습니다.");
        }

        // ③ CWE-307 — rate limit. 패스워드 검증(BCrypt)/DB 작업 전에 차단한다.
        //    계정 축 + 전역 축 + 노드 공유 축을 모두 강제한다(A-ISSUE-18).
        rateLimiter.consumeOrReject(actor.sub());

        // ④ ADR-055 — <관리자가 0명일 때만> 열리는 부트스트랩 창구다. 한 명이라도 있으면 닫힌다.
        //    ★이 게이트가 <역할 보유자 거절>까지 포섭한다 — 창이 닫힌 뒤에는 역할 보유 여부와
        //      무관하게 전원 거절되고, 그때는 관리자가 역할을 부여하는 정규 경로가 존재한다.
        //      그래서 ②에는 채널 검사만 남았다 — 둘은 한 쌍이라 ②에 역할 검사를 되살리면
        //      부트스트랩이 도달 불가능해진다(클래스 javadoc §창이 열려 있는 동안…).
        //
        //    ★★rate limit <뒤>에 둔다 (구 위치는 앞이었다 — 되돌리지 말 것).
        //      앞에 두면 인증된 내부 사용자가 <아무 패스워드로 1회> 호출해 응답만 보고 창의
        //      개폐를 읽는다(닫힘=409 / 열림=401·429). 그리고 "열림" 은 곧
        //      <지금 패스워드를 맞히면 관리자가 된다>는 신호라, 쿼터를 한 톨도 쓰지 않고 얻는
        //      정찰 창구가 된다. 뒤로 내리면 그 관측에도 시도 횟수가 든다.
        //      ⚠ 구 근거 *"닫힌 창구에 대한 시도가 정상 사용자의 쿼터를 소모하면 안 된다"* 는
        //        폐기됐다 — 창이 닫혔으면 이 창구를 쓸 <정상 사용자가 애초에 없다>. 아낄 쿼터의
        //        주인이 존재하지 않으므로 그 근거는 성립하지 않는다.
        //      ⚠ 패스워드 검증보다는 여전히 <앞>이다. 뒤로 더 내리면 닫힌 창에서도 패스워드
        //        정오답이 401/409 로 갈려 패스워드 오라클이 된다(회귀 가드가 이 순서를 고정한다).
        //
        //    ★409 의 뜻 — 이제 이 코드는 <창이 닫혔다> 하나만 뜻한다(그리고 ②의 포털 채널 거절).
        //      ⚠ 구 서술 폐기: *"'이미 권한이 부여된 사용자'와 같은 409 라 두 사유를 코드로 구분하지
        //        않는 것이 의도"*. 그 조건은 제거됐으므로(API-007 v14) 근거가 무효다. 되살리면
        //        자동 등록이 같은 요청 앞단에서 역할을 부여하므로 <창구가 도달 불가능해진다>.
        //    ⚠ 이 확인과 아래 부여 사이에는 창이 있다. 그 창은 조건부 INSERT
        //      (upsertRoleIfNoneHasRole)가 문장 안에서 다시 닫는다 — 여기 하나만으로는 부족하다.
        if (lsUserRoleRepository.countByRoleCd(BOOTSTRAP_ROLE.name()) > 0) {
            log.warn("[RoleClaim] denied userNo={} reason=bootstrap_closed", sanitize(actor.sub()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 관리자가 있어 자가부여가 닫혀 있습니다. 관리자에게 역할 부여를 요청하세요.");
        }

        // CWE-203 — 상수시간 비교. 미설정(해시 비어 있음)이면 항상 false 다(fail-closed).
        if (!adminPasswordVerifier.matches(req.adminPassword())) {
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

        // ★저작도구 소유 역할 보유 여부는 여기서도 따지지 않는다 (구 구현은 여기서 409 를 던졌다).
        //   위 ② 와 같은 축이다 — 자동 등록으로 행이 생긴 사용자, 그리고 이미 운영 중인 시스템의
        //   모든 사용자가 이 분기에 걸려 최초 관리자를 만들 경로가 0개가 됐다. 창이 닫혔는지는
        //   ④ 가 판정하고, 부여 문장(아래)이 같은 조건을 한 번 더 확인한다.

        // ⑤ 사용자 자동등록 (V169) — 없으면 만들고, 있으면 표시 정보를 갱신한다.
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

        // 역할 부여 — <조건부> INSERT. 부여 역할은 ADMIN 고정이며 요청값을 쓰지 않는다(ADR-055).
        //   ★위 ④ 게이트의 확인 이후 다른 노드가 관리자를 만들었으면 여기서 0 이 돌아온다.
        //     문장 안에서 조건을 다시 판정하므로 "확인 → 부여" 사이의 창이 좁혀진다.
        //   ★요청자에게 이미 역할이 있으면 <덮어쓴다>(ON CONFLICT DO UPDATE). 덮지 않으면 자동
        //     등록으로 작업자가 된 사용자·기존 검수자에게 관리자가 붙지 않아 부트스트랩이 도달
        //     불가능해진다. 덮어쓰기 대상은 요청자 자신 하나이고(userNo 는 JWT sub), 조건이 그대로라
        //     "아직 아무도 관리자가 아닌" 구간에서만 일어난다.
        if (lsUserRoleRepository.upsertRoleIfNoneHasRole(userNo, BOOTSTRAP_ROLE.name()) != 1) {
            log.warn("[RoleClaim] denied userNo={} reason=bootstrap_closed_race", userNo);
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 관리자가 있어 자가부여가 닫혀 있습니다. 관리자에게 역할 부여를 요청하세요.");
        }

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
        String token = issueInternalToken(userNo, BOOTSTRAP_ROLE, user.getUserNm());

        // userNo 는 Long 으로 파싱 완료된 안전한 값. role 은 enum.
        log.info("[RoleClaim] granted userNo={} role={} (bootstrap)", userNo, BOOTSTRAP_ROLE.name());

        return new RoleClaimResponse(
                token,
                BOOTSTRAP_ROLE.name(),
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
     * 자가부여 가능 역할 화이트리스트 (deny-by-default) — <b>{@link Role#ADMIN} 하나뿐</b>이다.
     *
     * <p>이 창구는 관리자 부트스트랩 전용이라 다른 역할을 부여하지 않는다(ADR-055). 일반
     * 사용자는 진입 시 작업자로 자동 등록되고, 그 뒤의 역할 지정은 관리자가 한다.
     *
     * <p>값만 좁혔고 <b>구조는 그대로</b>다 — {@link Role} enum 이 확장될 때 새 역할이
     * <b>자동으로</b> 자가부여 대상이 되면 안 되기 때문이다. 목록을 없애고 상수 비교로 바꾸면 그
     * 방어가 사라진다.
     *
     * <p>⚠ {@code WORKER}·{@code REVIEWER} 를 되살리지 말 것 — 되살리면 관리자 공유 패스워드
     * 하나로 다시 검수자가 되어, 역할 획득과 관리자 유효창이 같은 비밀에 매달리던 상태로 돌아간다
     * (ADR-055 가 갈라놓은 바로 그 지점).
     *
     * @design ADR-055
     */
    public static List<Role> allowedClaimRoles() {
        return List.of(BOOTSTRAP_ROLE);
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
        return UserDisplayNames.normalize(value, maxLength);
    }
}
