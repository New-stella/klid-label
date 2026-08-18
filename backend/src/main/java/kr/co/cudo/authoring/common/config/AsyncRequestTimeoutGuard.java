package kr.co.cudo.authoring.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 비동기 응답 제한시간({@code spring.mvc.async.request-timeout})의 <b>실효값</b> 하한 강제 — 기동 차단.
 *
 * <h3>왜 파일 가드만으로는 부족한가 (이 가드가 닫는 구멍)</h3>
 * <p>{@code AsyncRequestTimeoutConfigGuardTest} 는 <b>yml 파일의 텍스트</b>만 본다. 그런데 Spring 은
 * relaxed binding 으로 <b>환경변수가 yml 을 이긴다</b> — 온프렘 배포는 실제로
 * {@code deploy/onprem/config/backend/env.template} → systemd {@code EnvironmentFile} 로 값을 주입하므로
 * {@code SPRING_MVC_ASYNC_REQUEST_TIMEOUT=30000} 한 줄이면 운영이 <b>조용히 30초로 되돌아가고</b>
 * 파일 가드는 그대로 통과한다. 시스템 프로퍼티·커맨드라인 인자도 같다.
 *
 * <p>그래서 판정을 {@link Environment} 에서 한다 — yml(공통/프로파일별)·환경변수·시스템 프로퍼티·
 * 커맨드라인 인자 등 <b>모든 프로퍼티 소스</b>가 한 번에 덮인다
 * ({@link kr.co.cudo.authoring.common.security.ForwardedHeadersConfigGuard} 와 동일 축).
 *
 * <h3>왜 경고가 아니라 기동 차단인가</h3>
 * <p>이 결함의 실패 모드는 <b>"아무도 실패하지 않는다"</b> 이다. 값이 30초로 되돌아가도 서버는 200 을
 * 내고 응답을 쓰다가 끊으므로, 서버 로그·헬스체크·메트릭은 모두 정상으로 보이고 사용자만 <b>깨진
 * ZIP</b> 을 받는다. 배포 로그의 WARN 한 줄로는 이런 무증상 실패를 막을 수 없다
 * ({@link QuartzClusteringGuard} 와 동일 판단). 되돌리려면 이 가드를 함께 고쳐야 하며, 그 편집은
 * 리뷰에 드러난다.
 *
 * <h3>왜 local/dev 도 열어 두지 않는가</h3>
 * <p>낮은 값을 정당화할 사용처를 <b>찾지 못했다</b>. 이 값이 짧으면 로컬·개발에서도 같은 결함(대용량
 * 내려받기 절단)이 그대로 재현되고, 애초에 그 절단을 실측한 곳이 개발 환경이다. 그리고 local 만
 * 열어 두면 "로컬에선 되니까" 라는 재도입 경로가 열린다. 제한시간 <b>자체</b>를 실험해야 한다면 그때
 * {@link #MIN_TIMEOUT} 을 함께 고치는 것이 맞다 — 조용히 되돌려지는 것보다 낫다.
 *
 * <h3>하한 · 무제한 금지</h3>
 * <ul>
 *   <li><b>미설정 = 위반</b> — 키가 없으면 컨테이너 기본값 30초가 적용된다(그것이 원래 결함이다).</li>
 *   <li><b>0 이하 = 위반</b> — Servlet 규약상 "제한 없음" 이라 하한은 만족하지만, 조용히 멈춘 연결이
 *       {@link MvcAsyncExecutorConfig} 의 경계 있는 풀 스레드를 <b>영원히</b> 붙잡는다. 경계를 세운
 *       취지를 정반대로 되돌리므로 허용하지 않는다.</li>
 *   <li><b>{@link #MIN_TIMEOUT} 미만 = 위반</b> — 그 이상은 허용한다(상한은 두지 않는다).</li>
 * </ul>
 */
@Slf4j
@Component
public class AsyncRequestTimeoutGuard {

    /** 비동기 응답(StreamingResponseBody 등)의 절대 제한시간 키. */
    static final String KEY = "spring.mvc.async.request-timeout";

    /**
     * 실효값 하한 — 30분. 근거는 {@code application.yml} 의 해당 키 주석(실효 대역 5Mbps 에서 약 1.1GB).
     * 화면 쪽 상한({@code frontend DATAMART_DOWNLOAD_TIMEOUT_MS})도 같은 근거로 30분이다.
     */
    public static final Duration MIN_TIMEOUT = Duration.ofMinutes(30);

    private final Environment environment;

    public AsyncRequestTimeoutGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void check() {
        String raw = environment.getProperty(KEY);
        verify(raw);
        log.info("[MvcAsync] 비동기 응답 제한시간 실효값={} (하한 {})", raw, MIN_TIMEOUT);
    }

    /**
     * 순수 판정 — 컨테이너 없이도 단위 검증할 수 있게 원문 문자열을 받는다.
     *
     * @param rawValue {@link #KEY} 의 <b>실효</b> 원문(어떤 프로퍼티 소스에서 왔든). 미설정이면 {@code null}
     * @throws IllegalStateException 미설정 · 해석 불가 · 0 이하 · {@link #MIN_TIMEOUT} 미만
     */
    static void verify(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw violation("미설정", "컨테이너 기본값 30초가 적용됩니다");
        }
        Duration effective;
        try {
            // 단위 없는 숫자는 ms 로 읽는다 — WebMvcProperties.Async#requestTimeout 바인딩과 같은 규칙이라
            // "가드는 통과했는데 실제 바인딩은 다른 값" 이 되지 않는다.
            effective = DurationStyle.detectAndParse(rawValue.trim(), ChronoUnit.MILLIS);
        } catch (RuntimeException e) {
            throw violation("해석 불가", "기동 시 설정 바인딩이 실패하거나 기본값 30초로 떨어집니다");
        }
        if (effective.isZero() || effective.isNegative()) {
            throw violation("0 이하(제한 없음)",
                    "조용히 멈춘 연결이 MVC 비동기 풀의 스레드를 영원히 붙잡습니다(CWE-400)");
        }
        if (effective.compareTo(MIN_TIMEOUT) < 0) {
            throw violation("하한 " + MIN_TIMEOUT + " 미만",
                    "대용량 작업 데이터 내려받기가 응답 도중 잘려 깨진 ZIP 이 내려갑니다");
        }
    }

    /** 거부 사유에 <b>설정된 값 자체는 싣지 않는다</b> — 메시지는 무엇을 고쳐야 하는지만 말한다. */
    private static IllegalStateException violation(String verdict, String consequence) {
        return new IllegalStateException(
                KEY + " 실효값이 허용 범위 밖입니다(" + verdict + "). " + consequence + ". "
                        + "이 값은 '다음 쓰기까지의 공백'이 아니라 비동기 처리 시작 이후의 <절대 경과시간>이라, "
                        + "서버가 쉬지 않고 데이터를 쓰고 있어도 리셋되지 않습니다. "
                        + "최소 " + MIN_TIMEOUT + " 이상으로 두세요"
                        + "(yml·환경변수 SPRING_MVC_ASYNC_REQUEST_TIMEOUT·시스템 프로퍼티 모두 대상입니다). "
                        + "정말로 짧게 써야 한다면 AsyncRequestTimeoutGuard.MIN_TIMEOUT 을 함께 고치세요.");
    }
}
