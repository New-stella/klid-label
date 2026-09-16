package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.util.VisibleTextNormalizer;

/**
 * 사용자 마스터의 <b>표시 정보</b>(USER_ID·USER_NM) 정규화 — 저장 직전 마지막 방어선.
 *
 * <p>이 값을 채우는 경로가 둘이 되면서(역할 클레임의 인계값 · 진입 시 자동 등록의 토큰 이름 클레임)
 * 길이 상한과 제어문자 처리가 갈릴 수 있어 한 곳으로 모았다. 상한이 갈리면 한쪽 경로에서만
 * 컬럼 폭을 넘겨 <b>INSERT 시점 DB 오류(500)</b> 가 난다.
 *
 * <ul>
 *   <li><b>보이지 않는 문자 판정은 {@link VisibleTextNormalizer} 에 위임한다</b>: 공백 전용은 null 로
 *       접어 "값을 보내지 않음" 과 같게 취급한다. 빈 문자열이 기존 이름을 지우면 안 된다(upsert 의
 *       COALESCE/NULLIF 와 짝). 제어문자·서식문자는 제거하고(CWE-117 — 이 값은 화면 표시 + JWT
 *       {@code name} 클레임 + 다른 코드의 로그로 흘러간다), 유니코드 공백(Zs)은 단어 사이 구분
 *       의미를 잃지 않도록 지우지 않고 일반 공백으로 바꾼 뒤 앞뒤를 다듬는다.</li>
 *   <li><b>★{@code trim()}·{@code strip()} 으로 되돌리지 말 것</b>: {@code trim()} 은 {@code U+0020}
 *       이하만 털고, {@code strip()} 이 쓰는 {@link Character#isWhitespace} 는 <b>non-breaking 공백
 *       ({@code U+00A0}·{@code U+2007}·{@code U+202F})을 공백으로 보지 않는다</b>. 둘 중 무엇을 써도
 *       NBSP·전각공백만 채운 이름이 "빈 값" 판정을 빠져나가 그대로 저장되고(실측), 화면 쪽 입구는
 *       JS {@code trim()} 이 그것을 털어내므로 <b>두 입구의 판정이 갈린다</b>. 판정 축은 whitespace 가
 *       아니라 <b>문자 카테고리(Zs/Cf/Zl/Zp)</b> 여야 한다.</li>
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

    /** 공백 전용은 null, 보이지 않는 문자는 제거, 상한 초과분은 절단. */
    public static String normalize(String value, int maxLength) {
        String cleaned = VisibleTextNormalizer.normalizeOrNull(value);
        if (cleaned == null) {
            return null;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
