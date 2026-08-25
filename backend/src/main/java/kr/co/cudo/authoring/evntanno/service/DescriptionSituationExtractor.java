package kr.co.cudo.authoring.evntanno.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 묘사(describe) 전문에서 <b>「상황」 라벨 줄</b>의 값만 뽑는다. [design: CDIAG-014]
 *
 * <p>협력자에 묻지 않고 별도 클래스로 두는 이유는 이 파싱이 <b>외부 문구 형식에 붙는 유일한 지점</b>
 * 이라서다 — 형식이 흔들리면 여기만 고치면 되고, 단위 시험이 그 흔들림을 직접 겨눌 수 있다.
 *
 * <h3>라벨 목록에 의존하지 않는다</h3>
 * <p>규격 콜백 예시는 {@code "- 장소: ...\n- 날씨: ...\n- 상황: ..."} 형태지만 <b>항목의 전체 목록·
 * 순서·필수 여부를 정의하지 않는다</b>("장소·날씨·상황 등"). 그래서 항목 목록을 세우지 않고
 * <b>「상황」 줄 하나만</b> 찾는다 — 사업자가 항목을 늘리거나 순서를 바꿔도 깨지지 않는다.
 *
 * <h3>경계</h3>
 * <ul>
 *   <li>값은 <b>그 줄의 줄바꿈까지</b>다. 다음 라벨 줄로 이어붙이지 않는다.</li>
 *   <li>선행 공백 · {@code -} 뒤 공백 수 · 콜론 앞뒤 공백은 <b>관용 처리</b>한다.</li>
 *   <li>같은 라벨이 여러 번 나오면 <b>첫 번째 줄</b>을 쓴다(결정성). 그 줄의 값이 공백뿐이면
 *       <b>미입력</b>으로 다루고 뒤의 줄을 뒤지지 않는다 — 「첫 번째를 쓴다」와 「공백은 미입력」을
 *       그대로 겹친 결과이며, 뒤를 뒤지면 어느 줄이 뽑힐지가 값의 내용에 따라 달라진다.</li>
 *   <li>찾지 못하면 <b>빈 문자열이 아니라 {@link Optional#empty()}</b> — 호출부가 "채우지 않는다"와
 *       "빈 값으로 채운다"를 구분해야 한다.</li>
 * </ul>
 */
public final class DescriptionSituationExtractor {

    /** 뽑을 항목의 라벨. 이 하나 말고 다른 라벨은 알지 않는다(위 javadoc 참조). */
    static final String SITUATION_LABEL = "상황";

    /**
     * 「상황」 라벨 줄 판정 — 라벨은 <b>줄 머리</b>(선택적 {@code -} 뒤)에 와야 한다.
     *
     * <p>줄 머리를 요구하는 것이 핵심이다. 요구하지 않으면 다른 항목 값 안에 들어 있는
     * {@code "…현재 상황: …"} 같은 문구가 라벨로 잡혀 엉뚱한 값이 실린다.
     */
    private static final Pattern SITUATION_LINE =
            Pattern.compile("^\\s*(?:-\\s*)?" + SITUATION_LABEL + "\\s*:\\s*(.*)$");

    private DescriptionSituationExtractor() {
    }

    /**
     * 묘사 전문에서 「상황」 값을 뽑는다.
     *
     * @param description 묘사 결과 서술 전문. {@code null}·공백 허용.
     * @return 「상황」 값(앞뒤 공백 제거). 라벨이 없거나 값이 공백뿐이면 {@link Optional#empty()}.
     */
    public static Optional<String> extract(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        // \R 로 나눠 \n · \r\n · \r 을 모두 줄 경계로 다룬다(벤더가 어느 개행을 쓰는지 계약에 없다).
        for (String line : description.split("\\R", -1)) {
            Matcher matcher = SITUATION_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String value = matcher.group(1).strip();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        return Optional.empty();
    }
}
