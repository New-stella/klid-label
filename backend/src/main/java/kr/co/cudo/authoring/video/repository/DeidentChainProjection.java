package kr.co.cudo.authoring.video.repository;

/**
 * 비식별 누락 신고 게이트({@code DeidentReportGate})의 <b>조상 체인 1노드</b> 투영.
 *
 * <p>신고 판정은 자기 자신뿐 아니라 {@code ORGNL_RAW_SN} 으로 이어지는 <b>조상(원본) 영상</b>까지
 * 봐야 한다 — 파생영상(해상도/증강) 프레임은 부모의 <b>비식별 프레임을 복사·리스케일</b>한 것이라,
 * 부모의 마스킹이 실패한 그 픽셀이 파생본에도 그대로 남아 있기 때문이다. 신고는 부모 행만
 * {@code 'F'} 로 바꾸므로 자기 행만 보는 판정은 파생본에서 fail-open 이 된다(CWE-359).
 *
 * <p>한 노드에서 판정에 필요한 두 값({@code DE_IDNTF_YN} + {@code ORGNL_RAW_SN})을 <b>단일 조회</b>로
 * 가져온다 — 값마다 따로 조회하면 라벨 조회처럼 빈번한 경로에서 왕복이 배로 늘어난다.
 */
public interface DeidentChainProjection {

    /** {@code LS_DATA_RAW.DE_IDNTF_YN} — {@code 'F'} 면 신고 구간(재비식별 대기). */
    String getDeIdntfYn();

    /** {@code LS_DATA_RAW.ORGNL_RAW_SN} — 원본(부모) 영상 PK. 원본 영상이면 null. */
    Long getOrgnlRawSn();
}
