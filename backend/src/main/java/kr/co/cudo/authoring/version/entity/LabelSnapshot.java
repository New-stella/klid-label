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
 * </ul>
 *
 * <p>불변(record) 값객체이며, Jackson 역직렬화는 canonical constructor 로만 수행한다
 * (보안 CWE-502: 다형 타입 정보 없음 — 명시 타입만).
 */
public record LabelSnapshot(
        String lblTypeCd,
        Long labelId,
        String labelNm,
        String pointCn
) {
}
