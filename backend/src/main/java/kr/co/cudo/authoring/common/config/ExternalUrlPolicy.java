package kr.co.cudo.authoring.common.config;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
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
 * <h3>★ 이 판정은 더 이상 기동을 막지 않는다 — 전송을 막는다 (2026-09-03 확정, 구속)</h3>
 * <p>규칙은 그대로이고 <b>적용 시점만 옮겼다</b>. 호출부는 {@link #inspect(String)} 로 판정을 받아
 * 들고 있다가 <b>그 주소로 나가려는 순간</b> 거부한다({@link ExternalEndpointAddress}). 한 연동의
 * 설정 실수로 저작 업무 전체가 멈추는 것을 막기 위함이며, <b>막아야 할 것은 잘못된 곳으로 나가는
 * 것이지 기동이 아니다</b>.
 *
 * <p>⚠ {@link #check(String)} 은 <b>남는다</b> — 저장 창구(400 응답)와 후보 장비 걸러내기처럼
 * <b>기동과 무관한</b> 호출부가 그대로 쓴다. 이 메서드를 다시 빈 생성 경로에 걸지 말 것.
 *
 * <h3>보안 메모</h3>
 * <ul>
 *   <li>예외 메시지에 baseUrl 원문을 싣지 않는다(CWE-209 — userinfo 형태의 자격증명이 섞일 수 있다).
 *       진단에 필요한 scheme/host 만 노출한다.</li>
 *   <li>사설망 차단 정책에서는 DNS 해석 실패를 <b>거부</b>로 취급한다(rebinding 대응). 내부망 허용
 *       정책에서는 해석 실패를 통과시킨다 — 내부 DNS 로만 풀리는 컨테이너 호스트명에서 부팅이 깨지지
 *       않게 하기 위함이다. 단 <b>해석에 성공한 경우</b>에는 허용 정책에서도 링크로컬/메타데이터·ULA·
 *       CGNAT 대역을 거부한다(IMDS 는 어떤 환경에서도 정상 위탁 대상이 아니다) — {@link ReservedRange}.</li>
 *   <li>대역 판정은 {@code InetAddress} 의 술어({@code isSiteLocalAddress} 등)가 아니라 <b>명시적 CIDR
 *       바이트 비교</b>로 한다. JDK 술어는 IPv6 ULA({@code fc00::/7} — AWS IPv6 IMDS 포함)와 CGNAT
 *       ({@code 100.64/10} — Alibaba 메타데이터 포함)을 판정하지 못해 두 정책 모두에서 통과시켰다
 *       (3차 전수검증 G-ISSUE-21).</li>
 *   <li>호스트가 여러 주소로 해석되면 <b>전 주소</b>를 검사하고 하나라도 차단 대역이면 거부한다
 *       ({@code getAllByName}). 첫 주소만 보면 검사 대상과 실제 접속 대상이 갈린다(G-ISSUE-22).</li>
 *   <li>IPv6 안에 IPv4 를 임베드하는 표기(IPv4-mapped {@code ::ffff:x.x.x.x} · IPv4-compatible
 *       {@code ::x.x.x.x} · <b>NAT64 well-known prefix {@code 64:ff9b::/96}</b>, RFC 6052)는 모두
 *       <b>IPv4 로 언랩한 뒤</b> IPv4 규칙을 적용한다 — 언랩하지 않으면 {@code 64:ff9b::a9fe:a9fe}
 *       (= 169.254.169.254 IMDS) 처럼 IPv6 표기로 위장해 IPv4 대역 규칙을 통째로 우회할 수 있다.</li>
 *   <li><b>알려진 잔여위험(범위 밖 — 별도 이슈로 이월)</b>: 이 정책은 <b>기동 시 1회</b> 해석·판정하며,
 *       이후 매 요청의 DNS 재해석 결과는 재검증하지 않는다(DNS rebinding TOCTOU). base-url 이
 *       사용자 입력이 아니라 <b>배포 설정값</b>이라 현 설계가 방어 대상으로 선언하지 않은 범위다.
 *       구조 변경(요청 시점 재검증/고정 IP 커넥션)은 별도 이슈에서 다룬다.</li>
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
     * @throws IllegalStateException 정책 위반 시
     */
    public boolean check(String baseUrl) {
        Verdict verdict = inspect(baseUrl);
        if (verdict.rejected()) {
            throw new IllegalStateException(verdict.detail(), verdict.cause());
        }
        return verdict.https();
    }

    /**
     * ★ <b>같은 판정을 예외 없이 돌려준다</b> (2026-09-03 확정, 구속).
     *
     * <h3>왜 예외 없는 형태가 필요한가</h3>
     * <p>이 판정은 지금까지 <b>빈 생성 시점</b>에만 쓰였고, 위반하면 예외 → 빈 생성 실패 →
     * <b>기동 차단</b>이었다. 그런데 「외부 연동 주소가 어떤 상태여도 애플리케이션은 뜬다」가
     * 확정되면서, 호출부는 <b>판정 결과를 들고 있다가 전송 시점에 쓰는</b> 형태가 필요해졌다.
     * 예외는 흐름을 끊으므로 그 용도에 맞지 않는다.
     *
     * <p><b>규칙은 하나도 바뀌지 않았다</b> — {@link #check(String)} 이 이 메서드를 그대로 부르므로
     * 두 경로가 갈릴 여지가 없다. 바뀐 것은 <b>언제 막는가</b>이지 <b>무엇을 막는가</b>가 아니다.
     *
     * <p>{@link Violation} 은 <b>호스트가 섞이지 않은 사유 분류</b>다 — 전송 실패 메시지·응답에는 이
     * 분류만 싣고, 호스트가 담긴 {@link Verdict#detail()} 은 서버 로그에만 남긴다(CWE-209).
     */
    public Verdict inspect(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Verdict.reject(Violation.BLANK,
                    propertyName + " 가 비어있습니다. 외부 연동 URL 설정 필수.", null);
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            // CWE-209: 원문 미노출(자격증명 포함 가능).
            return Verdict.reject(Violation.MALFORMED,
                    propertyName + " 형식이 올바르지 않습니다 (설정을 확인하세요).", e);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase();
        boolean https = "https".equals(scheme);
        if (!https && !"http".equals(scheme)) {
            return Verdict.reject(Violation.SCHEME_NOT_ALLOWED,
                    propertyName + " 은 http/https 스키마만 허용됩니다 (현재 scheme=" + scheme + ").", null);
        }
        if (!https && transport == Transport.HTTPS_ONLY) {
            return Verdict.reject(Violation.CLEARTEXT_NOT_ALLOWED,
                    propertyName + " 은 HTTPS 스키마만 허용됩니다 (현재 scheme=" + scheme
                            + "). CWE-319 cleartext 차단.", null);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Verdict.reject(Violation.NO_HOST, propertyName + " 의 host 가 비어있습니다.", null);
        }
        String hostLower = host.toLowerCase();
        for (String fragment : PLACEHOLDER_HOST_FRAGMENTS) {
            if (hostLower.contains(fragment)) {
                return Verdict.reject(Violation.PLACEHOLDER_HOST,
                        propertyName + " 호스트가 placeholder/예제입니다: " + host
                                + ". 환경 변수 미설정 의심.", null);
            }
        }
        Verdict networkVerdict = inspectNetwork(host);
        if (networkVerdict != null) {
            return networkVerdict;
        }
        if (!https && plaintextWarned.compareAndSet(false, true)) {
            log.warn("[ExternalUrl] 평문 HTTP 전송 — 내부망 격리 전제. property={} host={}:{}",
                    propertyName, host, uri.getPort());
        }
        return Verdict.accept(https);
    }

    /**
     * 사유 분류 — <b>입력이 섞이지 않는다</b>.
     *
     * <p>전송 실패 메시지에 실리는 것은 이 분류의 {@link #label()} 뿐이다. 호스트·스킴·해석 결과가
     * 담긴 상세 문구는 {@link Verdict#detail()} 로 서버 로그에만 남는다 — 거부 응답이 사유별로
     * 갈리면 그 차이가 곧 내부망을 훑는 신호가 된다(CWE-209).
     */
    public enum Violation {
        /** 주소가 비어 있다 — <b>위험한 것이 아니라 아직 안 정해진 것</b>이다. */
        BLANK("주소 미설정"),
        MALFORMED("주소 형식 오류"),
        SCHEME_NOT_ALLOWED("허용되지 않는 스킴"),
        CLEARTEXT_NOT_ALLOWED("평문 http 불허"),
        NO_HOST("호스트 없음"),
        PLACEHOLDER_HOST("예시·미설정 호스트"),
        UNRESOLVABLE_HOST("호스트 해석 실패"),
        RESERVED_RANGE("예약 대역");

        private final String label;

        Violation(String label) {
            this.label = label;
        }

        /** 사람이 읽을 사유 — 주소를 담지 않는다. */
        public String label() {
            return label;
        }
    }

    /** 판정 결과 — 통과이거나, 사유가 붙은 거부다. */
    public static final class Verdict {

        private static final Verdict ACCEPT_HTTPS = new Verdict(null, null, null, true);
        private static final Verdict ACCEPT_HTTP = new Verdict(null, null, null, false);

        private final Violation violation;
        private final String detail;
        private final Throwable cause;
        private final boolean https;

        private Verdict(Violation violation, String detail, Throwable cause, boolean https) {
            this.violation = violation;
            this.detail = detail;
            this.cause = cause;
            this.https = https;
        }

        static Verdict accept(boolean https) {
            return https ? ACCEPT_HTTPS : ACCEPT_HTTP;
        }

        static Verdict reject(Violation violation, String detail, Throwable cause) {
            return new Verdict(violation, detail, cause, false);
        }

        public boolean rejected() {
            return violation != null;
        }

        /** 거부 사유 분류 — 통과면 {@code null}. */
        public Violation violation() {
            return violation;
        }

        /** <b>서버 로그 전용</b> 상세 문구(호스트 포함 가능) — 통과면 {@code null}. */
        public String detail() {
            return detail;
        }

        Throwable cause() {
            return cause;
        }

        /** https 면 {@code true}. 거부된 판정에서는 의미가 없다. */
        public boolean https() {
            return https;
        }
    }

    /**
     * 호스트 해석 + 대역 판정 — 위반이면 거부 판정을, 통과면 {@code null} 을 돌려준다.
     *
     * <p>정책 축에 따라 <b>해석 실패의 취급이 갈린다</b>(클래스 주석 §보안 메모): 사설망 차단
     * 정책은 거부(rebinding 대응), 내부망 허용 정책은 통과(컨테이너 호스트명 기동 보장).
     */
    private Verdict inspectNetwork(String host) {
        InetAddress[] addresses;
        if (blockPrivateNetwork) {
            try {
                addresses = InetAddress.getAllByName(normalizeHost(host));
            } catch (UnknownHostException e) {
                return Verdict.reject(Violation.UNRESOLVABLE_HOST,
                        propertyName + " 호스트를 해석할 수 없습니다: " + host, e);
            }
        } else {
            addresses = resolveQuietly(host);
            if (addresses == null) {
                return null; // 해석 불가 = 개발 네트워크 밖 → 통과(기동 보장)
            }
        }
        return inspectResolvedAddresses(host, addresses);
    }

    /**
     * 평문(http) 엔드포인트에 인증 토큰이 설정된 경우 경고를 남긴다 (CWE-319).
     *
     * <p>평문 구간에서는 {@code Authorization: Bearer ...} 헤더가 네트워크에 그대로 흐른다. 내부망
     * 격리를 전제로 하는 연동에서는 평문이 정상 형상이라 <b>거부하지 않고 경고만</b> 남기며,
     * <b>토큰 값은 절대 출력하지 않는다</b>(존재/길이만 — CWE-532).
     *
     * <p>여기(판정 원천)에 두어 <b>모든 연동이 같은 문구·같은 조건</b>으로 경고하게 한다 — 연동마다
     * 복제하면 한쪽만 고쳐지는 비대칭이 다시 생긴다({@link ProfileGatedUrlPolicy} 도 이 구현을 부른다).
     *
     * @param logTag 연동 식별용 로그 태그(주소·토큰이 아니다)
     */
    public static void warnIfTokenOnCleartext(String logTag, String baseUrl, String token) {
        if (token == null || token.isBlank() || baseUrl == null) {
            return;
        }
        if (!baseUrl.trim().toLowerCase(Locale.ROOT).startsWith("http://")) {
            return;
        }
        log.warn("[{}] 평문 http 엔드포인트에 인증 토큰이 설정되어 있습니다 — 토큰이 네트워크에 평문 노출됩니다"
                + " (CWE-319). tokenLength={}", logTag, token.trim().length());
    }

    /**
     * 내부망 허용 정책에서도 <b>링크로컬/메타데이터·ULA·CGNAT 대역</b>은 거부한다 (CWE-918) —
     * 대역 목록과 사유는 {@link ReservedRange} 참조. 내부망 목업이라 해도 클라우드 메타데이터(IMDS)는
     * 정상적인 위탁 대상이 될 수 없기 때문이다.
     *
     * <p>단 해석 실패는 <b>실패로 취급하지 않는다</b> — 컨테이너 내부 서비스명({@code klid-mock-server})은
     * 도커 밖에서 해석되지 않으므로, 해석 실패를 거부로 처리하면 네이티브 기동이 통째로 막힌다
     * (2026-07-25 로컬 배선 실측). "해석되면 검사, 안 되면 통과" 가 이 경로의 규칙이다.
     */
    /** 호스트를 해석하되 실패하면 {@code null} 을 돌려준다(예외 없음) — 내부망 정책 전용. */
    private InetAddress[] resolveQuietly(String host) {
        try {
            return InetAddress.getAllByName(normalizeHost(host));
        } catch (UnknownHostException | SecurityException e) {
            return null; // 해석 불가 = 개발 네트워크 밖 → 통과(기동 보장)
        }
    }

    /** IPv6 리터럴은 {@code URI#getHost} 가 대괄호를 포함해 돌려주므로 벗겨낸다. */
    private static String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * <b>해석 결과에 대한 대역 판정 단일 진입점</b> — 해석(DNS)과 판정을 분리해 둔다.
     *
     * <p>분리 이유는 두 가지다: ① relaxed/strict 두 경로가 같은 판정을 쓰도록 강제한다(과거 두 경로가
     * 각자 다른 술어를 갖고 있어 갭이 생겼다) ② 실 DNS 없이 <b>다중 A/AAAA 응답</b>을 주입해 검증할 수
     * 있다(패키지 가시성 — 테스트 전용 우회 경로가 아니라 판정 그 자체다).
     *
     * <p><b>해석된 주소 전체</b>를 검사하며 <b>하나라도</b> 차단 대역이면 거부한다(G-ISSUE-22). 첫 주소만
     * 보면 실제 커넥션이 향하는 주소(JDK/OS 가 고른다)와 검사 대상이 달라져 판정이 우회된다.
     */
    void verifyResolvedAddresses(String host, InetAddress... addresses) {
        Verdict verdict = inspectResolvedAddresses(host, addresses);
        if (verdict != null) {
            throw new IllegalStateException(verdict.detail());
        }
    }

    /** 대역 판정 본체 — 위반이면 거부 판정을, 통과면 {@code null} 을 돌려준다. */
    private Verdict inspectResolvedAddresses(String host, InetAddress... addresses) {
        for (InetAddress addr : addresses) {
            ReservedRange range = ReservedRange.classify(addr);
            if (range == null || (!blockPrivateNetwork && !range.blockedEvenOnInternalNetwork)) {
                continue;
            }
            return Verdict.reject(Violation.RESERVED_RANGE, blockPrivateNetwork
                    ? propertyName + " 이 내부/사설/예약 네트워크를 가리킵니다: " + host + " → "
                            + addr.getHostAddress() + " [" + range.label + "]. CWE-918 SSRF 차단."
                    : propertyName + " 이 " + range.label + " 대역을 가리킵니다: " + host + " → "
                            + addr.getHostAddress() + ". 내부망(개발) 정책에서도 차단됩니다(CWE-918).", null);
        }
        return null;
    }

    /**
     * 차단 대상 IP 대역 — <b>명시적 CIDR 바이트 비교</b>로 판정한다 (G-ISSUE-21).
     *
     * <h3>왜 {@code InetAddress} 술어를 쓰지 않는가</h3>
     * <p>{@code Inet6Address#isSiteLocalAddress()} 는 <b>deprecated 된 {@code fec0::/10} 만</b> 판정하고
     * 실제 IPv6 사설 대역인 <b>ULA {@code fc00::/7}</b> 는 판정하지 않는다 — AWS 의 IPv6 IMDS
     * ({@code fd00:ec2::254})가 바로 이 대역이라 자격증명 탈취로 직결되는 구멍이었다. {@code Inet4Address}
     * 쪽도 RFC1918 만 보고 <b>CGNAT {@code 100.64/10}</b>(Alibaba Cloud 메타데이터 {@code 100.100.100.200}
     * 포함)를 놓친다. 문자열 prefix 비교({@code startsWith("169.254.")})도 표기 변형에 취약하므로
     * <b>바이트 비교</b>로 대체한다.
     *
     * <h3>{@code blockedEvenOnInternalNetwork} 의 의미</h3>
     * <p>{@code true} 인 대역은 <b>내부망 완화(relaxed) 정책에서도</b> 거부한다 — 클라우드 메타데이터/
     * 링크로컬은 어떤 환경에서도 정상 위탁 대상이 아니기 때문이다. ULA·CGNAT 를 이 집합에 넣어도
     * 개발 목업은 깨지지 않는다: 도커/사내망 목업은 RFC1918 IPv4(그리고 loopback)를 쓰며 ULA·CGNAT 는
     * 쓰지 않는다. 반대로 RFC1918·loopback 은 {@code false} 라 완화 경로에서 계속 허용된다.
     */
    private enum ReservedRange {
        WILDCARD("와일드카드(0.0.0.0/::)", true),
        UNSPECIFIED_V4("예약(0.0.0.0/8)", true),
        LOOPBACK("루프백", false),
        PRIVATE_V4("사설(RFC1918)", false),
        LINK_LOCAL("링크로컬/클라우드 메타데이터", true),
        CGNAT("CGNAT 공유(100.64.0.0/10)", true),
        ULA_V6("IPv6 ULA(fc00::/7)", true),
        SITE_LOCAL_V6("IPv6 사이트로컬(fec0::/10)", true),
        // ── IANA 특수목적 대역(방어심도) ─────────────────────────────────────────────
        // 아래 4종은 "정상 위탁 대상이 될 수 없는" 대역이라 relaxed 에서도 차단한다. 근거는
        // UNSPECIFIED_V4(0.0.0.0/8) 를 넣은 것과 같다 — 과차단 방향이라 안전하고, 도커/사내망
        // 목업이 쓰는 대역(RFC1918·loopback)은 여기에 포함되지 않아 개발 기동이 깨지지 않는다.
        PROTOCOL_ASSIGNMENT("IETF 프로토콜 할당(192.0.0.0/24)", true),
        BENCHMARK("벤치마킹(198.18.0.0/15)", true),
        MULTICAST("멀티캐스트(224.0.0.0/4 · ff00::/8)", true),
        RESERVED_FUTURE("예약(240.0.0.0/4)", true);

        private final String label;
        /** 내부망 완화(relaxed) 정책에서도 차단하는가. */
        private final boolean blockedEvenOnInternalNetwork;

        ReservedRange(String label, boolean blockedEvenOnInternalNetwork) {
            this.label = label;
            this.blockedEvenOnInternalNetwork = blockedEvenOnInternalNetwork;
        }

        /**
         * NAT64 well-known prefix {@code 64:ff9b::/96} 의 상위 12바이트 (RFC 6052 §2.1).
         * 하위 32비트가 임베드된 IPv4 주소다.
         */
        private static final byte[] NAT64_WELL_KNOWN_PREFIX = {
                0x00, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 0, 0, 0, 0, 0
        };

        /** 차단 대역이면 해당 분류를, 공인 주소면 {@code null} 을 돌려준다. */
        private static ReservedRange classify(InetAddress address) {
            byte[] raw = unwrapEmbeddedIpv4(address.getAddress());
            return raw.length == 4 ? classifyIpv4(raw) : classifyIpv6(raw);
        }

        /**
         * IPv6 에 임베드된 IPv4 를 언랩한다 — IPv4-mapped({@code ::ffff:x.x.x.x}) ·
         * IPv4-compatible({@code ::x.x.x.x}) · <b>NAT64 well-known prefix({@code 64:ff9b::/96})</b>.
         * 언랩하지 않으면 IPv6 표기로 위장해 IPv4 사설/메타데이터 대역 규칙을 통과시킬 수 있다
         * (예: {@code 64:ff9b::a9fe:a9fe} = NAT64 경유 169.254.169.254 IMDS).
         *
         * <p>NAT64 는 <b>well-known prefix 만</b> 판정한다. RFC 6052 의 Network-Specific Prefix
         * (사업자 임의 {@code /32~/96})와 RFC 8215 local-use {@code 64:ff9b:1::/48} 는 값을 사전에
         * 열거할 수 없어 일반 IPv6 로 남는다 — 우리 배포 형상에 NAT64 게이트웨이가 없어 실익이 낮다.
         */
        private static byte[] unwrapEmbeddedIpv4(byte[] raw) {
            if (raw.length != 16) {
                return raw;
            }
            if (hasPrefix(raw, NAT64_WELL_KNOWN_PREFIX)) {
                return new byte[] {raw[12], raw[13], raw[14], raw[15]};
            }
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    return raw;
                }
            }
            boolean mapped = (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF;
            boolean compatible = raw[10] == 0 && raw[11] == 0
                    && !(raw[12] == 0 && raw[13] == 0 && raw[14] == 0); // ::1 / :: 은 IPv6 규칙으로
            if (!mapped && !compatible) {
                return raw;
            }
            return new byte[] {raw[12], raw[13], raw[14], raw[15]};
        }

        /** {@code raw} 가 {@code prefix} 바이트열로 시작하는가. */
        private static boolean hasPrefix(byte[] raw, byte[] prefix) {
            for (int i = 0; i < prefix.length; i++) {
                if (raw[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }

        private static ReservedRange classifyIpv4(byte[] b) {
            int o0 = b[0] & 0xFF;
            int o1 = b[1] & 0xFF;
            if (o0 == 0) {
                return (o1 == 0 && b[2] == 0 && b[3] == 0) ? WILDCARD : UNSPECIFIED_V4; // 0.0.0.0/8
            }
            if (o0 == 127) {
                return LOOPBACK;                                                        // 127.0.0.0/8
            }
            if (o0 == 169 && o1 == 254) {
                return LINK_LOCAL;                                                      // 169.254.0.0/16
            }
            if (o0 == 10 || (o0 == 172 && (o1 & 0xF0) == 16) || (o0 == 192 && o1 == 168)) {
                return PRIVATE_V4;                        // 10/8 · 172.16/12 · 192.168/16
            }
            if (o0 == 100 && (o1 & 0xC0) == 64) {
                return CGNAT;                                                           // 100.64.0.0/10
            }
            if (o0 == 192 && o1 == 0 && b[2] == 0) {
                return PROTOCOL_ASSIGNMENT;   // 192.0.0.0/24 — NAT64/DS-Lite 등 프로토콜 전용 anycast
            }
            if (o0 == 198 && (o1 & 0xFE) == 18) {
                return BENCHMARK;                                                       // 198.18.0.0/15
            }
            if ((o0 & 0xF0) == 224) {
                return MULTICAST;                                                       // 224.0.0.0/4
            }
            if ((o0 & 0xF0) == 240) {
                return RESERVED_FUTURE;                        // 240.0.0.0/4 (255.255.255.255 포함)
            }
            return null;
        }

        private static ReservedRange classifyIpv6(byte[] b) {
            int o0 = b[0] & 0xFF;
            if (isAllZeroExceptLast(b)) {
                return b[15] == 1 ? LOOPBACK : (b[15] == 0 ? WILDCARD : null);          // ::1 · ::
            }
            if (o0 == 0xFE) {
                int high2 = b[1] & 0xC0;
                if (high2 == 0x80) {
                    return LINK_LOCAL;                                                  // fe80::/10
                }
                if (high2 == 0xC0) {
                    return SITE_LOCAL_V6;                                               // fec0::/10
                }
            }
            if ((o0 & 0xFE) == 0xFC) {
                return ULA_V6;                                                          // fc00::/7
            }
            if (o0 == 0xFF) {
                // IPv4 멀티캐스트(224/4)와 대칭. 한쪽만 막으면 v6 표기로 우회되고, 이 저장소가 반복해서
                // 겪은 "한 축만 갱신돼 비대칭 재발" 패턴이 그대로 재현된다.
                return MULTICAST;                                                       // ff00::/8
            }
            return null;
        }

        private static boolean isAllZeroExceptLast(byte[] b) {
            for (int i = 0; i < b.length - 1; i++) {
                if (b[i] != 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
