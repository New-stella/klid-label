package kr.co.cudo.authoring.common.client;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 취소 소유자 키에 <b>채널</b>이 들어가는지 — 포털 사용자 식별자와 내부 사용자 식별자가 같은 문자열이어도 서로의
 * 요청을 끊지 못해야 한다(CWE-639). 같은 채널 안의 판정 결과는 subject 비교와 같다.
 *
 * @design API-204, API-258
 */
class AiCallCancellationOwnerKeyTest {

    private final AiCallCancellationRegistry registry = new AiCallCancellationRegistry();

    private static TokenClaims claims(String sub, Role role, Channel channel) {
        return new TokenClaims(sub, role, channel, Instant.now().plusSeconds(600));
    }

    @AfterEach
    void tearDown() {
        registry.unbind();
        SecurityContextHolder.clearContext();
    }

    /**
     * ★ 등록(인터셉터)과 취소(창구)가 <b>같은 키</b>를 만드는지 배선째 본다. 한쪽만 키 형식을 바꾸면 취소 API 는
     * 200 을 돌려주는데 <b>아무것도 끊지 않는다</b>(조용한 무동작) — 진행 중이 아닌 식별자만 보는 시험은 그 차이를
     * 구분하지 못한다.
     */
    @Test
    @DisplayName("★인터셉터가_등록한_요청을_내부_취소_창구가_실제로_끊는다_키_배선_일치")
    void interceptorAndInternalCancelWindowAgreeOnKey() throws Exception {
        TokenClaims worker = claims("100", Role.WORKER, Channel.INTERNAL);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(worker, null, java.util.List.of()));
        AiCallCancellationInterceptor interceptor = new AiCallCancellationInterceptor(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/frames/1/autolabel");
        request.addHeader(AiCallCancellationInterceptor.REQUEST_ID_HEADER, "req-wire");
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        try {
            var controller = new kr.co.cudo.authoring.label.controller.AiCancelController(registry);
            assertThat(controller.cancel("req-wire", claims("100", Role.PORTAL_USER, Channel.PORTAL))
                    .data().cancelled()).as("다른 채널의 같은 subject 는 끊지 못한다").isFalse();
            assertThat(controller.cancel("req-wire", worker).data().cancelled()).isTrue();
        } finally {
            interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        }
    }

    @Test
    @DisplayName("★같은_subject라도_다른_채널의_요청은_끊지_못한다")
    void sameSubjectOtherChannelCannotCancel() {
        TokenClaims portal = claims("100", Role.PORTAL_USER, Channel.PORTAL);
        TokenClaims internal = claims("100", Role.WORKER, Channel.INTERNAL);
        AiCallScope scope = registry.open("req-portal", AiCallCancellationRegistry.ownerKey(portal));
        try {
            assertThat(registry.cancel("req-portal", AiCallCancellationRegistry.ownerKey(internal))).isFalse();
            assertThat(registry.cancel("req-portal", AiCallCancellationRegistry.ownerKey(portal))).isTrue();
        } finally {
            scope.close();
        }
    }

    @Test
    @DisplayName("내부_채널끼리는_subject가_같으면_끊고_다르면_못_끊는다_종전_판정과_같다")
    void internalChannelBehaviourUnchanged() {
        TokenClaims worker = claims("100", Role.WORKER, Channel.INTERNAL);
        TokenClaims other = claims("101", Role.WORKER, Channel.INTERNAL);
        AiCallScope scope = registry.open("req-int", AiCallCancellationRegistry.ownerKey(worker));
        try {
            assertThat(registry.cancel("req-int", AiCallCancellationRegistry.ownerKey(other))).isFalse();
            assertThat(registry.cancel("req-int", AiCallCancellationRegistry.ownerKey(worker))).isTrue();
        } finally {
            scope.close();
        }
    }

    @Test
    @DisplayName("주체가_없으면_키가_없어_추적도_취소도_하지_않는다")
    void missingSubjectYieldsNoKey() {
        assertThat(AiCallCancellationRegistry.ownerKey(null)).isNull();
        assertThat(AiCallCancellationRegistry.ownerKey(claims(" ", Role.WORKER, Channel.INTERNAL))).isNull();
        assertThat(AiCallCancellationRegistry.ownerKey(claims("7", Role.WORKER, Channel.INTERNAL)))
                .isEqualTo("INTERNAL:7");
    }
}
