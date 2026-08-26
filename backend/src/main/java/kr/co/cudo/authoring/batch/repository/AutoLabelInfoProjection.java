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
 *   <li>{@code labelId} — {@code LBL_ID}(라벨 마스터 {@code LS_LABEL} FK). <b>null 허용</b>이며
 *       null 은 마스터 미연결을 뜻한다. 소비자가 표시명·표시색을 마스터에서 조달할 때 쓰는 키다 —
 *       이 값이 없으면 마스터를 배치 조회할 수 없어 라벨명을 저장 원문으로만 내보내게 된다.</li>
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

    Long getLabelId();

    String getAutoLblYn();

    BigDecimal getConfScore();
}
