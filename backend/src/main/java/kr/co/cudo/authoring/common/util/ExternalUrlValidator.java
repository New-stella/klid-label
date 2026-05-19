package kr.co.cudo.authoring.common.util;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * 외부 URL SSRF/cleartext/placeholder 검증 공통 유틸 — Phase 2 신설, Phase 2 보강 (DEV_FIX H-2) 강화.
 *
 * <p>{@code WebClientConfig.validateExternalUrl} 로직을 재사용 가능하게 정적 메서드로 추출.
 * Phase 2 webhook 의 {@code resultFilePath} (외부 시스템이 전달하는 결과 위치 URL) SSRF 차단에
 * 동일하게 적용한다.
 *
 * <h3>검증 항목 (모두 통과해야 정상)</h3>
 * <ol>
 *   <li>스키마 — http/https 만 (옵션: HTTPS 강제 모드)</li>
 *   <li>호스트 비어있지 않음</li>
 *   <li>호스트(또는 IDN punycode → ASCII 변환 후)가 placeholder/예제 도메인이 아님 (운영 사고 방지 fail-closed)</li>
 *   <li>다중 A/AAAA 레코드 모두에 대해 loopback / any-local / link-local / site-local 아님 (CWE-918 SSRF)</li>
 *   <li>클라우드 메타데이터 IP 대역(169.254.x.x) 아님</li>
 *   <li>CGNAT 대역(100.64.0.0/10) 아님 — RFC 6598 캐리어 그레이드 NAT (DEV_FIX H-2)</li>
 *   <li>IPv4-mapped IPv6 (::ffff:0:0/96) 도 IPv4 로 환원하여 동일 검증 (DEV_FIX H-2)</li>
 * </ol>
 *
 * <h3>호출 위치</h3>
 * <ul>
 *   <li>{@code WebClientConfig.vlmWebClient} 빈 생성 시 (enabled=true 일 때)</li>
 *   <li>Phase 2 webhook {@code resultFilePath}/{@code deidentifiedFilePath} 페이로드 검증</li>
 * </ul>
 */
public final class ExternalUrlValidator {

    /**
     * 명백한 placeholder/예제 호스트 — 운영 사고 방지 fail-closed 가드.
     * IDN punycode 형태도 ASCII 변환 후 동일 검사가 가능하도록 ASCII 만 보관.
     */
    private static final Set<String> PLACEHOLDER_HOST_FRAGMENTS = Set.of(
            "example.com", "example.org", "example.net",
            "your-vlm-service", "your-service", "todo", "placeholder",
            "changeme", "change-me"
    );

    private ExternalUrlValidator() {
        // utility class
    }

    /**
     * 외부 URL 의 SSRF / cleartext / placeholder 위험을 검증한다.
     *
     * @param url            검증 대상 URL (file://, ftp:// 등 비-HTTP 스키마는 거부)
     * @param requireHttps   true 면 https 만 허용. false 면 http/https 둘 다 허용 (테스트/Dev 옵션)
     * @param contextHint    오류 메시지에 포함할 식별자 (예: "vlm.client.url", "resultFilePath")
     * @throws IllegalArgumentException 검증 실패 시 (fail-closed)
     */
    public static void validate(String url, boolean requireHttps, String contextHint) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(contextHint + " 가 비어있습니다.");
        }
        URI uri = parseUri(url.trim(), contextHint);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException(contextHint + " 의 스키마가 없습니다: " + url);
        }
        String schemeLower = scheme.toLowerCase();
        if (requireHttps) {
            if (!"https".equals(schemeLower)) {
                throw new IllegalArgumentException(
                        contextHint + " 은 HTTPS 스키마만 허용됩니다 (현재: " + scheme + "). CWE-319 cleartext 차단.");
            }
        } else if (!"http".equals(schemeLower) && !"https".equals(schemeLower)) {
            throw new IllegalArgumentException(
                    contextHint + " 은 http/https 스키마만 허용됩니다 (현재: " + scheme + ").");
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new IllegalArgumentException(contextHint + " 의 host 가 비어있습니다: " + url);
        }

        // IDN punycode → ASCII 변환 (CWE-1007 homograph attack, DEV_FIX H-2)
        String hostAscii;
        try {
            // IPv6 리터럴([::1]) 은 IDN.toASCII 가 거부할 수 있어 그대로 사용
            hostAscii = rawHost.startsWith("[") ? rawHost : IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    contextHint + " 호스트가 유효한 IDN 이 아닙니다: " + rawHost, e);
        }
        String hostLower = hostAscii.toLowerCase();
        for (String fragment : PLACEHOLDER_HOST_FRAGMENTS) {
            if (hostLower.contains(fragment)) {
                throw new IllegalArgumentException(
                        contextHint + " 호스트가 placeholder/예제입니다: " + rawHost);
            }
        }

        // 다중 A/AAAA 레코드 모두 검증 (CWE-918 DNS rebinding 대응, DEV_FIX H-2)
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(rawHost);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(contextHint + " 호스트를 해석할 수 없습니다: " + rawHost, e);
        }
        if (addrs == null || addrs.length == 0) {
            throw new IllegalArgumentException(contextHint + " 호스트를 해석할 수 없습니다: " + rawHost);
        }
        for (InetAddress addr : addrs) {
            checkAddress(addr, rawHost, contextHint);
        }
    }

    private static URI parseUri(String url, String contextHint) {
        try {
            return new URI(url);
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalArgumentException(contextHint + " 형식이 올바르지 않습니다: " + url, e);
        }
    }

    /**
     * 단일 IP 에 대한 SSRF 차단 검사 — IPv4-mapped IPv6 환원, CGNAT, 메타데이터, 사설망.
     */
    private static void checkAddress(InetAddress addr, String hostForMsg, String contextHint) {
        InetAddress effective = unwrapIPv4MappedIPv6(addr);

        if (effective.isLoopbackAddress() || effective.isAnyLocalAddress()
                || effective.isLinkLocalAddress() || effective.isSiteLocalAddress()) {
            throw new IllegalArgumentException(
                    contextHint + " 이 내부/사설 네트워크를 가리킵니다: " + hostForMsg
                            + " → " + effective.getHostAddress() + ". CWE-918 SSRF 차단.");
        }

        String ip = effective.getHostAddress();
        if (ip.startsWith("169.254.")) {
            throw new IllegalArgumentException(
                    contextHint + " 이 클라우드 메타데이터 대역을 가리킵니다: " + ip + ". CWE-918 차단.");
        }

        // CGNAT 100.64.0.0/10 차단 (RFC 6598) — DEV_FIX H-2
        if (effective instanceof Inet4Address) {
            byte[] octets = effective.getAddress();
            int first = octets[0] & 0xFF;
            int second = octets[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) {
                throw new IllegalArgumentException(
                        contextHint + " 이 CGNAT(100.64.0.0/10) 대역을 가리킵니다: " + ip + ". CWE-918 차단.");
            }
        }

        // IPv6 ULA(fc00::/7) 도 사설 — isSiteLocalAddress 가 RFC 3879 deprecated 후 일부 JDK 에서 false 일 수 있어 보강
        if (effective instanceof Inet6Address) {
            byte[] octets = effective.getAddress();
            int firstByte = octets[0] & 0xFF;
            if ((firstByte & 0xFE) == 0xFC) {
                throw new IllegalArgumentException(
                        contextHint + " 이 IPv6 ULA(fc00::/7) 대역을 가리킵니다: " + ip + ". CWE-918 차단.");
            }
        }
    }

    /**
     * IPv4-mapped IPv6 (::ffff:a.b.c.d) 를 IPv4 로 환원. DEV_FIX H-2.
     * 그 외 IPv6 또는 IPv4 는 그대로 반환.
     */
    private static InetAddress unwrapIPv4MappedIPv6(InetAddress addr) {
        if (!(addr instanceof Inet6Address ipv6)) return addr;
        byte[] bytes = ipv6.getAddress();
        // ::ffff:0:0/96 패턴 (앞 10바이트 0, 11~12바이트 0xff, 0xff)
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return addr;
        }
        if ((bytes[10] & 0xFF) != 0xFF || (bytes[11] & 0xFF) != 0xFF) return addr;
        byte[] ipv4Bytes = new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
        try {
            return InetAddress.getByAddress(ipv4Bytes);
        } catch (UnknownHostException e) {
            // 4바이트 배열로 절대 실패하지 않음. 방어적 fallback.
            return addr;
        }
    }
}
