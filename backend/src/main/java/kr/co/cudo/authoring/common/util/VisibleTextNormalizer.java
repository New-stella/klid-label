package kr.co.cudo.authoring.common.util;

/**
 * "보이지 않는 문자" 를 걷어내 <b>사람이 실제로 입력한 내용이 있는지</b> 판정할 수 있게 만드는 정규화.
 *
 * <h2>왜 {@link ControlCharNormalizer} 로 통합하지 않는가 (중요)</h2>
 * <p>{@link ControlCharNormalizer} 는 <b>Java 규칙과 SQL 표현식({@code translate(...)}) 이 같은 결과를
 * 내야 한다</b>는 제약 위에 서 있다. 셀렉트 옵션(코드값 목록)을 SQL 로 정규화해 내려주고 그 값을 필터로
 * 되돌려 받기 때문에, 한쪽만 제거 집합을 넓히면 <b>옵션에서 고른 값이 0건이 되는</b> 왕복 파손이 난다
 * (그 클래스 주석의 실제 사고). 여기서 필요한 확장(FORMAT·행/문단 구분·유니코드 공백)은 SQL 쪽에 같은
 * 규칙을 만들 수단이 마땅치 않으므로, 공용 유틸을 넓히는 대신 <b>이 규칙이 필요한 곳 전용</b>으로 분리한다.
 * 현재 사용처는 <b>사람이 자유롭게 입력하는 값</b> 넷이다 — 외부 위탁 증강 프롬프트 5필드
 * ({@code AugmentRequestService}) · 증강 취소 사유({@code AugmentCancelService}) · 포털 증강 요청
 * ({@code PortalAugmentService}) · 사용자 표시 이름({@code UserDisplayNames}). 목록 필터·이벤트
 * 유형 코드 등 {@link ControlCharNormalizer} 사용처의 동작은 <b>일절 변하지 않는다</b>.
 *
 * <p>⚠ <b>구 서술 폐기(2026-09-16)</b> — <i>"사용처는 <b>외부로 중계되는</b> 자유 입력 둘"</i>. 그 분류 축은
 * 더 이상 사용처를 담지 못한다. <b>사용자 표시 이름은 외부로 나가지 않고 내부 화면에만 보이는데</b>
 * 같은 판정이 필요하다 — 보이지 않는 문자만으로 된 이름이 통과하면 목록·배정·검수 화면의 이름 칸이
 * 빈 채로 그려진다. ⇒ 이 판정기가 걸리는 조건은 <b>「외부로 나가는가」가 아니라 「사람이 자유롭게 쓴 값인가」</b>다.
 * 그 축으로 읽지 않으면 새 사용처를 만날 때마다 "우리는 외부로 안 보내니 해당 없다" 로 잘못 비껴간다.
 *
 * <h2>규칙</h2>
 * <ol>
 *   <li><b>제거</b> — {@link Character#isISOControl}(NUL·개행·탭 등), 카테고리 {@code Cf}(FORMAT:
 *       {@code U+200B} ZWSP · {@code U+FEFF} BOM · {@code U+2060} WJ · {@code U+202E} RLO ·
 *       {@code U+200F} RLM 등), {@code Zl}(LINE_SEPARATOR {@code U+2028}), {@code Zp}
 *       (PARAGRAPH_SEPARATOR {@code U+2029}).</li>
 *   <li><b>일반 공백으로 치환</b> — 카테고리 {@code Zs}({@code U+00A0} NBSP · {@code U+3000} 등).
 *       지우지 않고 공백으로 바꾸는 이유는 단어 사이의 NBSP 가 구분 의미를 갖기 때문이다
 *       ({@code "폭우 　경보"} → {@code "폭우 경보"}).</li>
 *   <li>그 뒤 앞뒤 공백 제거. 남는 게 없으면 {@code null}.</li>
 * </ol>
 *
 * <h2>왜 이 규칙이 필요한가 (CWE-20 / CWE-117 / 표시 위조)</h2>
 * <ul>
 *   <li>{@code @NotBlank} 는 {@link String#trim()}({@code U+0020} 이하만 제거) 기준이고
 *       {@link Character#isWhitespace} 는 <b>NBSP 를 공백으로 보지 않는다</b>. 그래서 NBSP·ZWSP·BOM 만
 *       채운 필드가 "입력됨" 으로 통과해 <b>빈 조건</b>이 벤더로 나간다 — 벤더가 임의 기본값으로 채우면
 *       결과가 비결정적이 되어 프롬프트 계약 자체가 무의미해진다.</li>
 *   <li>{@code U+2028}/{@code U+2029} 는 개행 유사 문자라 로그·JSON 소비자에 따라 줄바꿈으로 해석되어
 *       로그 위조(CWE-117) 표면이 되고, {@code U+202E}(RLO)는 REVIEWER 화면에서 텍스트를 역순으로
 *       보이게 해 조건 표시를 위조한다.</li>
 * </ul>
 *
 * <p>stateless 유틸 — 인스턴스화 금지.
 */
public final class VisibleTextNormalizer {

    private VisibleTextNormalizer() {
    }

    /**
     * 보이지 않는 문자를 제거·치환하고 앞뒤 공백을 다듬는다. 남는 내용이 없으면 {@code null}.
     *
     * <p>코드포인트 단위로 순회하므로 보조 평면(이모지 등) 문자가 쪼개지지 않는다.
     */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp) || isRemovableFormatting(cp)) {
                return;
            }
            if (Character.isSpaceChar(cp)) {
                sanitized.append(' '); // Zs(NBSP·U+3000 …) → 일반 공백
                return;
            }
            sanitized.appendCodePoint(cp);
        });
        String trimmed = sanitized.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 보이지 않으면서 의미도 없는 서식 문자(Cf) + 행/문단 구분자(Zl/Zp). */
    private static boolean isRemovableFormatting(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
