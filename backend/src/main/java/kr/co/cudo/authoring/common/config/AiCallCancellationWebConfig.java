package kr.co.cudo.authoring.common.config;

import kr.co.cudo.authoring.common.client.AiCallCancellationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 취소 스코프를 매어 줄 경로 등록 — <b>사람이 기다리는 온디맨드 추론 4종만</b>.
 *
 * <p>범위를 좁히는 것이 이 설정의 요점이다. 전역으로 걸면 모든 요청이 취소 식별자를 실을 수 있게 되어
 * 등록소가 커지고, 배치·통지처럼 취소가 의미 없는 경로까지 스코프를 갖는다.
 *
 * <p>⚠ 경로는 <b>컨텍스트 경로({@code /api})를 뺀</b> 서블릿 내부 경로다. 새 온디맨드 추론 경로가
 * 생기면 여기 추가하지 않는 한 취소가 되지 않는다 — 그 사실을 {@code AiCancelApiTest} 가
 * 실제 매핑과 대조해 고정한다.
 */
@Configuration
@RequiredArgsConstructor
public class AiCallCancellationWebConfig implements WebMvcConfigurer {

    /** 취소를 지원하는 온디맨드 추론 경로. */
    public static final List<String> CANCELLABLE_PATHS = List.of(
            "/v1/frames/*/autolabel",
            "/v1/frames/*/sam2-segment",
            "/v1/frames/*/sam2-track",
            "/v1/frames/*/yolo-track");

    private final AiCallCancellationInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(CANCELLABLE_PATHS);
    }
}
