package kr.co.cudo.authoring.common.util;

/**
 * 외부 입력(외부 API 응답, 사용자 입력 등)을 로그로 출력하기 전 정제하는 헬퍼.
 *
 * <p>보안 (CWE-117 Log Injection):
 * <ul>
 *   <li>개행(CR/LF) · 탭 · NULL · 기타 제어문자(C0 {@code 0x00~0x1F} · DEL {@code 0x7F} ·
 *       <b>C1 {@code 0x80~0x9F}</b> — {@code U+0085} NEL 포함) 제거 — 로그 위·변조 차단</li>
 *   <li><b>유니코드 라인 구분자</b> {@code U+2028}(LINE SEPARATOR) · {@code U+2029}(PARAGRAPH SEPARATOR)
 *       제거 — 평문 로그 레이아웃(local)에서 줄바꿈으로 해석돼 가짜 로그 라인을 위조할 수 있다.
 *       dev/stg/prd 는 JSON 인코더라 무해하지만, 정제 결과가 레이아웃에 따라 달라지지 않도록 통일한다.</li>
 *   <li>최대 길이 상한 — 비정상 입력으로 로그 폭주 차단</li>
 *   <li>한글 등 가시 문자는 보존 (위 제어·구분자 외 {@code 0x20} 이상은 그대로 유지)</li>
 * </ul>
 *
 * <p>사용 예:
 * <pre>
 *   log.warn("source={}", LogSanitizer.sanitize(resp.source()));
 * </pre>
 */
public final class LogSanitizer {

    /** 기본 길이 상한 — 외부 응답에서 유래한 비정상 입력으로 인한 로그 폭주 방지. */
    public static final int DEFAULT_MAX_LENGTH = 200;

    private static final String NULL_PLACEHOLDER = "(null)";
    private static final String TRUNCATED_SUFFIX = "...(truncated)";

    private LogSanitizer() {
        // 유틸 클래스 — 인스턴스화 금지
    }

    /**
     * 기본 상한({@value #DEFAULT_MAX_LENGTH}자)으로 로그 출력 안전 문자열 반환.
     *
     * @param input 원본 입력 (null 허용)
     * @return null → {@code "(null)"}, 그 외 제어문자 제거 + 길이 상한 적용된 문자열
     */
    public static String sanitize(String input) {
        return sanitize(input, DEFAULT_MAX_LENGTH);
    }

    /**
     * 지정한 최대 길이로 로그 출력 안전 문자열 반환.
     *
     * @param input     원본 입력 (null 허용)
     * @param maxLength 최대 길이 (1 이상). 초과 시 잘라내고 {@code "...(truncated)"} 접미사 추가
     * @return 정제된 안전 문자열
     */
    public static String sanitize(String input, int maxLength) {
        if (input == null) {
            return NULL_PLACEHOLDER;
        }
        if (maxLength < 1) {
            maxLength = DEFAULT_MAX_LENGTH;
        }

        StringBuilder sb = new StringBuilder(Math.min(input.length(), maxLength));
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            // 제어문자(C0/DEL/C1) + 유니코드 라인 구분자 제거. 그 외는 한글 등 가시 문자이므로 보존.
            if (isLineForgingChar(c)) {
                continue;
            }
            sb.append(c);
            if (sb.length() >= maxLength) {
                sb.append(TRUNCATED_SUFFIX);
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 로그 라인을 위조할 수 있는 문자인가 — 제거 대상 판정 (CWE-117).
     *
     * <p>제거 집합은 정규식 {@code \p{Cc}\p{Zl}\p{Zp}} 와 동치다:
     * <ul>
     *   <li>{@link Character#CONTROL}({@code Cc}) — C0 {@code 0x00~0x1F}, DEL {@code 0x7F},
     *       C1 {@code 0x80~0x9F}({@code U+0085} NEL 포함)</li>
     *   <li>{@link Character#LINE_SEPARATOR}({@code Zl}) — {@code U+2028}</li>
     *   <li>{@link Character#PARAGRAPH_SEPARATOR}({@code Zp}) — {@code U+2029}</li>
     * </ul>
     *
     * <p>일반 공백({@code U+0020})은 {@code Zs}(SPACE_SEPARATOR)라 대상이 아니다 — 문장 가독성을 위해 보존한다.
     */
    private static boolean isLineForgingChar(char c) {
        if (c < 0x20 || c == 0x7F) {
            // 최빈 구간 빠른 경로 (아래 getType 과 결과 동일).
            return true;
        }
        if (c < 0x80) {
            return false;
        }
        int type = Character.getType(c);
        return type == Character.CONTROL
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
