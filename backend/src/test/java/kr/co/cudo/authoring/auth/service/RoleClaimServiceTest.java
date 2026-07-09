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
import kr.co.cudo.authoring.user.entity.MngAcctUser;
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
        MngAcctUser user = mock(MngAcctUser.class);
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

        service = new RoleClaimService(userRepository, lsUserRoleRepository, userRoleResolver, resolver, bcryptHash, "klid-auth");
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

        // LS_USER_ROLE 원자 upsert 로만 기록 (MNG_ACCT_USER_AUTHRT 쓰기 0건).
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
    @DisplayName("권한_없는_사용자가_REVIEWER_부여_성공_+_새토큰_반환")
    void claimReviewerSuccess() {
        RoleClaimRequest req = new RoleClaimRequest(Role.REVIEWER, adminPlaintext);

        RoleClaimResponse res = service.claim(req, actor("1001", null));

        assertThat(res.role()).isEqualTo("REVIEWER");
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(res.accessToken()).getPayload();
        assertThat(claims.get("role", String.class)).isEqualTo("REVIEWER");
        verify(lsUserRoleRepository).upsertRole(eq(1001L), eq("REVIEWER"));
    }

    @Test
    @DisplayName("role_claim_이미_LS역할보유시_409_CONFLICT_JWT_role_없어도")
    void alreadyHasLsRoleReturns409() {
        // JWT role 은 비어있지만(stale token) LS_USER_ROLE 에 이미 역할 존재.
        when(lsUserRoleRepository.findByUserNo(1001L))
                .thenReturn(Optional.of(LsUserRole.of(1001L, "WORKER")));
        RoleClaimRequest req = new RoleClaimRequest(Role.REVIEWER, adminPlaintext);

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
        RoleClaimRequest req = new RoleClaimRequest(Role.REVIEWER, adminPlaintext);

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
    @DisplayName("PORTAL_USER_역할_입력시_400_INVALID_INPUT")
    void portalUserRoleRejected() {
        RoleClaimRequest req = new RoleClaimRequest(Role.PORTAL_USER, adminPlaintext);

        assertThatThrownBy(() -> service.claim(req, actor("1001", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
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
        MngAcctUser user2 = mock(MngAcctUser.class);
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
        RoleClaimService empty = new RoleClaimService(userRepository, lsUserRoleRepository, userRoleResolver, resolver, "", "klid-auth");
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
                new RoleClaimService(userRepository, lsUserRoleRepository, userRoleResolver, resolver, "plaintext-not-bcrypt", "klid-auth")
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
    @DisplayName("존재하지_않는_userNo_404_NOT_FOUND")
    void missingUserReturns404() {
        RoleClaimRequest req = new RoleClaimRequest(Role.WORKER, adminPlaintext);
        when(userRepository.findByUserNo(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim(req, actor("9999", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
