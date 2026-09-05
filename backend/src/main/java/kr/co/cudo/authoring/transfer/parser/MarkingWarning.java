package kr.co.cudo.authoring.transfer.parser;

/**
 * 검사 중 알려야 하는 사항 한 건 — <b>기계가 분기하는 코드</b>와 <b>사람이 읽는 설명</b>의 짝.
 *
 * <p>코드만 두면 화면이 문구를 각자 지어내 같은 사유가 자리마다 다르게 보이고, 설명만 두면 소비자가
 * 문자열을 비교해 분기하게 되어 문구를 다듬는 순간 그 분기가 깨진다. 그래서 둘을 함께 싣는다
 * (API-216 이 정한 모양이다).
 *
 * @param code    {@link MarkingImportWarningCode} 의 값
 * @param message 사람이 읽는 설명 — 내부 경로·원문을 담지 않는다(CWE-209)
 * @design API-216
 */
public record MarkingWarning(String code, String message) {

    public static MarkingWarning of(String code, String message) {
        return new MarkingWarning(code, message);
    }
}
