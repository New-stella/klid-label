package kr.co.cudo.authoring.observability.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VlmUrlPresentCondition 판정 단위 테스트 — Spring 컨텍스트 없이 순수 판정만 본다.
 *
 * <p>판정 축은 "키 존재"가 아니라 <b>"값이 비지 않음"</b>이다. 미주입 형상의 실제 모습은
 * {@code application.yml} 의 {@code ${VLM_SERVICE_URL:}} 때문에 "키가 있고 값이 빈 문자열"이며,
 * 그 형상에서 빈이 등록되면 집계 헬스가 상시 DOWN 이 된다.
 *
 * <p>따옴표 케이스는 문자열 표현식(SpEL) 조건으로 되돌리는 것을 막는 가드다 — 그 방식은 값의
 * 작은따옴표에서 파싱이 깨져 <b>애플리케이션 기동 자체가 실패</b>한다.
 */
class VlmUrlPresentConditionTest {

    private final VlmUrlPresentCondition condition = new VlmUrlPresentCondition();

    @Test
    @DisplayName("주소가_없으면_false")
    void no_match_when_absent() {
        assertThat(matches(null)).isFalse();
    }

    @Test
    @DisplayName("주소가_빈문자열이면_false")
    void no_match_when_empty() {
        assertThat(matches("")).isFalse();
    }

    @Test
    @DisplayName("주소가_공백뿐이면_false")
    void no_match_when_whitespace() {
        assertThat(matches("   ")).isFalse();
    }

    @Test
    @DisplayName("주소가_있으면_true")
    void match_when_present() {
        assertThat(matches("http://vlm.example.internal:9400")).isTrue();
    }

    @Test
    @DisplayName("주소에_작은따옴표가_있어도_예외없이_true")
    void match_when_value_contains_quote() {
        assertThat(matches("http://vlm.example.internal:9400/a'b")).isTrue();
    }

    private boolean matches(String url) {
        MockEnvironment environment = new MockEnvironment();
        if (url != null) {
            environment.setProperty(VlmUrlPresentCondition.URL_PROPERTY, url);
        }
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return condition.matches(context, mock(AnnotatedTypeMetadata.class));
    }
}
