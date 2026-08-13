package kr.co.cudo.authoring.batch.repository;

import java.math.BigDecimal;

/**
 * 오토라벨 결과 화면(FE FrameLabels)용 라벨 + AI 메타 투영.
 *
 * <p>auto/manual 구분과 신뢰도는 {@code LS_DATA_LBL} <b>본체 컬럼</b>이다(V6 흡수). 조회는 단일
 * 테이블 {@code CASE WHEN} 이며 조인이 없다.
 *
 * <ul>
 *   <li>{@code autoLblYn} — {@code AUTO_LBL_YN='Y'} 면 'Y', 그 외({@code 'N'}·{@code null})는 null
 *       (수동 라벨로 해석).</li>
 *   <li>{@code confScore} — {@code CONF_SCORE}. 자동이 아닌 라벨은 null.</li>
 * </ul>
 *
 * <p><b>V6 이전에는</b> 이 두 값이 별도 테이블 {@code LS_DATA_LBL_AI_INFO} 에 있었고
 * ({@code LsDataLbl} 의 두 필드가 {@code @Transient} 라 본체만 읽으면 항상 null 이었다) 그래서
 * 라벨당 최신 1행을 고르는 LATERAL 조인이 필요했다. 그 <b>계약</b>(자동이 아니면 두 값을 null 로
 * 내보낸다)은 흡수 후에도 그대로 유지된다 — 소비자 매핑이 바뀌지 않게 하려는 의도다.
 */
public interface AutoLabelInfoProjection {

    Long getLblSn();

    String getLabelNm();

    String getAutoLblYn();

    BigDecimal getConfScore();
}
