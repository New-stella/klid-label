package kr.co.cudo.authoring.common.security.adminsession;

import kr.co.cudo.authoring.auth.AdminSessionTestSupport;
import io.jsonwebtoken.security.Keys;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.exception.GlobalExceptionHandler;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.sysconfig.controller.SystemConfigController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 유효창 <b>게이트 단일 지점</b> — 두 진입점이 같은 판정을 쓰는지 확인한다. [@design ADR-046]
 *
 * <h3>이 파일이 막는 것</h3>
 * <p>이 저장소는 「게이트를 호출처마다 배선하면 샌다」를 반복해서 겪었다. 요구가 사용자 역할 변경·
 * 업로드 시작·관리자 자격 교체로 넓어지는데, 창구마다 검증자를 직접 부르면 <b>새 창구 하나가 조용히
 * 빠지는</b> 구멍이 열린다. 여기서는 ①프로그램적 경로가 실제로 거부하는지 ②애노테이션 장치가 실제로
 * 동작하는지 ③<b>표식이 없는 창구는 막히지 않는지</b>를 함께 본다.
 *
 * <p>③이 특히 중요하다 — 게이트를 너무 넓게 걸어 정상 통로를 잠그는 것이 이 라운드의 최악의 회귀다.
 */
class AdminSessionGateTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "admin-session-gate-test-signing-key-0123456789ab".getBytes(StandardCharsets.UTF_8));
    private static final JwtKeyResolver RESOLVER = () -> KEY;

    private static final String SUBJECT = "1001";
    private static final String OTHER_SUBJECT = "2002";

    private AdminSessionTokenService tokenService;
    private AdminSessionGate gate;

    @BeforeEach
    void setUp() {
        tokenService = AdminSessionTestSupport.tokenService(RESOLVER, 10);
        gate = new AdminSessionGate(tokenService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private String tokenFor(String subject) {
        return tokenService.issue(subject, Instant.now()).token();
    }

    private static void authenticate(String subject) {
        TokenClaims claims = new TokenClaims(subject, Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null, List.of()));
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        assertThat(t).isInstanceOf(CustomException.class);
        return ((CustomException) t).getErrorCode();
    }

    // ------------------------------------------------------------------ 프로그램적 경로

    @Test
    @DisplayName("★유효창_없이_부르면_거부한다")
    void 유효창_없이_부르면_거부한다() {
        assertThatThrownBy(() -> gate.require(null, SUBJECT))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("유효한_토큰은_통과시킨다")
    void 유효한_토큰은_통과시킨다() {
        assertThatCode(() -> gate.require(tokenFor(SUBJECT), SUBJECT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★다른_사람의_토큰은_거부한다 — 결박이 없으면 유효창이 사실상 공유된다")
    void 다른_사람의_토큰은_거부한다() {
        assertThatThrownBy(() -> gate.require(tokenFor(OTHER_SUBJECT), SUBJECT))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("★만료된_토큰은_거부한다")
    void 만료된_토큰은_거부한다() {
        String stale = tokenService.issue(SUBJECT,
                Instant.now().minus(Duration.ofHours(2))).token();

        assertThatThrownBy(() -> gate.require(stale, SUBJECT))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("★거부_사유를_구분해_알리지_않는다 — 응답이 상태 오라클이 되면 안 된다(CWE-209)")
    void 거부_사유를_구분해_알리지_않는다() {
        String expired = tokenService.issue(SUBJECT, Instant.now().minus(Duration.ofHours(2))).token();
        String forged = tokenFor(SUBJECT) + "x";

        List<String> messages = List.of(
                messageOfRejection(null, SUBJECT),
                messageOfRejection("", SUBJECT),
                messageOfRejection(expired, SUBJECT),
                messageOfRejection(forged, SUBJECT),
                messageOfRejection(tokenFor(OTHER_SUBJECT), SUBJECT));

        assertThat(messages)
                .as("없음·빈값·만료·위조·타인 토큰이 서로 구분되면 그 차이가 곧 정보다")
                .containsOnly(messages.get(0));
    }

    @Test
    @DisplayName("★유효창_거부는_권한_없음과_문구가_다르다 — 막힌 사람이 돌아올 길을 알려야 한다")
    void 유효창_거부는_권한_없음과_문구가_다르다() {
        // AC-072 의 기대 결과 — 상태코드와 오류 코드로는 두 사유가 구분되지 않지만 <b>안내 문구는
        // 다르다</b>. 유효창이 없거나 끝난 경우에는 다시 인증할 자리를 안내하고, 검수자 권한이 없는
        // 경우에는 권한이 없다고만 말한다. 그러지 않으면 막힌 사람이 스스로 돌아올 길이 없어
        // 재인증 동선 자체가 성립하지 않는다.
        //
        // ★위 «거부_사유를_구분해_알리지_않는다» 와 <b>대상이 다르다</b> — 그 시험은 유효창 거부
        // <b>안에서</b> 없음·만료·위조·타인 발급이 서로 구분되지 않음을 본다. 이 시험은 유효창 축과
        // <b>인가 축</b>이 서로 구분됨을 본다. 둘을 같은 것으로 읽어 한쪽을 지우면, 문구를 하나로
        // 합치는 «일관성» 정리가 재인증 동선을 소리 없이 없앤다.
        String rejected = messageOfRejection(null, SUBJECT);

        assertThat(rejected)
                .as("인가 거부와 같은 문구면 두 사유가 화면에서 구분되지 않는다")
                .isNotEqualTo(ErrorCode.FORBIDDEN.defaultMessage())
                .as("다시 인증할 자리를 가리키지 않으면 막힌 사람이 돌아올 길이 없다")
                .contains("인증");
    }

    private String messageOfRejection(String token, String subject) {
        try {
            gate.require(token, subject);
        } catch (CustomException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
            return e.getMessage();
        }
        throw new AssertionError("거부되지 않았다 — token=" + token);
    }

    // ------------------------------------------------------------------ 선언적 경로(장치 동작)

    @Nested
    @DisplayName("애노테이션 장치")
    class 애노테이션_장치 {

        private MockMvc mvc;

        @BeforeEach
        void setUpMvc() {
            mvc = MockMvcBuilders
                    .standaloneSetup(new GuardedController(), new PlainController(),
                            new ClassLevelGuardedController())
                    .addInterceptors(new AdminSessionInterceptor(gate))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("★애노테이션이_붙은_창구는_유효창_없이_호출하면_403")
        void 애노테이션이_붙은_창구는_유효창_없이_호출하면_403() throws Exception {
            authenticate(SUBJECT);

            mvc.perform(get("/test/guarded")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("애노테이션이_붙은_창구도_유효창이_있으면_통과한다")
        void 애노테이션이_붙은_창구도_유효창이_있으면_통과한다() throws Exception {
            authenticate(SUBJECT);

            mvc.perform(get("/test/guarded").header(AdminSessionGate.HEADER, tokenFor(SUBJECT)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("★애노테이션이_없는_창구는_유효창_없이도_통과한다 — 게이트를 넓게 걸어 정상 통로를 잠그지 않는다")
        void 애노테이션이_없는_창구는_유효창_없이도_통과한다() throws Exception {
            authenticate(SUBJECT);

            mvc.perform(get("/test/plain")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("클래스에_붙인_애노테이션도_적용된다 — 새 메서드가 조용히 빠지지 않게")
        void 클래스에_붙인_애노테이션도_적용된다() throws Exception {
            authenticate(SUBJECT);

            mvc.perform(get("/test/class-level")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("★인증_주체를_알_수_없으면_유효한_토큰이라도_거부한다 (fail-closed)")
        void 인증_주체를_알_수_없으면_거부한다() throws Exception {
            SecurityContextHolder.clearContext();

            mvc.perform(get("/test/guarded").header(AdminSessionGate.HEADER, tokenFor(SUBJECT)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("★subject_는_요청이_아니라_인증_주체에서_읽는다 — 남의 토큰을 제시해도 통과하지 않는다")
        void 남의_토큰으로는_통과하지_못한다() throws Exception {
            authenticate(SUBJECT);

            mvc.perform(get("/test/guarded").header(AdminSessionGate.HEADER, tokenFor(OTHER_SUBJECT)))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ 헤더 이름 단일 진실원

    @Test
    @DisplayName("헤더_이름의_진실원은_게이트다 — 창구마다 다른 헤더를 받게 되면 클라이언트가 갈린다")
    void 헤더_이름의_진실원은_게이트다() {
        assertThat(SystemConfigController.ADMIN_SESSION_HEADER).isEqualTo(AdminSessionGate.HEADER);
    }

    // ------------------------------------------------------------------ 픽스처

    @RestController
    static class GuardedController {
        @RequiresAdminSession
        @GetMapping("/test/guarded")
        String guarded() {
            return "ok";
        }
    }

    @RestController
    static class PlainController {
        @GetMapping("/test/plain")
        String plain() {
            return "ok";
        }
    }

    @RequiresAdminSession
    @RestController
    static class ClassLevelGuardedController {
        @GetMapping("/test/class-level")
        String classLevel() {
            return "ok";
        }
    }
}
