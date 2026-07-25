package kr.co.cudo.authoring.common.security.webhook;

import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 웹훅 게이트 배선 — 인터셉터 등록 + 필터 <b>이중 등록 차단</b>.
 *
 * <h3>인터셉터 등록</h3>
 * <p>{@link WebhookGateInterceptor} 를 웹훅 경로 접두에 등록한다. 접두 패턴이라 하위에 신규
 * 엔드포인트가 생겨도 기본이 <b>보호됨</b>이다.
 *
 * <h3>필터 이중 등록 차단 (S-12)</h3>
 * <p>{@link HmacWebhookFilter} 는 {@code @Component} 라 Spring Boot 가 서블릿 컨테이너 필터로도
 * 자동 등록하고, 동시에 {@code SecurityConfig} 가 Security 필터 체인에도 등록한다. 실행 경로가 둘이면
 * dispatch 종류(ERROR/ASYNC)나 순서에 따라 "어느 쪽이 실제로 도는지" 가 달라져 우회 분석이 흐려진다.
 * 자동 등록을 꺼서 <b>실행 경로를 Security 체인 1개로 고정</b>한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebhookGateConfig implements WebMvcConfigurer {

    private final WebhookGateInterceptor webhookGateInterceptor;

    /**
     * 경로 패턴은 {@link WebhookProtectedPaths#GATE_PATTERNS} 를 참조한다 — 여기에 문자열을 다시 적으면
     * 필터(allowlist)와 인터셉터(2단 게이트)가 <b>2벌로 드리프트</b>하여, 신규 웹훅 추가 시 2단 게이트만
     * 조용히 미적용되는 구멍이 생긴다(DEV_FIX L-1).
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(webhookGateInterceptor)
                .addPathPatterns(WebhookProtectedPaths.GATE_PATTERNS);
    }

    /** 서블릿 컨테이너 자동 등록 비활성 — Security 체인 등록만 유효하게 한다. */
    @Bean
    public FilterRegistrationBean<HmacWebhookFilter> hmacWebhookFilterRegistration(
            HmacWebhookFilter hmacWebhookFilter) {
        FilterRegistrationBean<HmacWebhookFilter> registration =
                new FilterRegistrationBean<>(hmacWebhookFilter);
        registration.setEnabled(false);
        return registration;
    }
}
