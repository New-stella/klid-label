package kr.co.cudo.authoring.marking.service;

/**
 * 마킹 저장이 <b>응답에 실을 조달값</b> — 채널마다 조달처가 다르다.
 *
 * <p>관제 채널은 영상 원장 행({@code LS_DATA_RAW})에서 이벤트 유형 코드와 영상 파일 경로를 꺼내고,
 * 포털 채널은 <b>둘 다 갖지 않는다</b> — 관제 인입 이벤트 유형이 그 경로로 오지 않고, 저장 경로는
 * 외부 채널 응답에 실을 값이 아니다(CWE-209).
 *
 * @param eventTypeCd 이벤트 유형 코드. 미상이면 {@code null} — 지어내지 않는다
 * @param videoPath   영상 파일 경로. 노출하지 않는 채널이면 {@code null}
 * @design ADR-058
 */
public record MarkingTarget(String eventTypeCd, String videoPath) {

    /** 조달값이 없는 채널(포털)용 — 두 칸을 비운다. */
    public static final MarkingTarget EMPTY = new MarkingTarget(null, null);
}
