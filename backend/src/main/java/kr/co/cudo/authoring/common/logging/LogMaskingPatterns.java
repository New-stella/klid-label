package kr.co.cudo.authoring.common.logging;

import java.util.regex.Pattern;

/**
 * 로그 마스킹 규칙 <b>단일 원천</b> — {@link MaskingPatternLayout}(local, PatternLayout)과
 * {@link MaskingJsonValueMasker}(dev/stg/prd, JSON 인코더)가 공유한다.
 *
 * <p>A-ISSUE-62 (HIGH, CWE-532 / CWE-359 / OWASP A09:2025) — 두 마스커가 각자 정규식 상수를 복제 보유해
 * 있었고, 그 규칙이 {@code key=value} 와 {@code Authorization:} 두 형태뿐이라 아래가 전부 평문으로
 * 기록됐다(실동작 확인): JSON 형태 자격증명, 접두어 없는 JWT 전문, 전화번호, 주민번호형, 이메일,
 * {@code X-Access-Token} 등 Authorization 이외 토큰 헤더. 규칙을 이 클래스 하나로 모아 두 구현의
 * 드리프트를 없앤다.
 *
 * <h2>적용 순서(중요)</h2>
 * <ol>
 *   <li><b>JSON 자격증명</b> — {@code "password":"x"}. JSON 은 키와 콜론 사이에 {@code "} 가 끼어
 *       헤더/KV 패턴이 잡지 못하므로 먼저 처리한다.</li>
 *   <li><b>토큰 헤더</b> — {@code Authorization: …} / {@code X-Access-Token: …}</li>
 *   <li><b>쿠키 헤더</b> — {@code Cookie: …} / {@code Set-Cookie: …} (값이 {@code ;} 로 구분된 여러
 *       쌍이라 줄 끝까지 가린다)</li>
 *   <li><b>key=value</b> — {@code password=…} / {@code token=…}</li>
 *   <li><b>Bearer 값</b> → <b>bare JWT</b> — 위에서 이미 {@code ***} 로 치환된 자리는 매칭되지 않는다.</li>
 *   <li><b>PII</b> — 주민번호형 → 전화번호 → 이메일. 주민번호형을 전화번호보다 먼저 처리해 13자리
 *       숫자열이 전화번호 규칙에 부분 매칭되는 것을 막는다.</li>
 * </ol>
 *
 * <p><b>비용</b>: 로그 출력은 hot path 이므로 모든 {@link Pattern} 을 컴파일 상수로 두고, 빈 문자열은
 * 조기 반환한다. 마스킹 결과에 원본 조각이 남지 않도록 치환값은 고정 문자열만 사용한다.
 *
 * <h2>ReDoS 방어(CWE-1333 / CWE-400)</h2>
 * <p>키워드 <b>앞</b>에 {@code [A-Za-z0-9_.\-]*} 를 두면 영숫자 연속 런의 <b>모든 시작 위치</b>에서
 * 런 끝까지 전진했다 되돌아오는 2차 백트래킹이 발생한다(실측: 8,000자 입력 849ms / 16,000자 3,433ms).
 * 이 마스커는 인코더 레벨에 배선돼 <b>스택트레이스를 포함한 모든 문자열 값</b>에 적용되고 어펜더가
 * 동기 방식이라, 그 지연이 로깅 스레드를 그대로 블록한다. 세 겹으로 막는다.
 * <ol>
 *   <li><b>경계 lookbehind</b>({@code (?<![A-Za-z0-9_.\-])}) — 키 후보 매칭 시도를 런의 첫 글자
 *       한 곳으로 제한해 시작 위치 수를 O(n) → O(런 개수) 로 줄인다. 뒤쪽 반복은
 *       <b>possessive</b>({@code *+}) 로 바꿔 되돌아오지 않게 한다(뒤따르는 문자가 해당 문자
 *       클래스에 속하지 않아 의미 변화 없음).</li>
 *   <li><b>반복 상한</b> — lookbehind 만으로는 부족하다. 런 <b>하나</b> 안에 키워드가 반복 포함되면
 *       (예: {@code "token".repeat(n/5)}) 그 단일 시작 위치에서 앞쪽 {@code *} 가 키워드를 만날
 *       때마다 되돌아가고, 매번 뒤쪽 반복이 런 끝까지 다시 스캔해 2차 비용이 그대로 남는다
 *       (실측: 8K 33ms / 16K 133ms / 32K 535ms / 65K 2,265ms — 입력 2배마다 4배). 키 이름을
 *       구성하는 앞뒤 반복에 {@code {0,64}} 상한을 둬 <b>시작 위치당 비용을 상수</b>로 묶는다
 *       ({@link #KEY_FRAGMENT_MAX} — 실제 자격증명 키 이름은 훨씬 짧다).</li>
 *   <li><b>길이 상한</b>({@link #MAX_MASK_LENGTH}) — 정규식 재작성이 놓친 경로를 대비한 최종
 *       방어선. 상한 초과분은 마스킹하지 않고 <b>버린다</b>(fail-secure — 미마스킹 원문을 흘리지
 *       않는다).</li>
 * </ol>
 *
 * <h2>스택 고갈 방어(CWE-674)</h2>
 * <p>Java 정규식은 <b>그룹</b>에 걸린 무제한 반복({@code (?:…)+})을 재귀 {@code Loop} 노드로
 * 컴파일해 <b>반복 횟수만큼 콜스택이 쌓인다</b>. 이메일 도메인부를 라벨 단위
 * ({@code (?:[A-Za-z0-9\-]++\.)+}) 로 쪼갠 결과, {@code "a@" + "b.".repeat(4000)} (겨우 8KB —
 * {@link #MAX_MASK_LENGTH} 의 1/8) 입력에서 {@code StackOverflowError} 가 났다(실측 재현).
 * 이 {@link Error} 는 Logback 의 {@code catch (Exception)} 에 걸리지 않아 <b>로그를 호출한 스레드가
 * 그대로 죽는다</b>(요청 중단 + 로그 유실). 그룹 반복에는 반드시 <b>명시적 상한</b>을 둔다
 * ({@link #EMAIL_DOMAIN_LABEL_MAX}) — 상한이 있으면 재귀 깊이가 상수로 묶인다.
 *
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class LogMaskingPatterns {

    /** 마스킹 치환 표기. */
    private static final String MASK = "***";

    /**
     * 정규식을 적용할 입력 길이 상한(64KB). 초과분은 마스킹 없이 폐기하고 절삭 표기를 남긴다.
     *
     * <p>정규식이 모두 선형이라 이 크기에서도 수 ms 내에 끝나지만, 미래 규칙 추가가 다시 2차
     * 복잡도를 들여올 경우를 대비한 상한이다. 초과분을 <b>남기지 않는</b> 이유는, 남기면 마스킹되지
     * 않은 자격증명·PII 가 그대로 기록되기 때문이다(CWE-532).
     */
    static final int MAX_MASK_LENGTH = 64 * 1024;

    /**
     * 절삭 지점을 되감을 최대 거리. 절삭이 토큰 한가운데를 자르면 잘린 앞부분이 미마스킹 상태로
     * 남으므로({@code {"password":"SE…}}) 이 창 안에서 안전한 경계를 찾아 되감는다. 창을 두는 이유는
     * 되감기로 버려지는 진단 정보를 상수로 묶기 위해서다.
     */
    private static final int TRUNCATE_REWIND_WINDOW = 1024;

    /**
     * 키 이름에서 키워드 앞뒤에 붙을 수 있는 조각의 최대 길이(ReDoS 상한).
     * {@code db.password} · {@code X_API_KEY_VALUE} 같은 실제 키는 이보다 훨씬 짧다.
     */
    private static final int KEY_FRAGMENT_MAX = 64;

    /**
     * 이메일 도메인 라벨 개수 상한(스택 고갈 방어). {@code mail.sub.example.co.kr} 이 4 개이므로
     * 실무 도메인은 이 상한에 닿지 않는다.
     */
    private static final int EMAIL_DOMAIN_LABEL_MAX = 10;

    /** 키 이름을 구성하는 문자 — 이 문자 앞에서는 키 매칭을 시작하지 않는다(경계 lookbehind). */
    private static final String KEY_BOUNDARY = "(?<![A-Za-z0-9_.\\-])";

    /**
     * 자격증명 계열 키 조각 — 키 이름 어딘가에 포함되면 값 전체를 가린다.
     * (예: {@code accessToken}, {@code X-Access-Token}, {@code db.password}, {@code clientSecret})
     */
    private static final String CREDENTIAL_KEY_FRAGMENT =
            "password|passwd|pwd|token|secret|authorization|credential|apikey|api_key|api-key";

    /**
     * 키워드 <b>앞</b> 조각 — 길이 상한이 핵심이다. 무제한 {@code *} 였을 때는 키워드가 반복 포함된
     * 런에서 되돌아가기 횟수가 입력 길이에 비례해 O(n²) 이 됐다.
     */
    private static final String KEY_PREFIX = "[A-Za-z0-9_.\\-]{0," + KEY_FRAGMENT_MAX + "}";

    /**
     * 키워드 <b>뒤</b> 조각 — 상한 + possessive({@code {0,64}+}). 되돌아갈 이유가 없고
     * (뒤따르는 {@code =} · {@code "} 는 이 문자 클래스에 속하지 않는다) 되돌아가지 않아야 한다.
     */
    private static final String KEY_SUFFIX = "[A-Za-z0-9_.\\-]{0," + KEY_FRAGMENT_MAX + "}+";

    /**
     * JSON 형태 자격증명 — {@code "password" : "hunter2"} (이스케이프된 따옴표 포함 값 지원).
     *
     * <p>여는 {@code "} 가 시작 위치를 제한하지만 {@code "} 가 다수 실린 입력에서는 따옴표마다 키
     * 후보 런을 훑어 2차 비용이 난다. 키 이름 길이를 {@code {0,64}} 로 제한해 따옴표당 비용을
     * 상수로 묶는다(실제 자격증명 키 이름은 훨씬 짧다).
     */
    private static final Pattern JSON_CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)\"(" + KEY_PREFIX + "(?:" + CREDENTIAL_KEY_FRAGMENT + ")" + KEY_SUFFIX + ")"
                    + "\"\\s*+:\\s*+"
                    + "\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+\""
    );

    /**
     * 토큰 헤더 — {@code Authorization: Bearer xxx} / {@code X-Access-Token: xxx}.
     * {@code X-Access-Token} 은 관제 계약 헤더명이라 Authorization 과 동급으로 취급한다.
     */
    private static final Pattern HEADER_COLON_PATTERN = Pattern.compile(
            "(?i)(authorization|proxy-authorization|x-access-token|x-auth-token|x-api-key)"
                    + "\\s*+:\\s*+(?:Bearer\\s++)?\\S++"
    );

    /**
     * 쿠키 헤더 — {@code Cookie: JSESSIONID=…; refresh=…} / {@code Set-Cookie: …}.
     *
     * <p>세션 토큰이 실리는데도 마스킹 키워드 목록에 없어 평문 기록됐다(CWE-532). 쿠키는 값이
     * {@code ;} 로 구분된 <b>여러 쌍</b>이라 {@code \S+} 로는 첫 쌍만 가려지므로 <b>줄 끝까지</b>
     * 가린다. 줄 경계를 넘지 않게 공백은 {@code [ \t]} 로 한정한다.
     */
    private static final Pattern COOKIE_HEADER_PATTERN = Pattern.compile(
            "(?i)(set-cookie|cookie)[ \\t]*+:[ \\t]*+[^\\r\\n]*+"
    );

    /**
     * {@code key=value} 형태 자격증명.
     *
     * <p>{@code db.password} · {@code accessToken} 처럼 키워드 앞뒤에 조각이 붙는 형태를 계속
     * 지원해야 하므로 앞쪽 조각 자체는 유지하되, 두 가지로 비용을 묶는다.
     * <ul>
     *   <li>경계 lookbehind — 매칭 <b>시작 위치</b>를 런의 첫 글자로 제한한다.</li>
     *   <li>{@link #KEY_PREFIX} / {@link #KEY_SUFFIX} 의 {@code {0,64}} 상한 — <b>시작 위치당
     *       비용</b>을 상수로 묶는다. lookbehind 만으로는 키워드가 반복 포함된 단일 런
     *       ({@code "token".repeat(n/5)}) 에서 2차 비용이 남았다.</li>
     * </ul>
     */
    private static final Pattern KV_PATTERN = Pattern.compile(
            "(?i)" + KEY_BOUNDARY
                    + "(" + KEY_PREFIX + "(?:" + CREDENTIAL_KEY_FRAGMENT + ")" + KEY_SUFFIX + ")"
                    + "\\s*+=\\s*+\\S++"
    );

    /** {@code Bearer <값>} — 키 이름 없이 토큰만 실린 경우. */
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(Bearer)\\s++[A-Za-z0-9._~+/=\\-]{8,}+"
    );

    /**
     * 접두어 없는 JWT 전문 — {@code eyJ…​.…​.…} 3 세그먼트.
     * 서명부는 없을 수도 있으므로(alg=none 위조 시도 로깅 등) 마지막 세그먼트는 길이 0 을 허용한다.
     *
     * <p><b>경계 lookbehind 필수</b>(CWE-1333 / CWE-400) — 다른 규칙과 달리 이 패턴만 경계가 없었다.
     * {@code eyJ} 는 JWT 문자 클래스 안에 있는 조각이라 <b>런 한가운데에서도 매칭이 시작</b>되고,
     * 뒤의 {@code {4,}+} 가 런 끝까지 스캔했다 {@code \.} 를 못 찾아 실패하는 동작이 런 안의
     * {@code eyJ} 출현 횟수(O(n))만큼 반복돼 O(n²) 이 됐다(실측: {@code "eyJ".repeat(n/3)} 입력
     * 8K 72ms / 16K 241ms / 32K 935ms / 64K 3,703ms — 입력 2배마다 4배). 다른 규칙과 같은 방식으로
     * 시작 위치를 런의 첫 글자 한 곳으로 제한하면 선형이 된다(수정 후 64K 1ms).
     */
    private static final Pattern BARE_JWT_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{4,}+\\.[A-Za-z0-9_-]{4,}+\\.[A-Za-z0-9_-]*+"
    );

    /**
     * 주민등록번호형 13자리 — {@code 900101-1234567} / {@code 9001011234567}.
     * 앞뒤 숫자 경계를 둬 더 긴 숫자열의 일부가 잘려 매칭되지 않게 한다.
     */
    private static final Pattern RRN_PATTERN = Pattern.compile(
            "(?<!\\d)\\d{6}-?[1-4]\\d{6}(?!\\d)"
    );

    /**
     * 휴대전화번호 — {@code 010-1234-5678} / {@code 01012345678}.
     * {@code rules/security.md} 의 표기 규약({@code 010-****-1234})대로 가운데 자리만 가린다.
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)(01[016-9])[-. ]?(\\d{3,4})[-. ]?(\\d{4})(?!\\d)"
    );

    /**
     * 이메일 — 로컬파트 첫 글자만 남기고 가린다(도메인은 진단 가치가 있어 보존).
     *
     * <p>로컬파트도 경계 lookbehind + possessive 로 선형화한다. 도메인부는 {@code .} 가 문자
     * 클래스에 포함돼 있어 통째로 possessive 를 걸면 마지막 {@code .tld} 까지 삼켜 매칭이 깨지므로,
     * <b>라벨 단위</b>({@code 문자열++ 다음 마침표})로 쪼개 각 라벨만 possessive 로 둔다.
     *
     * <p><b>라벨 반복에는 반드시 상한({@link #EMAIL_DOMAIN_LABEL_MAX})을 둔다</b> — 무제한
     * {@code +} 면 Java 가 이 그룹 반복을 재귀 {@code Loop} 노드로 컴파일해 라벨 수만큼 콜스택이
     * 쌓이고, {@code "a@" + "b.".repeat(4000)} 같은 8KB 입력에서 {@code StackOverflowError} 로
     * 로깅 스레드가 죽는다(CWE-674, 실측 재현). 상한을 두면 재귀 깊이가 상수로 묶인다.
     *
     * <p>바깥 반복을 possessive 로 두지 <b>않는</b> 이유는 {@code user@example.com.} 처럼 뒤에
     * 마침표가 붙은 형태 때문이다 — 라벨을 하나 양보해야 {@code [A-Za-z]{2,}} 가 TLD 를 잡는다.
     * 상한이 있으니 선택지가 최대 {@value #EMAIL_DOMAIN_LABEL_MAX} 개뿐이라 백트래킹은 상수다.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9._%+\\-])([A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]*+"
                    + "(@(?:[A-Za-z0-9\\-]++\\.){1," + EMAIL_DOMAIN_LABEL_MAX + "}[A-Za-z]{2,}+)"
    );

    private LogMaskingPatterns() {
    }

    /**
     * 로그 문자열에서 자격증명·PII 를 마스킹한다.
     *
     * <p>{@link #MAX_MASK_LENGTH} 를 넘는 입력은 앞부분만 마스킹하고 나머지를 폐기한다 — 미마스킹
     * 원문을 흘리지 않기 위한 fail-secure 절삭이며, 동시에 정규식 처리 비용의 상한이 된다.
     * 절삭 지점은 {@link #safeCutIndex(String)} 로 되감아 자격증명이 <b>반토막</b> 나지 않게 한다.
     *
     * @param input 원본 로그 문자열 (null/빈 문자열은 그대로 반환)
     * @return 마스킹된 문자열
     */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (input.length() > MAX_MASK_LENGTH) {
            int cut = safeCutIndex(input);
            int omitted = input.length() - cut;
            return maskWithin(input.substring(0, cut))
                    + "...[log-mask: " + omitted + " chars truncated]";
        }
        return maskWithin(input);
    }

    /**
     * 절삭 지점을 안전한 경계까지 되감는다.
     *
     * <p>정확히 {@link #MAX_MASK_LENGTH} 에서 자르면 자격증명이 경계에 걸쳤을 때 <b>잘린 앞부분이
     * 미마스킹 상태로 남는다</b>(CWE-532 — 실측 {@code {"password":"SE...[log-mask: … truncated]}).
     * 마스킹 규칙들은 값의 <b>끝</b>(공백 · 닫는 따옴표)을 봐야 매칭되므로, 값이 잘리면 규칙이
     * 매칭에 실패해 조각이 그대로 흐른다. 두 단계로 되감는다.
     * <ol>
     *   <li><b>공백 경계</b> — 마지막 공백까지 되감아 공백으로 구분된 토큰이 쪼개지지 않게 한다.
     *       {@code \S++} 를 값으로 쓰는 규칙(KV · 헤더 · Bearer)은 이것으로 충분하다.</li>
     *   <li><b>따옴표 균형</b> — 공백을 포함할 수 있는 JSON 문자열 값({@code "password":"my pw"})
     *       은 1 단계로 부족하다. 열린 채 끝나는 따옴표가 있으면 그 따옴표 앞까지 더 되감는다.</li>
     * </ol>
     *
     * <p>두 단계 모두 {@link #TRUNCATE_REWIND_WINDOW} 안에서만 되감는다 — 경계를 못 찾았다고
     * 64KB 를 통째로 버리면 진단 정보 손실이 과도하다. 창 밖이면 원래 지점에서 자른다.
     *
     * @return 되감긴 절삭 인덱스 (항상 {@code 0 < index <= MAX_MASK_LENGTH})
     */
    private static int safeCutIndex(String input) {
        int floor = MAX_MASK_LENGTH - TRUNCATE_REWIND_WINDOW;
        int cut = MAX_MASK_LENGTH;

        for (int i = cut - 1; i >= floor; i--) {
            if (Character.isWhitespace(input.charAt(i))) {
                cut = i;
                break;
            }
        }

        int openQuoteAt = unterminatedQuoteIndex(input, cut);
        if (openQuoteAt >= floor) {
            cut = openQuoteAt;
        }
        return cut;
    }

    /**
     * {@code [0, end)} 구간에서 <b>닫히지 않은</b> 따옴표의 위치. 없으면 {@code -1}.
     * 백슬래시 이스케이프({@code \"} · {@code \\})를 건너뛰어 오판을 막는다.
     */
    private static int unterminatedQuoteIndex(String input, int end) {
        int openAt = -1;
        for (int i = 0; i < end; i++) {
            char c = input.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                openAt = (openAt < 0) ? i : -1;
            }
        }
        return openAt;
    }

    /** 길이 상한 안에서의 실제 마스킹 — 클래스 주석의 적용 순서를 그대로 따른다. */
    private static String maskWithin(String input) {
        String result = JSON_CREDENTIAL_PATTERN.matcher(input).replaceAll("\"$1\":\"" + MASK + "\"");
        result = HEADER_COLON_PATTERN.matcher(result).replaceAll("$1: " + MASK);
        result = COOKIE_HEADER_PATTERN.matcher(result).replaceAll("$1: " + MASK);
        result = KV_PATTERN.matcher(result).replaceAll("$1=" + MASK);
        result = BEARER_PATTERN.matcher(result).replaceAll("$1 " + MASK);
        result = BARE_JWT_PATTERN.matcher(result).replaceAll(MASK);
        result = RRN_PATTERN.matcher(result).replaceAll("******-*******");
        result = PHONE_PATTERN.matcher(result).replaceAll("$1-****-$3");
        result = EMAIL_PATTERN.matcher(result).replaceAll("$1" + MASK + "$2");
        return result;
    }
}
