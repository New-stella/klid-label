package kr.co.cudo.authoring.common.security.webhook;

import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 웹훅 IP/CIDR 설정 파서 — <b>형식 오류는 기동 차단</b> (DEV_FIX N-4).
 *
 * <h3>왜 log.warn 이 아니라 기동 실패인가</h3>
 * <p>구 구현은 파싱 실패 CIDR 을 {@code log.warn} 후 <b>버렸다</b>. 그래서 오타 하나
 * ({@code 203.0.113.0/33}, 구분자 오타 {@code a;b}) 로 matcher 목록이 통째로 비고,
 * <ul>
 *   <li>{@link WebhookIpAllowlist} 는 "비어 있으면 전면 허용" 이라 <b>allowlist 가 조용히 꺼졌다</b>,</li>
 *   <li>{@link ClientIpResolver} 는 "비어 있으면 XFF 전면 무시" 라 <b>모든 콜백 IP 가 LB IP 하나로
 *       수렴</b>했다(A-ISSUE-15 원상복귀).</li>
 * </ul>
 * 두 경우 모두 <b>기동은 성공</b>하고 방어만 사라진다 — "미설정 시 기동 차단"(M-3) 이 없애려던
 * <b>조용한 취약 기본값</b>이 값 형식 축으로 되살아난 것이다. 그래서 프로파일과 무관하게
 * 잘못된 형식은 기동을 막는다. 값을 안 쓰겠다는 의사는 {@code none} 으로 <b>명시</b>한다.
 *
 * <h3>호스트명 금지</h3>
 * <p>{@link IpAddressMatcher} 는 호스트명을 받으면 생성 시점에 {@code InetAddress.getByName} 으로
 * <b>DNS 를 1회 해석해 결과를 고정</b>한다. 기동 시 DNS 실패면 기동이 깨지고, 성공해도 이후 DNS 변경이
 * 반영되지 않아 "설정한 대역과 실제 매칭 대역이 다른" 상태가 조용히 유지된다. IP/CIDR 리터럴만 받는다.
 */
final class WebhookCidrParser {

    /** IPv4 점4분 표기 — 0~255 옥텟 4개. */
    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    /** 프리픽스 길이 표기 — 숫자만(선행 0 허용, 상한은 IpAddressMatcher 가 주소 폭으로 검증). */
    private static final Pattern PREFIX_BITS = Pattern.compile("^\\d{1,3}$");

    /** IPv6 16비트 그룹 개수(압축 없는 전체 표기). */
    private static final int IPV6_MAX_GROUPS = 8;

    /** IPv6 텍스트 표기 최대 길이(IPv4 꼬리 포함 전체 표기 45자). */
    private static final int IPV6_MAX_TEXT_LENGTH = 45;

    private WebhookCidrParser() {
    }

    /**
     * CSV 를 matcher 목록으로 컴파일한다. 빈 값/공백은 빈 목록.
     *
     * @param settingKey 오류 메시지에 노출할 설정 키(예: {@code webhook.trusted-proxy-cidrs})
     * @param envKey     오류 메시지에 노출할 환경변수명
     * @throws BeanInitializationException 토큰 중 하나라도 IP/CIDR 리터럴이 아니면
     */
    static List<IpAddressMatcher> parseStrict(String csv, String settingKey, String envKey) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String token : csv.split(",")) {
            String cidr = token.trim();
            if (cidr.isEmpty()) {
                continue;
            }
            matchers.add(compile(cidr, settingKey, envKey));
        }
        return List.copyOf(matchers);
    }

    private static IpAddressMatcher compile(String cidr, String settingKey, String envKey) {
        String address = cidr;
        String bits = null;
        int slash = cidr.indexOf('/');
        if (slash >= 0) {
            address = cidr.substring(0, slash);
            bits = cidr.substring(slash + 1);
            if (!PREFIX_BITS.matcher(bits).matches()) {
                throw reject(cidr, settingKey, envKey, "프리픽스 길이가 숫자가 아닙니다");
            }
        }
        if (!isIpLiteral(address)) {
            throw reject(cidr, settingKey, envKey,
                    "IP/CIDR 리터럴이 아닙니다(호스트명·구분자 오타 등). 구분자는 쉼표(,) 입니다");
        }
        if (bits != null) {
            // 주소 폭 상한은 여기서 직접 검증한다 — Spring 버전에 따라 IpAddressMatcher 의 사전 검증
            // 유무가 달라질 수 있어, "오타가 조용히 통과" 하는 경로를 라이브러리에 위임하지 않는다.
            int max = address.indexOf(':') >= 0 ? 128 : 32;
            if (Integer.parseInt(bits) > max) {
                throw reject(cidr, settingKey, envKey,
                        "프리픽스 길이가 주소 폭(" + max + "bit)을 초과합니다");
            }
        }
        try {
            return new IpAddressMatcher(cidr);
        } catch (RuntimeException e) {
            throw reject(cidr, settingKey, envKey, "IP/CIDR 로 해석할 수 없습니다");
        }
    }

    private static BeanInitializationException reject(String cidr, String settingKey, String envKey,
                                                      String reason) {
        return new BeanInitializationException(
                settingKey + " (env " + envKey + ") 에 잘못된 값이 있습니다: '"
                        + WebhookProtectedPaths.sanitize(cidr) + "' — " + reason + ". "
                        + "잘못된 값을 무시하고 기동하면 방어가 조용히 꺼진 채 운영됩니다(DEV_FIX N-4). "
                        + "IP/CIDR 리터럴(예: 10.0.0.0/8, 203.0.113.5, 2001:db8::/32)만 쉼표로 나열하고, "
                        + "적용하지 않겠다면 'none' 을 명시하세요.");
    }

    /** IPv4/IPv6 <b>리터럴</b> 인가 — 호스트명·부분 문자열을 배제한다(요청 스레드 DNS 조회 차단, L-3). */
    static boolean isIpLiteral(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        return candidate.indexOf(':') >= 0 ? isIpv6Literal(candidate)
                                           : IPV4_LITERAL.matcher(candidate).matches();
    }

    /**
     * IPv6 텍스트 표기 구조 검증 — RFC 4291 §2.2 규칙을 <b>문자열 구조만으로</b> 판정한다.
     *
     * <p>{@code InetAddress.getByName} 을 판정에 쓰지 않는다 — 리터럴이 아닌 값이 들어오면 DNS 조회로
     * 이어지기 때문이다(요청 경로 L-3 · 호스트명 금지). 모두 만족해야 리터럴이다:
     * <ul>
     *   <li>길이 ≤ 45. 영역 식별자({@code %eth0})는 받지 않는다.</li>
     *   <li>{@code ::} 는 0회 또는 1회({@code :::}·{@code 1::2::3} 거부). {@code ::} 단독은 미지정 주소로 허용.</li>
     *   <li>{@code ::} 로 시작/끝나는 경우가 아니면 콜론 하나로 시작·끝나지 않는다({@code :1}·{@code 1:} 거부).</li>
     *   <li>16진 그룹은 1~4자. IPv4 점4분 꼬리는 <b>주소 맨 끝 그룹에만</b> 오며 16비트 그룹 2개로 센다.</li>
     *   <li>{@code ::} 가 없으면 그룹 수가 정확히 8, 있으면 7 이하(0 포함).</li>
     * </ul>
     */
    // [design: INT-006] [design: INT-003] — 허용 목록 값 형식(IPv6 는 :: 압축 포함 표준 텍스트 표기)
    private static boolean isIpv6Literal(String value) {
        if (value.length() > IPV6_MAX_TEXT_LENGTH || value.indexOf('%') >= 0 || value.contains(":::")) {
            return false;
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 1) >= 0) {
            return false; // "::" 두 번 이상
        }
        boolean compressed = compression >= 0;
        if (value.startsWith(":") && compression != 0) {
            return false; // ":1" — 단일 콜론 시작
        }
        if (value.endsWith(":") && (!compressed || compression != value.length() - 2)) {
            return false; // "1:" — 단일 콜론 끝
        }

        List<String> groups = new ArrayList<>();
        boolean lastGroupEndsAddress;
        if (compressed) {
            String head = value.substring(0, compression);
            String tail = value.substring(compression + 2);
            if (!splitGroups(head, groups) || !splitGroups(tail, groups)) {
                return false;
            }
            lastGroupEndsAddress = !tail.isEmpty(); // "1.2.3.4::" 처럼 끝이 "::" 면 IPv4 꼬리가 아니다
        } else {
            if (!splitGroups(value, groups)) {
                return false;
            }
            lastGroupEndsAddress = true;
        }

        int bitGroups = 0;
        for (int i = 0; i < groups.size(); i++) {
            String group = groups.get(i);
            if (group.indexOf('.') >= 0) { // IPv4 점4분 꼬리 (예: ::ffff:192.0.2.1)
                if (i != groups.size() - 1 || !lastGroupEndsAddress
                        || !IPV4_LITERAL.matcher(group).matches()) {
                    return false;
                }
                bitGroups += 2;
            } else if (isHexGroup(group)) {
                bitGroups++;
            } else {
                return false;
            }
        }
        return compressed ? bitGroups <= IPV6_MAX_GROUPS - 1 : bitGroups == IPV6_MAX_GROUPS;
    }

    /** 콜론으로 구분된 그룹을 모은다. 빈 문자열은 그룹 0개. 빈 그룹(연속 콜론·양끝 콜론)이 있으면 false. */
    private static boolean splitGroups(String part, List<String> out) {
        if (part.isEmpty()) {
            return true;
        }
        for (String group : part.split(":", -1)) {
            if (group.isEmpty()) {
                return false;
            }
            out.add(group);
        }
        return true;
    }

    /**
     * ASCII 16진 1~4자.
     *
     * <p>{@code Character.digit} 을 쓰지 않는다 — 전각 숫자·전각 영문·다른 문자 체계의 숫자까지 16진으로
     * 받아 {@code ::１} 같은 값이 리터럴로 통과하고, 요청 경로에서는 그 문자열이 클라이언트 IP 로 채택된다.
     */
    private static boolean isHexGroup(String group) {
        if (group.isEmpty() || group.length() > 4) {
            return false;
        }
        for (int i = 0; i < group.length(); i++) {
            char c = group.charAt(i);
            boolean asciiHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!asciiHex) {
                return false;
            }
        }
        return true;
    }
}
