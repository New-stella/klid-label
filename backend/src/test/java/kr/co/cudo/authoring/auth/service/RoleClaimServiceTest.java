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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
        // ADR-055 — 부트스트랩 창구의 두 조건.
        //   ① 관리자 0명(countByRoleCd=0) ② 조건부 INSERT 가 1행을 넣음.
        //   Mockito 기본값(0)은 ②를 "이미 관리자가 있다"로 만들어 전 테스트가 409 가 되므로 명시한다.
        when(lsUserRoleRepository.countByRoleCd(anyString())).thenReturn(0L);
        when(lsUserRoleRepository.upsertRoleIfNoneHasRole(anyLong(), anyString())).thenReturn(1);

        // 테스트 admin 평문 — SecureRandom 으로 동적 생성. 해시만 서비스에 주입.
        byte[] pwBytes = new byte[24];
        new SecureRandom().nextBytes(pwBytes);
        adminPlaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(pwBytes);
        String bcryptHash = new BCryptPasswordEncoder(12).encode(adminPlaintext);

        // rate limiter — 공유 저장소 없이(로컬 카운터만) 계정 5회/분, 전역 50회/분.
        //   ★고정 Clock 주입(분 경계 flake 수정) — consumeOrReject 의 windowStart 산출이 실제
        //   시스템 시각을 쓰면, 같은 테스트 안의 연속 호출이 분 경계를 넘는 순간 카운터 버킷이
        //   바뀌어 리셋된다(시도_5회_초과시_429_TOO_MANY_REQUESTS 가 약 3% 확률로 flake 하던 원인).
        //   Clock.fixed 는 결코 흐르지 않으므로 이 파일의 모든 반복 호출이 항상 같은 분 버킷에 든다.
        rateLimiter = new RoleClaimRateLimiter(null, 5, 50,
                Clock.fixed(Instant.parse("2026-01-01T00:00:30Z"), ZoneOffset.UTC));
        service = new RoleClaimService(userRepository, lsUserRoleRepository, userRoleResolver, resolver,
                rateLimiter, new AdminPasswordVerifier(bcryptHash), "klid-auth");
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
    @DisplayName("★부트스트랩_성공시_ADMIN_이_부여되고_새토큰의_role_도_ADMIN_이다")
    void claimGrantsAdmin() {
        // ADR-055 — 이 창구는 관리자 부트스트랩 전용이다. 요청 바디의 role 은 결과를 바꾸지 못한다.
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        RoleClaimResponse res = service.claim(req, actor("1001", null));

        assertThat(res.accessToken()).isNotBlank();
        assertThat(res.role()).isEqualTo("ADMIN");
        assertThat(res.userNo()).isEqualTo(1001L);
        assertThat(res.userName()).isEqualTo("테스트사용자");

        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(res.accessToken()).getPayload();
        assertThat(claims.getSubject()).isEqualTo("1001");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.get("channel", String.class)).isEqualTo("INTERNAL");
        assertThat(claims.getIssuer()).isEqualTo("klid-auth");

        // 쓰기는 <조건부> INSERT 하나다 — 무조건 upsert 로 되돌리면 부트스트랩 조건이 문장에서 사라진다.
        verify(lsUserRoleRepository, times(1)).upsertRoleIfNoneHasRole(eq(1001L), eq("ADMIN"));
        verify(lsUserRoleRepository, times(0)).upsertRole(anyLong(), anyString());
    }

    @Test
    @DisplayName("성공시_evict_호출_verify")
    void claimSuccessEvictsCache() {
        // 자가부여 성공 시 인가 역할 캐시 무효화 (no-tx: 즉시 evict 분기)
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        service.claim(req, actor("1001", null));

        verify(userRoleResolver).evict(1001L);
    }

    @Test
    @DisplayName("★요청_바디에_REVIEWER_를_실어도_ADMIN_이_부여된다_요청값은_결과를_바꾸지_못한다")
    void requestedRoleCannotChangeGrantedRole() {
        // ADR-055 — 구 정책(WORKER·REVIEWER 자가부여 개방)은 폐기됐다. 되살리면 관리자 공유
        //   패스워드 하나로 다시 검수자가 되어, 역할 획득과 관리자 유효창이 같은 비밀에 매달리던
        //   상태로 돌아간다(그 둘을 가르는 것이 이 결정의 핵심이다).
        // given: 구 클라이언트가 보내던 REVIEWER 요청
        RoleClaimRequest req = new RoleClaimRequest(Role.REVIEWER, adminPlaintext);

        // when
        RoleClaimResponse res = service.claim(req, actor("1001", null));

        // then: 요청값과 무관하게 ADMIN 이 부여된다(403 이 아니다 — 그 요청은 거절 대상이 아니라
        //   결과를 바꾸지 못할 뿐이다). WORKER 도 같다.
        assertThat(res.role()).isEqualTo("ADMIN");
        verify(lsUserRoleRepository, times(1)).upsertRoleIfNoneHasRole(eq(1001L), eq("ADMIN"));
        verify(lsUserRoleRepository, times(0)).upsertRoleIfNoneHasRole(anyLong(), eq("REVIEWER"));

        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(res.accessToken()).getPayload();
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("★자가부여_화이트리스트는_ADMIN_하나뿐이다_구_WORKER_REVIEWER_개방_폐기")
    void whitelistIsAdminOnly() {
        // ★되돌림(mutation) 감지용 직접 단언 — 목록에 WORKER/REVIEWER 를 되살리면 이 시험이 RED 다.
        //   구조(목록 대조)는 유지된다: Role enum 이 확장될 때 새 역할이 <자동으로> 자가부여
        //   대상이 되면 안 된다(deny-by-default).
        assertThat(RoleClaimService.allowedClaimRoles())
                .containsExactly(Role.ADMIN)
                .doesNotContain(Role.WORKER, Role.REVIEWER, Role.PORTAL_USER);
    }

    @Test
    @DisplayName("★관리자가_이미_있으면_패스워드가_맞아도_409_창구가_닫힌다")
    void bootstrapClosedWhenAdminExists() {
        // ADR-055 — 관리자가 한 명이라도 생기면 이 창구는 누구에게도 열리지 않는다.
        //   ★게이트를 빼면 이 시험이 RED 다.
        when(lsUserRoleRepository.countByRoleCd("ADMIN")).thenReturn(1L);
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        // 쓰기는 물론이고 자동등록조차 하지 않는다 — 어차피 거절될 요청이 행을 만들면 안 된다.
        verify(lsUserRoleRepository, times(0)).upsertRoleIfNoneHasRole(anyLong(), anyString());
        verify(userRepository, times(0)).upsertUser(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("★닫힌_창에서는_패스워드가_틀려도_409_지_401_이_아니다_게이트가_패스워드보다_앞이다")
    void closedWindowShortCircuitsBeforePasswordCheck() {
        // ★순서 고정 — 게이트를 패스워드 검증 <뒤>로 옮기면 여기서 401 이 되어 RED 다.
        //   뒤로 내리면 닫힌 창에서도 패스워드 정오답이 401/409 로 갈려 <패스워드 오라클>이 된다.
        when(lsUserRoleRepository.countByRoleCd("ADMIN")).thenReturn(1L);
        RoleClaimRequest wrongPw = new RoleClaimRequest(Role.ADMIN, wrongPassword());

        assertThatThrownBy(() -> service.claim(wrongPw, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("★닫힌_창_판정도_시도_쿼터를_소모한다_게이트가_rate_limit_뒤다_개폐_정찰_차단")
    void closedWindowConsumesRateLimitQuota() {
        // ★순서 고정 — 게이트가 rate limit <앞>이면 쿼터가 소모되지 않아 6회째도 409 가 되고 RED 다.
        //   앞에 두면 인증된 내부 사용자가 아무 패스워드로 1회 호출해 응답만으로 창의 개폐를 읽는다
        //   (닫힘=409 / 열림=401·429). "열림" 은 곧 <지금 맞히면 관리자가 된다>는 신호라, 쿼터를
        //   한 톨도 쓰지 않고 얻는 정찰 창구가 된다.
        when(lsUserRoleRepository.countByRoleCd("ADMIN")).thenReturn(1L);
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        // 계정 한도 5회 — 전부 409(창이 닫혀서)로 거절되지만 쿼터는 줄어든다.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CONFLICT));
        }
        // 6회째 — 쿼터가 바닥나 게이트에 닿기도 전에 429 다.
        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("★게이트_통과_후_다른_노드가_관리자를_만들면_조건부_INSERT_가_0을_돌려_409")
    void bootstrapClosedByConditionalInsertRace() {
        // 확인(count)과 쓰기 사이의 창은 문장 안의 조건이 닫는다 — 그 조건을 무조건 INSERT 로
        //   바꾸면 두 노드가 동시에 관리자가 된다.
        when(lsUserRoleRepository.upsertRoleIfNoneHasRole(anyLong(), anyString())).thenReturn(0);
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("PORTAL_USER_는_여전히_거절된다")
    void PORTAL_USER_는_여전히_거절된다() {
        // REVIEWER 개방과 무관하게 포털 역할은 별도 채널이라 본 API 로 부여되지 않는다.
        RoleClaimRequest req = new RoleClaimRequest(Role.PORTAL_USER, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));

        verify(lsUserRoleRepository, times(0)).upsertRoleIfNoneHasRole(anyLong(), anyString());
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

        // then: 쿼터가 소모되지 않았으므로 정상 부트스트랩이 그대로 성공한다.
        RoleClaimResponse res = service.claim(new RoleClaimRequest(Role.ADMIN, adminPlaintext), actor("1001", null));
        assertThat(res.role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("★저장소에_이미_역할이_있어도_창이_열려_있으면_ADMIN_이_부여된다_AC_127")
    void existingStoredRoleStillBootstraps() {
        // ★구 구현은 여기서 409 를 던졌고, 그것이 부트스트랩을 도달 불가능하게 만든 두 축 중 하나다
        //   (다른 하나는 actor.role() 검사). 이미 운영 중인 시스템에는 역할 없는 사용자가 없다.
        when(lsUserRoleRepository.findByUserNo(1001L))
                .thenReturn(Optional.of(LsUserRole.of(1001L, "WORKER")));
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        RoleClaimResponse res = service.claim(req, actor("1001", null));

        assertThat(res.role()).isEqualTo("ADMIN");
        verify(lsUserRoleRepository, times(1)).upsertRoleIfNoneHasRole(eq(1001L), eq("ADMIN"));
    }

    @Test
    @DisplayName("잘못된_admin_password_401_UNAUTHORIZED")
    void wrongPasswordReturns401() {
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, wrongPassword());

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(lsUserRoleRepository, times(0)).upsertRoleIfNoneHasRole(anyLong(), anyString());
    }

    @Test
    @DisplayName("★작업자_토큰으로_와도_창이_열려_있으면_ADMIN_이_부여된다_자동등록_교착_방지")
    void workerActorStillBootstraps() {
        // ★진입 시 자동 등록이 <같은 요청의 앞단>에서 작업자 역할을 부여하므로, 이 창구에 도달하는
        //   신규 사용자의 principal 은 사실상 항상 WORKER 다. 여기서 거절하면 도달 경로가 0개다.
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        RoleClaimResponse res = service.claim(req, actor("1001", Role.WORKER));

        assertThat(res.role()).isEqualTo("ADMIN");
        verify(lsUserRoleRepository, times(1)).upsertRoleIfNoneHasRole(eq(1001L), eq("ADMIN"));
    }

    @Test
    @DisplayName("★검수자_토큰으로_와도_창이_열려_있으면_ADMIN_이_부여된다_업그레이드_배포_축")
    void reviewerActorStillBootstraps() {
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        RoleClaimResponse res = service.claim(req, actor("1001", Role.REVIEWER));

        assertThat(res.role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("★창이_닫힌_뒤에는_역할_보유자도_무보유자도_모두_409")
    void windowClosedRejectsBothRoleHolderAndNot() {
        when(lsUserRoleRepository.countByRoleCd("ADMIN")).thenReturn(1L);
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        for (Role actorRole : new Role[]{null, Role.WORKER, Role.REVIEWER}) {
            assertThatThrownBy(() -> service.claim(req, actor("1001", actorRole)))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CONFLICT));
        }
        verify(lsUserRoleRepository, times(0)).upsertRoleIfNoneHasRole(anyLong(), anyString());
    }

    @Test
    @DisplayName("포털채널_actor는_role없어도_409_CONFLICT_교차채널_상승차단")
    void portalChannelActorReturns409() {
        // H-1 — PORTAL 채널 actor 는 role==null 이어도 거절한다 (교차채널 권한상승 차단).
        //   ★역할 보유 검사는 완화됐지만 <채널 격리는 그대로다> — 함께 풀지 말 것.
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, portalActor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(lsUserRoleRepository, times(0)).upsertRoleIfNoneHasRole(anyLong(), anyString());
    }

    @Test
    @DisplayName("actor_null_시_401")
    void nullActorReturns401() {
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("시도_5회_초과시_429_TOO_MANY_REQUESTS")
    void rateLimitAfterFiveAttempts() {
        String bad = wrongPassword();
        RoleClaimRequest badReq = new RoleClaimRequest(Role.ADMIN, bad);
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
        RoleClaimRequest bad = new RoleClaimRequest(Role.ADMIN, wrongPassword());
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
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

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
                new RoleClaimRateLimiter(null, 5, 50), new AdminPasswordVerifier(""), "klid-auth");
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);

        assertThatThrownBy(() -> empty.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("admin_hash_가_BCrypt_형식이_아니면_부트_거절")
    void rejectNonBcryptHashAtBoot() {
        JwtKeyResolver resolver = () -> key;
        // 해시 형식 판정은 이제 공용 판정기가 소유한다 — 거절 지점만 옮겨졌고 정책은 그대로다.
        assertThatThrownBy(() -> new AdminPasswordVerifier("plaintext-not-bcrypt"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("userNo_가_숫자가_아니면_400_INVALID_INPUT")
    void nonNumericSubjectReturns400() {
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext);
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
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext, "sjs123", "신재석");

        // when
        RoleClaimResponse res = service.claim(req, actor("9999", null));

        // then: 404 가 아니라 자동등록 후 부여된다
        assertThat(res.userNo()).isEqualTo(9999L);
        assertThat(res.userName()).isEqualTo("신재석");
        verify(userRepository, times(1)).upsertUser(eq(9999L), eq("sjs123"), eq("신재석"));
        verify(lsUserRoleRepository, times(1)).upsertRoleIfNoneHasRole(eq(9999L), eq("ADMIN"));
    }

    @Test
    @DisplayName("역할클레임시_기존_사용자는_이름이_갱신된다")
    void 역할클레임시_기존_사용자는_이름이_갱신된다() {
        // given: 이미 존재하는 사용자(1001) + 관제가 바뀐 이름을 인계
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext, "sjs123", "변경된이름");

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
        RoleClaimRequest blank = new RoleClaimRequest(Role.ADMIN, adminPlaintext, "   ", "\t\n ");

        service.claim(blank, actor("1001", null));

        verify(userRepository, times(1)).upsertUser(eq(1001L), isNull(), isNull());
    }

    @Test
    @DisplayName("표시정보의_제어문자는_제거되고_컬럼길이로_잘린다")
    void 표시정보의_제어문자는_제거되고_컬럼길이로_잘린다() {
        // CWE-117 — 이 값은 화면·JWT name 클레임·다른 코드의 로그로 흘러간다.
        //   길이 상한은 DTO @Size 가 먼저 막지만 다른 호출자를 대비해 서비스에서도 자른다.
        String forged = "홍길\r\n[RoleClaim] granted userNo=1 role=REVIEWER";
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext, null, forged);

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
        RoleClaimRequest forged = new RoleClaimRequest(Role.ADMIN, adminPlaintext, "victim", "피해자");
        service.claim(forged, actor("1001", null));

        // then: 쓰기는 전부 sub(1001) 대상이다 — 위조값은 자기 행의 표시 이름에만 반영된다
        verify(userRepository, times(1)).upsertUser(eq(1001L), eq("victim"), eq("피해자"));
        verify(userRepository, times(0)).upsertUser(eq(2001L), anyString(), anyString());
        verify(lsUserRoleRepository, times(1)).upsertRoleIfNoneHasRole(eq(1001L), eq("ADMIN"));
        verify(lsUserRoleRepository, times(0)).upsertRoleIfNoneHasRole(eq(2001L), anyString());
    }

    @Test
    @DisplayName("창이_닫혀_있으면_자동등록도_하지_않는다")
    void 창이_닫혀_있으면_자동등록도_하지_않는다() {
        // 어차피 409 로 거절될 요청이 사용자 행을 만들면 안 된다(순서: 창 판정 → 자동등록).
        //   ★판정 축이 "이미 역할이 있는가" 에서 "창이 닫혔는가" 로 옮겨졌다(AC-127).
        when(lsUserRoleRepository.countByRoleCd("ADMIN")).thenReturn(1L);
        RoleClaimRequest req = new RoleClaimRequest(Role.ADMIN, adminPlaintext, "sjs123", "신재석");

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(userRepository, times(0)).upsertUser(anyLong(), anyString(), anyString());
    }
}
