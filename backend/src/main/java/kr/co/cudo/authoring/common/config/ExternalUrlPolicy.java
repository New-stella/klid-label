package kr.co.cudo.authoring.common.config;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 외부 연동 base-url 검증 정책 — <b>VLM / KPST 공용 판정 원천</b>.
 *
 * <h3>왜 공용화하는가</h3>
 * <p>과거 {@code WebClientConfig}(VLM)와 {@link KpstWebClientConfig} 는 같은 목적(외부 base-url 안전성
 * 검증)의 로직을 <b>각자 다른 규칙으로 복제</b>하고 있었다. VLM 은 무조건 HTTPS + 사설망 차단이라
 * 내부망/목업(평문 http, 사설 IP)에는 구조적으로 연결 자체가 불가능했고(기동 크래시), KPST 는
 * http/https 분기로 내부망 평문을 허용했다. "두 클라이언트의 보안 정책이 서로 다른 이유로 다르게
 * 동작"하는 상태였으므로, <b>판정 로직을 한 곳에 모으고 차이를 정책 값으로 명시</b>한다.
 *
 * <h3>정책 축(2개)</h3>
 * <ul>
 *   <li>{@link Transport} — 평문 http 허용 여부(CWE-319).</li>
 *   <li>{@code blockPrivateNetwork} — loopback/사설/link-local/메타데이터 대역 차단 여부(CWE-918 SSRF).</li>
 * </ul>
 * 두 축과 무관하게 <b>항상</b> 적용되는 하드 규칙: 빈값 거부, 파싱 가능, {@code http|https} 외 스키마 거부,
 * host 필수, placeholder/예제 호스트 거부(운영 사고 방지 fail-closed).
 *
 * <h3>보안 메모</h3>
 * <ul>
 *   <li>예외 메시지에 baseUrl 원문을 싣지 않는다(CWE-209 — userinfo 형태의 자격증명이 섞일 수 있다).
 *       진단에 필요한 scheme/host 만 노출한다.</li>
 *   <li>사설망 차단 정책일 때만 DNS 를 해석한다(rebinding 대응). 허용 정책에서는 해석하지 않는다 —
 *       내부 DNS 로만 풀리는 컨테이너 호스트명에서 부팅이 깨지지 않게 하기 위함이다.</li>
 * </ul>
 */
@Slf4j
public final class ExternalUrlPolicy {

    /** 명백한 placeholder/예제 호스트 — 어떤 정책에서도 차단(운영 미설정 배포 방지). */
    private static final Set<String> PLACEHOLDER_HOST_FRAGMENTS = Set.of(
            "example.com", "example.org", "example.net",
            "your-vlm-service", "your-service", "todo", "placeholder",
            "changeme", "change-me"
    );

    /** 전송 계층 허용 범위. */
    public enum Transport {
        /** https 만 허용 — 공중망 경유 전제(CWE-319). */
        HTTPS_ONLY,
        /** http/https 모두 허용 — 내부망 격리 전제. 평문 사용 시 기동 1회 WARN. */
        HTTP_OR_HTTPS
    }

    private final String propertyName;
    private final Transport transport;
    private final boolean blockPrivateNetwork;
    /** 평문 경고를 정책 인스턴스당 1회만 출력하기 위한 가드(기동 로그 폭주 방지). */
    private final AtomicBoolean plaintextWarned = new AtomicBoolean(false);

    private ExternalUrlPolicy(String propertyName, Transport transport, boolean blockPrivateNetwork) {
        this.propertyName = propertyName;
        this.transport = transport;
        this.blockPrivateNetwork = blockPrivateNetwork;
    }

    /**
     * 엄격 정책 — HTTPS 전용 + 사설/내부 대역 차단. 외부(공중망) 벤더 연동의 기본값이며 운영 표준이다.
     */
    public static ExternalUrlPolicy strict(String propertyName) {
        return new ExternalUrlPolicy(propertyName, Transport.HTTPS_ONLY, true);
    }

    /**
     * 내부망 정책 — 평문 http 허용 + 사설 대역 허용. <b>내부망 격리를 전제</b>로 하는 연동에만 사용한다
     * (KPST 비식별 솔루션, local/dev 목업). placeholder·비허용 스키마 차단은 그대로 유지된다.
     */
    public static ExternalUrlPolicy internalNetwork(String propertyName) {
        return new ExternalUrlPolicy(propertyName, Transport.HTTP_OR_HTTPS, false);
    }

    /**
     * base-url 을 검증한다.
     *
     * @return https 면 {@code true}, http 면 {@code false} (호출자의 TLS 구성 분기용)
     * @throws IllegalStateException 정책 위반 시 — 빈 생성 실패 → 애플리케이션 기동 차단(fail-closed)
     */
    public boolean check(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(propertyName + " 가 비어있습니다. 외부 연동 URL 설정 필수.");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            // CWE-209: 원문 미노출(자격증명 포함 가능).
            throw new IllegalStateException(propertyName + " 형식이 올바르지 않습니다 (설정을 확인하세요).", e);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase();
        boolean https = "https".equals(scheme);
        if (!https && !"http".equals(scheme)) {
            throw new IllegalStateException(
                    propertyName + " 은 http/https 스키마만 허용됩니다 (현재 scheme=" + scheme + ").");
        }
        if (!https && transport == Transport.HTTPS_ONLY) {
            throw new IllegalStateException(
                    propertyName + " 은 HTTPS 스키마만 허용됩니다 (현재 scheme=" + scheme
                            + "). CWE-319 cleartext 차단.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(propertyName + " 의 host 가 비어있습니다.");
        }
        String hostLower = host.toLowerCase();
        for (String fragment : PLACEHOLDER_HOST_FRAGMENTS) {
            if (hostLower.contains(fragment)) {
                throw new IllegalStateException(
                        propertyName + " 호스트가 placeholder/예제입니다: " + host + ". 환경 변수 미설정 의심.");
            }
        }
        if (blockPrivateNetwork) {
            requirePublicNetwork(host);
        }
        if (!https && plaintextWarned.compareAndSet(false, true)) {
            log.warn("[ExternalUrl] 평문 HTTP 전송 — 내부망 격리 전제. property={} host={}:{}",
                    propertyName, host, uri.getPort());
        }
        return https;
    }

    /** 내부/사설/메타데이터 대역 차단 (CWE-918). 도메인은 해석 후 검증한다(DNS rebinding 대응). */
    private void requirePublicNetwork(String host) {
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(propertyName + " 호스트를 해석할 수 없습니다: " + host, e);
        }
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            throw new IllegalStateException(
                    propertyName + " 이 내부/사설 네트워크를 가리킵니다: " + host + " → " + addr.getHostAddress()
                            + ". CWE-918 SSRF 차단.");
        }
        // 169.254.169.254 등 클라우드 메타데이터 대역(link-local 검사로 대개 잡히지만 명시적으로 한 번 더).
        String ip = addr.getHostAddress();
        if (ip.startsWith("169.254.")) {
            throw new IllegalStateException(
                    propertyName + " 이 클라우드 메타데이터 대역을 가리킵니다: " + ip + ". CWE-918 차단.");
        }
    }
}
