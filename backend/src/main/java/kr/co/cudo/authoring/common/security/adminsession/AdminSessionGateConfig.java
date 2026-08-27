package kr.co.cudo.authoring.common.security.adminsession;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 관리자 유효창 인터셉터 배선. [@design ADR-046]
 *
 * <p><b>경로가 아니라 애노테이션으로 매칭한다</b> — 경로 패턴 목록을 여기에 적으면 그 목록이
 * 「무엇이 보호되는가」의 두 번째 진실원이 되어, 창구가 옮겨지거나 늘 때 한쪽만 갱신된다.
 * 전 경로에 등록하되 실제 판정은 {@link AdminSessionInterceptor} 가 핸들러의 애노테이션으로
 * 하므로, 표식이 없는 창구는 아무 비용 없이 지나간다.
 */
@Configuration
@RequiredArgsConstructor
public class AdminSessionGateConfig implements WebMvcConfigurer {

    private final AdminSessionInterceptor adminSessionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminSessionInterceptor).addPathPatterns("/**");
    }
}
