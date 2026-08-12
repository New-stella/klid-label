package kr.co.cudo.authoring.sysconfig.endpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 연동 주소를 <b>호출 시점에</b> 다시 읽어 요청 URL 을 고쳐 쓰는 필터 (R11 — 즉시 반영).
 *
 * <h3>왜 필요한가</h3>
 * <p>{@code WebClient} 의 {@code baseUrl} 은 <b>빈이 만들어질 때 한 번</b> 고정된다. 즉 설정 화면에서
 * 주소를 바꿔도 그 빈은 <b>재기동 전까지 옛 주소로 계속 호출한다</b> — 저장은 됐는데 아무것도 달라지지
 * 않는, 이 저장소가 이미 겪은 "코드는 맞는데 실동작 0건" 형태의 결함이 된다.
 *
 * <h3>고른 방식과 대안</h3>
 * <ul>
 *   <li><b>채택 — 필터에서 URL 재작성</b>: 호출부(각 {@code *Client})를 <b>한 줄도 고치지 않는다</b>.
 *       주소를 아는 책임이 클라이언트로 번지지 않고 설정 축 한 곳에 남는다.</li>
 *   <li>대안 ① 호출부가 절대 URI 를 넘긴다 — 클라이언트 4종을 모두 고쳐야 하고, 새 호출부가 생길 때마다
 *       같은 배선을 반복해야 해 "게이트를 호출처마다 배선하면 샌다" 패턴에 그대로 걸린다.</li>
 *   <li>대안 ② 매 호출마다 {@code WebClient} 를 새로 만든다 — 커넥션 풀이 매번 새로 생겨 자원이 샌다.</li>
 * </ul>
 *
 * <h3>대가(알고 선택한 것)</h3>
 * <ul>
 *   <li>필터는 요청 조립 스레드에서 <b>동기 설정 조회</b>(Caffeine TTL 60s → 미스 시 DB)를 한다.
 *       대개 메모리 조회지만 <b>미스 시 블로킹</b>이다. 이 4개 연동은 배치·통지 계열이라 호출 빈도가
 *       낮아 수용한다. (구 설계의 요청별 DNS 해석은 대역 판정 폐지와 함께 사라졌다.)</li>
 *   <li>{@code baseUrl} 이 그대로 남아 있어 override 가 없을 때의 동작은 <b>바뀌지 않는다</b>
 *       (기존 테스트·형상 영향 0). override 가 있을 때만 URL 이 바뀐다.</li>
 * </ul>
 */
@Slf4j
public final class IntegrationEndpointExchangeFilter {

    private IntegrationEndpointExchangeFilter() {
    }

    /**
     * @param endpoint    대상 연동
     * @param bootDefault 배포 기본값(빈의 {@code baseUrl} 로도 쓰인 값)
     * @param resolver    주소 판정 단일 지점. {@code null} 이면 항상 배포 기본값(단위 테스트 구성).
     */
    public static ExchangeFilterFunction of(IntegrationEndpoint endpoint,
                                            String bootDefault,
                                            IntegrationEndpointResolver resolver) {
        return (request, next) -> {
            // 리졸버는 예외를 던지지 않는다(값 판정은 저장 시점에 끝났다) — 전송을 막는 경로가 없다.
            String effective = resolver == null ? null : resolver.resolve(endpoint, bootDefault);
            if (effective == null || effective.isBlank() || sameBase(effective, bootDefault)) {
                return next.exchange(request);
            }
            URI rewritten;
            try {
                rewritten = rewrite(request.url(), bootDefault, effective);
            } catch (RuntimeException e) {
                log.warn("[IntegrationEndpoint] URL 재작성 실패 target={} — 전송을 중단합니다.",
                        endpoint.name());
                return Mono.error(e);
            }
            return next.exchange(ClientRequest.from(request).url(rewritten).build());
        };
    }

    /** 후행 슬래시 차이는 같은 주소로 본다. */
    private static boolean sameBase(String a, String b) {
        return trimTrailingSlash(a).equals(trimTrailingSlash(b == null ? "" : b));
    }

    /**
     * 요청 URL 의 <b>base 부분만</b> 새 주소로 갈아끼운다.
     *
     * <p>기본값에 경로가 붙어 있을 수 있으므로({@code https://host/api}) 그 접두를 떼고 새 base 의
     * 경로를 앞에 붙인다. raw 컴포넌트만 다뤄 <b>이미 인코딩된 경로·쿼리를 재인코딩하지 않는다</b>
     * (재인코딩하면 {@code %2F} 같은 값이 깨진다).
     *
     * <h3>★ 접두 판정은 {@code /} 경계를 본다</h3>
     * <p>{@code startsWith} 만 쓰면 <b>세그먼트 중간</b>에서 잘린다. bootPath={@code /api} · 요청
     * {@code /apix/foo} · override 에 경로가 없으면 {@code x/foo} 가 남아 {@code http://newhost} 뒤에
     * 그대로 붙어 <b>호스트가 {@code newhostx} 로 변조</b>된다(요청이 엉뚱한 서버로 나간다).
     * 지금 배포된 4종 기본값이 모두 경로 없는 형태라 도달하지 않을 뿐, 기본값에 경로가 붙는 순간
     * 활성화되는 결함이다.
     */
    static URI rewrite(URI current, String bootDefault, String effective) {
        URI effUri = URI.create(effective.trim());
        String authority = effUri.getRawAuthority();
        if (authority == null || authority.isBlank()) {
            // authority 를 못 뽑으면 문자열 연결이 "http://null…" 같은 엉뚱한 대상을 만든다.
            // 재작성을 포기하고 기존 오류 경로(전송 중단)로 넘긴다 — 조용히 다른 곳으로 보내지 않는다.
            throw new IllegalArgumentException("연동 주소에서 호스트를 확인할 수 없습니다.");
        }
        String bootPath = trimTrailingSlash(pathOf(bootDefault));
        String effPath = trimTrailingSlash(effUri.getRawPath() == null ? "" : effUri.getRawPath());

        String currentPath = current.getRawPath() == null ? "" : current.getRawPath();
        String suffix = stripBasePath(currentPath, bootPath);

        StringBuilder sb = new StringBuilder()
                .append(effUri.getScheme()).append("://").append(authority)
                .append(effPath).append(suffix);
        if (current.getRawQuery() != null) {
            sb.append('?').append(current.getRawQuery());
        }
        if (current.getRawFragment() != null) {
            sb.append('#').append(current.getRawFragment());
        }
        return URI.create(sb.toString());
    }

    /**
     * 요청 경로에서 배포 기본값의 base 경로를 <b>세그먼트 경계 기준</b>으로 떼어낸다.
     *
     * <p>경계가 맞지 않으면(=이 base 아래 요청이 아니면) 경로를 그대로 둔다. 남는 조각이 {@code /}
     * 로 시작하지 않으면 앞에 붙여, 새 base 와 이어붙일 때 세그먼트가 합쳐지지 않게 한다.
     */
    private static String stripBasePath(String currentPath, String bootPath) {
        if (bootPath.isEmpty()) {
            return currentPath;
        }
        if (currentPath.equals(bootPath)) {
            return "";
        }
        if (!currentPath.startsWith(bootPath + "/")) {
            return currentPath; // 세그먼트 경계 불일치 — 접두가 아니다(/api vs /apix)
        }
        String suffix = currentPath.substring(bootPath.length());
        return suffix.startsWith("/") ? suffix : "/" + suffix;
    }

    private static String pathOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String path = URI.create(url.trim()).getRawPath();
            return path == null ? "" : path;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String trimTrailingSlash(String value) {
        String v = value == null ? "" : value.trim();
        while (v.length() > 1 && v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return "/".equals(v) ? "" : v;
    }
}
