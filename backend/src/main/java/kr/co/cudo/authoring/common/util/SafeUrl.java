package kr.co.cudo.authoring.common.util;

import java.net.URI;
import java.util.Locale;

/**
 * URL 문자열에서 <b>호스트를 뽑고</b>, <b>자격증명(userinfo)을 가리는</b> 판정의 단일 지점.
 *
 * <h3>왜 한 곳인가</h3>
 * <p>같은 추출 규칙이 세 곳(비식별 신뢰 가드 · 연동 주소 검증기 · 연동 주소 전송 가드)에 필요하다.
 * 각자 복제하면 한쪽만 갱신돼 판정이 갈리는 것이 이 저장소의 반복 결함이므로, 규칙을 여기 하나로
 * 모은다. <b>이 클래스는 대역(사설/루프백/링크로컬)을 판정하지 않는다</b> — 대역 차단은 폐지된
 * 정책이며(2026-08-10 사용자 확정) 되살리지 않는다. 여기서 하는 일은 <b>문자열 파싱</b>뿐이다.
 *
 * <h3>{@code URI#getHost()} 가 null 을 주는 경우</h3>
 * <p>RFC 2396 문법을 벗어난 호스트 — 특히 <b>언더스코어가 포함된 호스트</b>
 * ({@code http://my_host:9400}, 도커 컴포즈 서비스명에서 흔하다) — 에서 {@code getHost()} 는
 * null 이다. 이를 "호스트 없음"으로 단정하면 정당한 내부 주소를 쓸 수 없게 되므로
 * {@code getAuthority()} 로 폴백해 호스트를 뽑는다.
 *
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class SafeUrl {

    /** userinfo 를 가릴 때 쓰는 고정 표기. 원문 조각을 남기지 않는다. */
    private static final String USERINFO_MASK = "***";

    private SafeUrl() {
    }

    /**
     * URL 에서 호스트만 <b>정규화(소문자 + IPv6 대괄호 제거)</b>해 돌려준다.
     *
     * @param url 원본 URL 문자열 (null/공백 허용)
     * @return 호스트. 파싱 불가하거나 호스트를 뽑을 수 없으면 {@code null}
     */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                host = hostFromAuthority(uri.getAuthority());
            }
            return (host == null || host.isBlank()) ? null : normalizeHost(host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 두 URL 이 <b>같은 호스트</b>를 가리키는지. 어느 한쪽이라도 호스트를 알 수 없으면 {@code false}
     * (판정 불가는 "다르다"로 낮춘다 — 자격증명 부착 판정에서 fail-secure 로 동작해야 한다).
     *
     * <p>포트·경로·스킴 차이는 보지 않는다 — 같은 서버의 포트/경로 변경까지 "다른 호스트"로 보면
     * 정당한 구성 변경에서 자격증명이 떨어져 기능이 깨진다.
     */
    public static boolean sameHost(String a, String b) {
        String hostA = hostOf(a);
        String hostB = hostOf(b);
        return hostA != null && hostA.equals(hostB);
    }

    /**
     * URL 의 userinfo({@code scheme://user:pass@host}) 를 고정 표기로 가린다.
     *
     * <p>로그 마스킹기({@code LogMaskingPatterns})는 키워드({@code password=} 등) 기반이라
     * {@code http://admin:s3cr3t@host} 형태를 <b>잡지 못한다</b>(실측). 감사 로그가 주소 원문을
     * 남기는 지점에서는 반드시 이 함수를 통과시킨다(CWE-532).
     *
     * @param url 원본 URL (null 허용)
     * @return userinfo 가 있으면 {@code scheme://***@host:port/path} 형태, 없으면 원본 그대로.
     *         파싱 불가하면 <b>원본을 돌려주지 않고</b> 고정 표기를 돌려준다(fail-secure).
     */
    public static String maskUserInfo(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String trimmed = url.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            // 파싱이 안 되면 userinfo 유무를 확정할 수 없다 — 원문을 흘리지 않는다.
            return trimmed.indexOf('@') >= 0 ? USERINFO_MASK : trimmed;
        }
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo == null) {
            // getUserInfo 가 null 이어도 비표준 authority(언더스코어 호스트 등)에는 '@' 가 남을 수 있다.
            String authority = uri.getRawAuthority();
            if (authority == null || authority.indexOf('@') < 0) {
                return trimmed;
            }
            int at = trimmed.indexOf('@');
            int schemeEnd = trimmed.indexOf("//");
            return (schemeEnd < 0 || at < schemeEnd)
                    ? USERINFO_MASK
                    : trimmed.substring(0, schemeEnd + 2) + USERINFO_MASK + trimmed.substring(at);
        }
        int at = trimmed.indexOf('@');
        int schemeEnd = trimmed.indexOf("//");
        if (at < 0 || schemeEnd < 0 || at < schemeEnd) {
            return USERINFO_MASK;
        }
        return trimmed.substring(0, schemeEnd + 2) + USERINFO_MASK + trimmed.substring(at);
    }

    /** URL 에 userinfo 가 실려 있는가 — 저장 거부 판정용. */
    public static boolean hasUserInfo(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getRawUserInfo() != null) {
                return true;
            }
            // 언더스코어 호스트 등 비표준 authority 는 getRawUserInfo 가 null 이라 authority 를 직접 본다.
            String authority = uri.getRawAuthority();
            return authority != null && authority.indexOf('@') >= 0;
        } catch (IllegalArgumentException e) {
            return false; // 형식 위반은 이 함수가 아니라 형식 검증이 거부한다.
        }
    }

    /**
     * {@code [userinfo@]host[:port]} 형태의 authority 에서 호스트만 뽑는다(폴백 경로).
     * IPv6 리터럴({@code [::1]:9201})은 대괄호 구간을 호스트로 인식한다.
     */
    private static String hostFromAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String candidate = authority.trim();
        int userInfoEnd = candidate.lastIndexOf('@');
        if (userInfoEnd >= 0) {
            candidate = candidate.substring(userInfoEnd + 1);
        }
        if (candidate.startsWith("[")) {
            int close = candidate.indexOf(']');
            return close > 1 ? candidate.substring(1, close) : null;
        }
        int portStart = candidate.indexOf(':');
        if (portStart >= 0) {
            candidate = candidate.substring(0, portStart);
        }
        return candidate.isBlank() ? null : candidate;
    }

    /** 비교용 정규화 — IPv6 대괄호 제거 + 소문자. */
    private static String normalizeHost(String host) {
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
