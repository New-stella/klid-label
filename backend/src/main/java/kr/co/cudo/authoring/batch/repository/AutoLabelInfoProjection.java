package kr.co.cudo.authoring.batch.repository;

import java.math.BigDecimal;

/**
 * 오토라벨 결과 화면(FE FrameLabels)용 라벨 + AI 메타 투영.
 *
 * <p>auto/manual 구분과 신뢰도는 {@code LS_DATA_LBL} 본체가 아닌 {@code LS_DATA_LBL_AI_INFO}
 * 에 저장된다({@link kr.co.cudo.authoring.batch.entity.LsDataLbl} 의 autoLblYn/confScore 는
 * {@code @Transient} 라 DB 조회 시 항상 null). 따라서 두 테이블을 단일 LEFT JOIN 쿼리로 함께
 * 조회해 N+1 없이 정확한 값을 가져오기 위한 투영이다.
 *
 * <ul>
 *   <li>{@code autoLblYn} — AI_INFO row 가 있고 {@code AUTO_LBL_YN='Y'} 면 'Y', 없으면 null
 *       (수동 라벨로 해석).</li>
 *   <li>{@code confScore} — AI_INFO 의 {@code CONF_SCORE}. 수동/미적재 라벨은 null.</li>
 * </ul>
 */
public interface AutoLabelInfoProjection {

    Long getLblSn();

    String getLabelNm();

    String getAutoLblYn();

    BigDecimal getConfScore();
}
