package kr.co.cudo.authoring.user.service;

/**
 * 사용자 마스터의 <b>표시 정보</b>(USER_ID·USER_NM) 정규화 — 저장 직전 마지막 방어선.
 *
 * <p>이 값을 채우는 경로가 둘이 되면서(역할 클레임의 인계값 · 진입 시 자동 등록의 토큰 이름 클레임)
 * 길이 상한과 제어문자 처리가 갈릴 수 있어 한 곳으로 모았다. 상한이 갈리면 한쪽 경로에서만
 * 컬럼 폭을 넘겨 <b>INSERT 시점 DB 오류(500)</b> 가 난다.
 *
 * <ul>
 *   <li><b>공백 전용 → null</b>: "값을 보내지 않음" 과 같게 취급한다. 빈 문자열이 기존 이름을 지우면
 *       안 된다(upsert 의 COALESCE/NULLIF 와 짝).</li>
 *   <li><b>제어문자 제거</b>(CWE-117): 이 값은 화면 표시 + JWT {@code name} 클레임 + 다른 코드의
 *       로그로 흘러간다. 저장 시점에 개행·구분자를 걷어내 로그 라인 위조 소지를 없앤다.</li>
 *   <li><b>길이 상한</b>: 상위 계층의 {@code @Size} 가 먼저 거절하지만, 다른 호출자가 생겨도 컬럼
 *       길이를 넘지 않도록 여기서도 자른다(방어 심층화).</li>
 * </ul>
 */
public final class UserDisplayNames {

    /** {@code LS_ACNT_USER.USER_ID} 컬럼 길이(명V20). 상위 DTO {@code @Size} 와 같은 값이어야 한다. */
    public static final int MAX_USER_ID_LENGTH = 20;

    /** {@code LS_ACNT_USER.USER_NM} 컬럼 길이(명V100). 상위 DTO {@code @Size} 와 같은 값이어야 한다. */
    public static final int MAX_USER_NM_LENGTH = 100;

    private UserDisplayNames() {
    }

    /** 공백 전용은 null, 제어문자는 제거, 상한 초과분은 절단. */
    public static String normalize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
