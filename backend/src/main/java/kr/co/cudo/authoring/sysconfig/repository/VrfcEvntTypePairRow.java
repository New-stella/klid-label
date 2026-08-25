package kr.co.cudo.authoring.sysconfig.repository;

/**
 * 관제 이벤트유형 코드 ↔ 검증 이벤트 유형 코드의 <b>수신된 짝</b> 한 건 (인입 원장 투영).
 * [design: API-219]
 *
 * <p>관제는 인입 행 하나에 두 코드를 <b>함께 실어 보낸다</b>. 그 대응은 이미 수신 원장 안에 있으므로
 * 저작도구가 <b>별도 매핑표를 만들지 않는다</b> — 사본을 만들면 두 번째 진실원이 되어 원장과 어긋난다.
 */
public interface VrfcEvntTypePairRow {

    /** 검증 이벤트 유형 코드 — 인입 원문(정규화 전). */
    String getVrfcEvntTypeCd();

    /** 관제 이벤트유형 코드 — 인입 원문. */
    String getEvntTypeCd();
}
