package kr.co.cudo.authoring.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import kr.co.cudo.authoring.auth.dto.RoleClaimRequest;
import kr.co.cudo.authoring.auth.dto.RoleClaimResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RoleClaimService 단위 테스트.
 *
 * <p>보안 — Fortify "Hardcoded Password" 회피:
 * 테스트 admin password 는 매 테스트 인스턴스마다 {@link SecureRandom} 으로 동적 생성하고
 * 해당 평문의 BCrypt 해시를 서비스에 주입한다. 코드/리소스에는 어떠한 평문도 박혀있지 않다.
 */
class RoleClaimServiceTest {

    private RoleClaimService service;
    private SecretKey key;
    private UserRepository userRepository;
    private LsUserRoleRepository lsUserRoleRepository;
    private kr.co.cudo.authoring.common.security.UserRoleResolver userRoleResolver;
    private RoleClaimRateLimiter rateLimiter;
    private String adminPlaintext;

    @BeforeEach
    void setUp() {
        // 256bit JWT 키 — 매 테스트 새로 발급.
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        JwtKeyResolver resolver = () -> key;

        userRepository = mock(UserRepository.class);
        lsUserRoleRepository = mock(LsUserRoleRepository.class);
        userRoleResolver = mock(kr.co.cudo.authoring.common.security.UserRoleResolver.class);

        // 테스트 사용자(userNo=1001)는 무권한 상태로 존재.
        //   V169 — 서비스는 upsertUser(자동등록) 후 findByUserNo 로 <되읽어> 표시명을 얻는다.
        //   따라서 여기 스텁은 "upsert 직후의 행"을 흉내낸다.
        LsAcntUser user = mock(LsAcntUser.class);
        when(user.getUserNo()).thenReturn(1001L);
        when(user.getUserNm()).thenReturn("테스트사용자");
        when(userRepository.findByUserNo(1001L)).thenReturn(Optional.of(user));

        // 기본 — LS 역할 미보유(미배정) 상태. upsert 는 1행 영향.
        when(lsUserRoleRepository.findByUserNo(anyLong())).thenReturn(Optional.empty());
        when(lsUserRoleRepository.upsertRole(anyLong(), anyString())).thenReturn(1);

        // 테스트 admin 평문 — SecureRandom 으로 동적 생성. 해시만 서비스에 주입.
        byte[] pwBytes = new byte[24];
        new SecureRandom().nextBytes(pwBytes);
        adminPlaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(pwBytes);
        String bcryptHash = new BCryptPasswordEncoder(12).encode(adminPlaintext);

        // rate limiter — 공유 저장소 없이(로컬 카운터만) 계정 5회/분, 전역 50회/분.
        rateLimiter = new RoleClaimRateLimiter(null, 5, 50);
        service = new RoleClaimService(userRepository, lsUserRoleRepository, userRoleResolver, resolver,
                rateLimiter, bcryptHash, "klid-auth");
    }

    private TokenClaims actor(String sub, Role role) {
        return new TokenClaims(sub, role, Channel.INTERNAL, Instant.now().plusSeconds(3600));
    }

    private TokenClaims portalActor(String sub, Role role) {
        return new TokenClaims(sub, role, Channel.PORTAL, Instant.now().plusSeconds(3600));
    }

    private String wrongPassword() {
        // 평문과 다른 임의 문자열 (동적 생성).
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return "wrong-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    @DisplayName("role_claim_자가부여시_LS에_역할이_기록된다_WORKER_+_새토큰_반환")
    void claimWorkerSuccess() {
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        RoleClaimResponse res = service.claim(req, actor("1001", null));

        assertThat(res.accessToken()).isNotBlank();
        assertThat(res.role()).isEqualTo("WORKER");
        assertThat(res.userNo()).isEqualTo(1001L);
        assertThat(res.userName()).isEqualTo("테스트사용자");

        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(res.accessToken()).getPayload();
        assertThat(claims.getSubject()).isEqualTo("1001");
        assertThat(claims.get("role", String.class)).isEqualTo("WORKER");
        assertThat(claims.get("channel", String.class)).isEqualTo("INTERNAL");
        assertThat(claims.getIssuer()).isEqualTo("klid-auth");

        // LS_USER_ROLE 원자 upsert 로만 기록 (구 관제 권한 매핑 테이블 쓰기 0건 — V165 로 삭제됨).
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(1001L), eq("WORKER"));
    }

    @Test
    @DisplayName("성공시_evict_호출_verify")
    void claimSuccessEvictsCache() {
        // 자가부여 성공 시 인가 역할 캐시 무효화 (no-tx: 즉시 evict 분기)
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        service.claim(req, actor("1001", null));

        verify(userRoleResolver).evict(1001L);
    }

    @Test
    @DisplayName("REVIEWER_자가부여가_허용된다")
    void REVIEWER_자가부여가_허용된다() {
        // ★2026-08-04 사용자 확정 — REVIEWER 자가부여 개방. 되돌리지 말 것.
        //   구 정책(A-ISSUE-17, WORKER 단일 화이트리스트)은 "이미 REVIEWER 인 사람이 부여한다"를
        //   전제했는데, 온프렘 신규 설치에는 그 사람이 없어 운영 문서가 dev 편의 경로를 부트스트랩으로
        //   안내하고 있었다(그쪽이 더 위험). 잔여 위험(패스워드 유출 = 전권)은 사용자가 수용했다.
        // given: 올바른 관리자 패스워드 + 무권한 INTERNAL actor
        RoleClaimRequest req = new RoleClaimRequest(Role.REVIEWER, adminPlaintext);

        // when
        RoleClaimResponse res = service.claim(req, actor("1001", null));

        // then: REVIEWER 로 부여되고 새 토큰의 role 클레임도 REVIEWER 다
        assertThat(res.role()).isEqualTo("REVIEWER");
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(1001L), eq("REVIEWER"));

        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(res.accessToken()).getPayload();
        assertThat(claims.get("role", String.class)).isEqualTo("REVIEWER");

        // then: 화이트리스트는 <유지>된다 — Role enum 이 확장돼도 새 역할이 자동 허용되면 안 된다.
        assertThat(RoleClaimService.allowedClaimRoles())
                .containsExactlyInAnyOrder(Role.WORKER, Role.REVIEWER)
                .doesNotContain(Role.PORTAL_USER);
    }

    @Test
    @DisplayName("PORTAL_USER_는_여전히_거절된다")
    void PORTAL_USER_는_여전히_거절된다() {
        // REVIEWER 개방과 무관하게 포털 역할은 별도 채널이라 본 API 로 부여되지 않는다.
        RoleClaimRequest req = new RoleClaimRequest(Role.PORTAL_USER, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        verify(lsUserRoleRepository, times(0)).upsertRole(anyLong(), anyString());
        verify(userRepository, times(0)).upsertUser(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("허용되지_않은_역할_요청은_rate_limit_소모_전에_거부됨")
    void disallowedRoleRejectedBeforeRateLimit() {
        // A-ISSUE-18 — 검증 순서: ① 부여 불가 역할 → ② actor 채널/기보유역할 → ③ rate limit.
        //   역할 검증이 rate limit 뒤에 있으면 잘못된 role 시도가 정상 사용자의 쿼터를 소모시킨다.
        //   ※ REVIEWER 개방 이후 FORBIDDEN 분기(화이트리스트 밖 역할)는 <Role enum 이 확장될 때만>
        //     도달 가능하므로, 순서 보장은 그보다 앞에서 거절되는 PORTAL_USER 로 고정한다.
        RoleClaimRequest bad = new RoleClaimRequest(Role.PORTAL_USER, adminPlaintext);

        // given: 계정 한도(5회)를 넘는 시도 — 전부 400 이어야 하고 429 로 바뀌면 안 된다.
        for (int i = 0; i < 7; i++) {
            assertThatThrownBy(() -> service.claim(bad, actor("1001", null)))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_INPUT));
        }

        // then: 쿼터가 소모되지 않았으므로 정상 WORKER 자가부여가 그대로 성공한다.
        RoleClaimResponse res = service.claim(new RoleClaimRequest(Role.WORKER, adminPlaintext), actor("1001", null));
        assertThat(res.role()).isEqualTo("WORKER");
    }

    @Test
    @DisplayName("role_claim_이미_LS역할보유시_409_CONFLICT_JWT_role_없어도")
    void alreadyHasLsRoleReturns409() {
        // JWT role 은 비어있지만(stale token) LS_USER_ROLE 에 이미 역할 존재.
        when(lsUserRoleRepository.findByUserNo(1001L))
                .thenReturn(Optional.of(LsUserRole.of(1001L, "WORKER")));
        // 요청 역할은 화이트리스트(WORKER) — 여기서 검증하려는 것은 "이미 LS 역할 보유" 분기다.
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(lsUserRoleRepository, times(0)).upsertRole(anyLong(), anyString());
    }

    @Test
    @DisplayName("잘못된_admin_password_401_UNAUTHORIZED")
    void wrongPasswordReturns401() {
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, wrongPassword());

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(lsUserRoleRepository, times(0)).upsertRole(anyLong(), anyString());
    }

    @Test
    @DisplayName("이미_WORKER_권한_부여된_사용자가_재호출_409_CONFLICT")
    void alreadyWorkerReturns409() {
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, actor("1001", Role.WORKER)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("이미_REVIEWER_권한_부여된_사용자가_재호출_409_CONFLICT")
    void alreadyReviewerReturns409() {
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, actor("1001", Role.REVIEWER)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("포털채널_actor는_role없어도_409_CONFLICT_교차채널_상승차단")
    void portalChannelActorReturns409() {
        // H-1 — PORTAL_USER(channel=PORTAL) actor 가 role==null 이어도 INTERNAL WORKER/REVIEWER
        // 자가부여를 시도하면 fail-closed 화이트리스트로 거절한다 (교차채널 권한상승 차단).
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, portalActor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(lsUserRoleRepository, times(0)).upsertRole(anyLong(), anyString());
    }

    @Test
    @DisplayName("actor_null_시_401")
    void nullActorReturns401() {
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("시도_5회_초과시_429_TOO_MANY_REQUESTS")
    void rateLimitAfterFiveAttempts() {
        String bad = wrongPassword();
        RoleClaimRequest badReq = new RoleClaimRequest(Role.WORKER, bad);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.claim(badReq, actor("1001", null)))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }
        // 6회째 — rate limit 발동.
        assertThatThrownBy(() -> service.claim(badReq, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("다른_사용자는_시도_횟수_제한에_영향_받지_않음")
    void rateLimitPerCaller() {
        RoleClaimRequest bad = new RoleClaimRequest(Role.WORKER, wrongPassword());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.claim(bad, actor("1001", null)))
                    .isInstanceOf(CustomException.class);
        }
        // userNo=2001 은 영향 없음 — 잘못된 패스워드이지만 rate limit 은 아니다.
        LsAcntUser user2 = mock(LsAcntUser.class);
        when(user2.getUserNm()).thenReturn("다른사용자");
        when(userRepository.findByUserNo(2001L)).thenReturn(Optional.of(user2));

        assertThatThrownBy(() -> service.claim(bad, actor("2001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("새_토큰의_TTL_은_기본_3600초")
    void issuedTokenHasOneHourTtl() {
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        RoleClaimResponse res = service.claim(req, actor("1001", null));

        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(res.accessToken()).getPayload();
        long ttl = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(ttl).isEqualTo(3600);
    }

    @Test
    @DisplayName("BCrypt_해시가_빈문자열이면_항상_401")
    void emptyAdminHashAlwaysReturns401() {
        JwtKeyResolver resolver = () -> key;
        RoleClaimService empty = new RoleClaimService(userRepository, lsUserRoleRepository, userRoleResolver, resolver,
                new RoleClaimRateLimiter(null, 5, 50), "", "klid-auth");
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);

        assertThatThrownBy(() -> empty.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("admin_hash_가_BCrypt_형식이_아니면_부트_거절")
    void rejectNonBcryptHashAtBoot() {
        JwtKeyResolver resolver = () -> key;
        assertThatThrownBy(() ->
                new RoleClaimService(userRepository, lsUserRoleRepository, userRoleResolver, resolver,
                        new RoleClaimRateLimiter(null, 5, 50), "plaintext-not-bcrypt", "klid-auth")
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("userNo_가_숫자가_아니면_400_INVALID_INPUT")
    void nonNumericSubjectReturns400() {
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);
        TokenClaims badSub = new TokenClaims("not-a-number", null, Channel.INTERNAL, Instant.now().plusSeconds(3600));

        assertThatThrownBy(() -> service.claim(req, badSub))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("역할클레임시_사용자가_없으면_자동등록된다")
    void 역할클레임시_사용자가_없으면_자동등록된다() {
        // V169 — 구 구현은 여기서 404 를 던졌다. 관제가 사용자 마스터를 채워 준다는 전제였는데
        //   실제로는 아무도 채우지 않아 <DBA 가 손으로 넣기 전까지 아무도 역할을 받을 수 없었다>.
        // given: 마스터에 없는 사용자 + 관제가 localStorage 로 인계한 표시 정보
        LsAcntUser registered = mock(LsAcntUser.class);
        when(registered.getUserNm()).thenReturn("신재석");
        when(userRepository.findByUserNo(9999L)).thenReturn(Optional.of(registered));
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext, "sjs123", "신재석");

        // when
        RoleClaimResponse res = service.claim(req, actor("9999", null));

        // then: 404 가 아니라 자동등록 후 부여된다
        assertThat(res.userNo()).isEqualTo(9999L);
        assertThat(res.userName()).isEqualTo("신재석");
        verify(userRepository, times(1)).upsertUser(eq(9999L), eq("sjs123"), eq("신재석"));
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(9999L), eq("WORKER"));
    }

    @Test
    @DisplayName("역할클레임시_기존_사용자는_이름이_갱신된다")
    void 역할클레임시_기존_사용자는_이름이_갱신된다() {
        // given: 이미 존재하는 사용자(1001) + 관제가 바뀐 이름을 인계
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext, "sjs123", "변경된이름");

        // when
        service.claim(req, actor("1001", null));

        // then: 같은 원자 upsert 로 표시 정보가 갱신된다(별도 분기 없음 — 등록/갱신이 한 문장)
        verify(userRepository, times(1)).upsertUser(eq(1001L), eq("sjs123"), eq("변경된이름"));
    }

    @Test
    @DisplayName("표시정보_미전달시_기존값을_지우지_않는다")
    void 표시정보_미전달시_기존값을_지우지_않는다() {
        // 하위호환 — 구 FE 는 표시 정보를 보내지 않는다. 공백 전용 문자열도 "미전달"과 같게 본다
        //   (null 로 정규화 → upsert 의 COALESCE 가 기존 값을 유지).
        RoleClaimRequest blank = new RoleClaimRequest(Role.WORKER, adminPlaintext, "   ", "\t\n ");

        service.claim(blank, actor("1001", null));

        verify(userRepository, times(1)).upsertUser(eq(1001L), isNull(), isNull());
    }

    @Test
    @DisplayName("표시정보의_제어문자는_제거되고_컬럼길이로_잘린다")
    void 표시정보의_제어문자는_제거되고_컬럼길이로_잘린다() {
        // CWE-117 — 이 값은 화면·JWT name 클레임·다른 코드의 로그로 흘러간다.
        //   길이 상한은 DTO @Size 가 먼저 막지만 다른 호출자를 대비해 서비스에서도 자른다.
        String forged = "홍길\r\n[RoleClaim] granted userNo=1 role=REVIEWER";
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext, null, forged);

        service.claim(req, actor("1001", null));

        verify(userRepository, times(1)).upsertUser(eq(1001L), isNull(),
                eq("홍길[RoleClaim] granted userNo=1 role=REVIEWER"));
    }

    @Test
    @DisplayName("userNo_는_JWT_sub_에서만_취한다")
    void userNo_는_JWT_sub_에서만_취한다() {
        // ★CWE-639 IDOR — 요청 바디에는 userNo 필드가 <아예 없어야> 하고, 자동등록·역할부여는
        //   전부 JWT subject 파싱값으로만 이루어져야 한다. 바디 값으로 남의 행을 만들거나
        //   표시명을 바꿀 수 있으면 안 된다.
        // given: 바디에 사용자 식별 필드가 존재하지 않음(Mass Assignment 차단)
        assertThat(java.util.Arrays.stream(RoleClaimRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .as("RoleClaimRequest 에 사용자 식별/권한 승격에 쓰일 필드가 있으면 안 된다")
                .containsExactlyInAnyOrder("role", "adminPassword", "userId", "userNm");

        // when: 표시 정보를 <다른 사용자처럼> 위조해 보낸다
        RoleClaimRequest forged = new RoleClaimRequest(Role.WORKER, adminPlaintext, "victim", "피해자");
        service.claim(forged, actor("1001", null));

        // then: 쓰기는 전부 sub(1001) 대상이다 — 위조값은 자기 행의 표시 이름에만 반영된다
        verify(userRepository, times(1)).upsertUser(eq(1001L), eq("victim"), eq("피해자"));
        verify(userRepository, times(0)).upsertUser(eq(2001L), anyString(), anyString());
        verify(lsUserRoleRepository, times(1)).upsertRole(eq(1001L), eq("WORKER"));
        verify(lsUserRoleRepository, times(0)).upsertRole(eq(2001L), anyString());
    }

    @Test
    @DisplayName("이미_역할이_있으면_자동등록도_하지_않는다")
    void 이미_역할이_있으면_자동등록도_하지_않는다() {
        // 어차피 409 로 거절될 요청이 사용자 행을 만들면 안 된다(순서: 409 판정 → 자동등록).
        when(lsUserRoleRepository.findByUserNo(1001L))
                .thenReturn(Optional.of(LsUserRole.of(1001L, "WORKER")));
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext, "sjs123", "신재석");

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(userRepository, times(0)).upsertUser(anyLong(), anyString(), anyString());
    }
}
