package kr.co.cudo.authoring.transfer.parser;

import kr.co.cudo.authoring.common.util.ControlCharNormalizer;

/**
 * 외부 산출물이 준 <b>문자열을 그대로 믿지 않기</b> 위한 정제 단일 지점.
 *
 * <h3>왜 필요한가</h3>
 * <p>산출물의 폴더명·파일명·분류명은 우리가 만든 값이 아니다. 그 값이
 * <ul>
 *   <li>파일 경로로 조립되면 상위 이동 표기({@code ..})·경로 구분자로 저장소 밖을 가리킬 수 있고
 *       (CWE-22),</li>
 *   <li>로그로 나가면 개행·제어문자로 로그 줄을 위조할 수 있으며(CWE-117),</li>
 *   <li>DB 컬럼 폭을 넘으면 INSERT 시점 오류로 이관 전체가 500 이 된다.</li>
 * </ul>
 * 세 방어를 호출부마다 다시 짜면 한 곳이 빠지므로 여기 한 곳에 모은다.
 *
 * <p>정제는 <b>거부가 아니라 축소</b>다 — 산출물 하나가 이상한 이름을 가졌다고 수백 프레임을 통째로
 * 막지 않는다(AC-047 과 같은 취지). 다만 정제 결과가 비면 {@code null} 을 돌려 호출부가
 * fail-closed 로 판단하게 한다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 */
public final class ExternalNameSanitizer {

    /** 식별자로 쓸 때 허용하는 문자 — 이 밖의 문자는 {@code _} 로 바꾼다. */
    private static final String IDENTIFIER_REPLACEMENT = "_";

    private ExternalNameSanitizer() {
    }

    /**
     * 파일명 정제 — <b>마지막 경로 요소만</b> 취하고 제어문자를 걷어낸 뒤 길이를 제한한다.
     *
     * <p>학습데이터 산출물의 영상 파일명이 {@code RAW_FILE_PATH_NM} 의 마지막 이름에서 나오므로
     * <b>산출물이 준 원본 이름을 최대한 보존</b>한다(자리표시자 이름을 쓰면 산출물에 인공 파일명이
     * 실린다). 보존하되 경로가 되지는 않게 만드는 것이 이 메서드의 일이다.
     *
     * @return 정제된 파일명. 정제 후 비었거나 {@code .}/{@code ..} 뿐이면 {@code null}
     */
    public static String fileName(String raw, int maxLength) {
        String normalized = ControlCharNormalizer.normalizeOrNull(raw);
        if (normalized == null) {
            return null;
        }
        // 경로 구분자는 플랫폼에 따라 다르므로 둘 다 자른다. 마지막 요소만 남긴다.
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        String base = slash < 0 ? normalized : normalized.substring(slash + 1);
        base = base.trim();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            return null;
        }
        // 마지막 요소만 남긴 뒤에도 상위 이동 표기가 섞여 있으면(예: "..foo" 는 정상, "../" 는 위에서 제거됨)
        // 남은 것은 파일명 문자이므로 추가로 자르지 않는다. 길이만 제한한다.
        return base.length() <= maxLength ? base : base.substring(0, maxLength);
    }

    /**
     * 식별자 정제 — 경로/식별자 조립에 쓸 수 있도록 <b>허용 문자만</b> 남긴다
     * ({@code A-Z a-z 0-9 _ - .}). 그 밖의 문자는 {@code _} 로 바꾼다.
     *
     * <p>파일명과 달리 원문 보존보다 <b>조립 안전</b>이 우선이다 — 이 값은 디렉터리 이름과
     * {@code VMS_CLIP_ID} 로 들어가고, 그 둘은 사람이 읽는 이름이 아니라 키다.
     *
     * @return 정제된 식별자. 정제 후 비면 {@code null}
     */
    public static String identifier(String raw, int maxLength) {
        String normalized = ControlCharNormalizer.normalizeOrNull(raw);
        if (normalized == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(allowed ? c : IDENTIFIER_REPLACEMENT);
        }
        String cleaned = sb.toString();
        if (cleaned.isBlank() || cleaned.chars().allMatch(c -> c == '_')) {
            return null;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    /**
     * 표시용 텍스트 정제 — 제어문자를 걷어내고 길이를 제한한다. 값 자체는 바꾸지 않는다
     * (분류명·설명처럼 사람이 읽는 값).
     *
     * @return 정제된 텍스트. 비면 {@code null}
     */
    public static String text(String raw, int maxLength) {
        String normalized = ControlCharNormalizer.normalizeOrNull(raw);
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    /**
     * 여러 줄 텍스트 정제 — 제어문자를 걷어내되 <b>줄바꿈({@code \n})은 남긴다</b>.
     *
     * <p>{@link #text(String, int)} 와 나누는 이유: 프레임 설명·이벤트 로그는 사람이 여러 줄로 쓴
     * 내용이 그대로 학습데이터 산출물로 나가는 값이라, 줄바꿈까지 걷어내면 <b>원문이 훼손</b>된다.
     * 반대로 {@code U+0000} 같은 나머지 제어문자는 남겨 두면 PgJDBC 가 파라미터를 거부해 이관
     * 트랜잭션이 통째로 깨지므로 반드시 제거한다. 캐리지리턴은 줄바꿈으로 접는다.
     *
     * <p>이 값을 로그로 내보낼 때는 줄바꿈이 살아 있으므로 반드시 {@code LogSanitizer} 를 거친다
     * (CWE-117).
     *
     * @return 정제된 텍스트. 비면 {@code null}
     */
    public static String multilineText(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(unified.length());
        for (int i = 0; i < unified.length(); i++) {
            char c = unified.charAt(i);
            if (c == '\n' || !Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
