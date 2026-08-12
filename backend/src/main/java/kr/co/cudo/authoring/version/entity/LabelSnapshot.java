package kr.co.cudo.authoring.version.entity;

/**
 * 라벨 1건의 diff 대상 스냅샷 값객체 — 저장 이벤트 이력(LS_DATA_LBL_HSTRY.CHG_DTL_CN)의 before/after 표현.
 *
 * <p>라벨 본문(LS_DATA_LBL)의 변경 가능 핵심 필드만 담는다:
 * <ul>
 *   <li>{@code lblTypeCd} : BBOX/POLYGON/SEGMENT/TRACK/SKELETON</li>
 *   <li>{@code labelId}   : LS_LABEL 마스터 FK (nullable)</li>
 *   <li>{@code labelNm}   : 라벨명</li>
 *   <li>{@code pointCn}   : 좌표 직렬화 문자열</li>
 *   <li>{@code trackId}   : 추적 식별자 (nullable)</li>
 * </ul>
 *
 * <h3>{@code trackId} 를 담는 이유 (F-01)</h3>
 * 이 값객체는 <b>변경 감지의 축</b>이기도 하다({@code LabelService.snapshotsEqual}). 트랙 재지정은
 * 관리 엔티티의 dirty checking 으로 커밋되는데, 담지 않으면 좌표·라벨명이 그대로일 때 "변경 없음"으로
 * 판정되어 <b>이력·판번호 증가·수정 통지가 한꺼번에 빠진다</b>:
 * <ol>
 *   <li>누가 트랙을 옮겼는지 기록이 남지 않는다(CWE-778)</li>
 *   <li>{@code LBL_VER} 가 오르지 않아 다른 세션의 낡은 판번호가 무효화되지 않고, 그 세션의 전량 교체
 *       저장이 409 없이 통과한다(CWE-362)</li>
 *   <li>승인 영상에서 산출물 재생성·관제 통지가 생략된다</li>
 * </ol>
 *
 * <p>불변(record) 값객체이며, Jackson 역직렬화는 canonical constructor 로만 수행한다
 * (보안 CWE-502: 다형 타입 정보 없음 — 명시 타입만).
 */
public record LabelSnapshot(
        String lblTypeCd,
        Long labelId,
        String labelNm,
        String pointCn,
        String trackId
) {

    /**
     * 하위호환 — {@code trackId} 축 도입 <b>이전</b> 호출자용(트랙을 건드리지 않는 경로).
     *
     * <p>이미 적재된 이력 행({@code CHG_DTL_CN})에는 이 필드가 없으며, 역직렬화 시 {@code null} 이 되어
     * 그대로 읽힌다(값이 없다 = 그 시점에 이 축을 담지 않았다).
     */
    public LabelSnapshot(String lblTypeCd, Long labelId, String labelNm, String pointCn) {
        this(lblTypeCd, labelId, labelNm, pointCn, null);
    }
}
