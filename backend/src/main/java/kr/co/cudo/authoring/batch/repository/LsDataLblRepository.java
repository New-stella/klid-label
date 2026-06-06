package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@ControlRepo
public interface LsDataLblRepository extends JpaRepository<LsDataLbl, Long> {

    List<LsDataLbl> findBySrcSn(Long srcSn);

    /**
     * 프레임(srcSn) 집합에 속한 모든 라벨을 단일 IN 쿼리로 일괄 조회.
     * <p>SCR-REVIEW-002 검수 화면 일괄 조회용 — N+1 회피.
     * 빈 컬렉션 입력 시 빈 결과 반환 (default).
     */
    List<LsDataLbl> findBySrcSnIn(Collection<Long> srcSns);

    @Query("""
            SELECT l
              FROM LsDataLbl l
             WHERE l.srcSn = :srcSn
               AND (
                    (:autoLblYn = 'Y' AND EXISTS (
                        SELECT 1 FROM LsDataLblAiInfo ai
                         WHERE ai.dataLblSn = l.lblSn
                           AND ai.autoLblYn = 'Y'
                    ))
                    OR
                    (:autoLblYn = 'N' AND NOT EXISTS (
                        SELECT 1 FROM LsDataLblAiInfo ai
                         WHERE ai.dataLblSn = l.lblSn
                           AND ai.autoLblYn = 'Y'
                    ))
               )
            """)
    List<LsDataLbl> findBySrcSnAndAutoLblYn(@Param("srcSn") Long srcSn, @Param("autoLblYn") String autoLblYn);

    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
             WHERE l.srcSn = :srcSn
               AND (
                    (:autoLblYn = 'Y' AND EXISTS (
                        SELECT 1 FROM LsDataLblAiInfo ai
                         WHERE ai.dataLblSn = l.lblSn
                           AND ai.autoLblYn = 'Y'
                    ))
                    OR
                    (:autoLblYn = 'N' AND NOT EXISTS (
                        SELECT 1 FROM LsDataLblAiInfo ai
                         WHERE ai.dataLblSn = l.lblSn
                           AND ai.autoLblYn = 'Y'
                    ))
               )
            """)
    long countBySrcSnAndAutoLblYn(@Param("srcSn") Long srcSn, @Param("autoLblYn") String autoLblYn);

    /**
     * 페이지 단위 batch lookup — 영상(rawSn) 별 라벨 총개수.
     * LS_DATA_LBL.SRC_SN → LS_DATA_SRC.SRC_SN → LS_DATA_SRC.RAW_SN 조인.
     * Object[]: [Long rawSn, Long count]. N+1 방지를 위해 IN 절 batch.
     */
    @Query("""
            SELECT s.rawSn, COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn IN :rawSns
             GROUP BY s.rawSn
            """)
    List<Object[]> countLabelsByRawSnIn(@Param("rawSns") Collection<Long> rawSns);

    /**
     * 영상(rawSn)에 속한 모든 프레임의 라벨 조회 (auto + manual).
     * LS_DATA_LBL.SRC_SN → LS_DATA_SRC.SRC_SN → LS_DATA_SRC.RAW_SN 조인.
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
             ORDER BY l.lblSn ASC
            """)
    List<LsDataLbl> findAllByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn)에 속한 모든 프레임의 자동 라벨(autoLblYn='Y')을 일괄 삭제.
     * 오토라벨링 테스트 재실행 시 idempotent 보장 용도.
     */
    @Modifying
    @Transactional(value = "controlTransactionManager")
    @Query("DELETE FROM LsDataLbl l WHERE l.srcSn IN " +
            "(SELECT s.srcSn FROM LsDataSrc s WHERE s.rawSn = :rawSn) " +
            "AND EXISTS (SELECT 1 FROM LsDataLblAiInfo ai WHERE ai.dataLblSn = l.lblSn AND ai.autoLblYn = 'Y')")
    void deleteByRawSnAutoLbl(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn)에 속한 모든 프레임의 라벨(자동+수동 전체)을 일괄 삭제 (R1 v1.14 — 비식별 신고 시).
     * <p>1건씩 삭제 금지(수천 건 가능) — 단일 DELETE…WHERE SRC_SN IN(서브쿼리) 로 처리.
     * 호출 전 ATTR_VAL/AI_INFO 자식 row 를 먼저 삭제해 FK 고아를 방지한다.
     */
    @Modifying
    @Transactional(value = "controlTransactionManager")
    @Query("DELETE FROM LsDataLbl l WHERE l.srcSn IN " +
            "(SELECT s.srcSn FROM LsDataSrc s WHERE s.rawSn = :rawSn)")
    void deleteAllByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 총 라벨 개수 — 단일 COUNT 쿼리 (N+1 금지).
     * LS_DATA_LBL.SRC_SN → LS_DATA_SRC.SRC_SN → LS_DATA_SRC.RAW_SN 조인.
     */
    @Query("""
            SELECT COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
            """)
    long countByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 클래스(라벨명)별 라벨 분포 — 단일 GROUP BY 쿼리 (N+1 금지).
     * <p>결과 Object[]: [Long labelId, String labelNm, Long count]. labelId 는 null 가능
     * (V32 이전 라벨) — caller 가 0L 폴백 처리. count DESC 정렬.
     */
    @Query("""
            SELECT l.labelId, l.labelNm, COUNT(l)
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
             GROUP BY l.labelId, l.labelNm
             ORDER BY COUNT(l) DESC
            """)
    List<Object[]> aggregateClassDistribution(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 신뢰도 보유 라벨에 대한 [합계, 개수] — 평균 계산용 (단일 쿼리).
     *
     * <p>신뢰도는 {@code LS_DATA_LBL_AI_INFO.CONF_SCORE} 에 저장된다(LsDataLbl.confScore 는 @Transient).
     * AiInfo 의 {@code DATA_RAW_SN} 으로 직접 필터링한다. 결과 Object[]: [BigDecimal sum, Long cnt].
     * 신뢰도 보유 라벨이 0건이면 sum=null, cnt=0.
     */
    @Query("""
            SELECT SUM(ai.confScore), COUNT(ai)
              FROM LsDataLblAiInfo ai
             WHERE ai.dataRawSn = :rawSn
               AND ai.confScore IS NOT NULL
            """)
    Object[] aggregateConfidenceSum(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 신뢰도 구간별 라벨 개수 — 단일 GROUP BY 쿼리 (N+1 금지).
     * <p>구간: high(&gt;=0.9) / mid(0.7~0.9) / low(&lt;0.7). LS_DATA_LBL_AI_INFO 기준.
     * 결과 Object[]: [String bucket, Long count].
     */
    @Query("""
            SELECT CASE
                       WHEN ai.confScore >= 0.9 THEN 'high'
                       WHEN ai.confScore >= 0.7 THEN 'mid'
                       ELSE 'low'
                   END,
                   COUNT(ai)
              FROM LsDataLblAiInfo ai
             WHERE ai.dataRawSn = :rawSn
               AND ai.confScore IS NOT NULL
             GROUP BY CASE
                       WHEN ai.confScore >= 0.9 THEN 'high'
                       WHEN ai.confScore >= 0.7 THEN 'mid'
                       ELSE 'low'
                   END
            """)
    List<Object[]> aggregateConfidenceBuckets(@Param("rawSn") Long rawSn);

    /**
     * 영상(rawSn) 의 저신뢰(임계 미만) 라벨을 프레임 정보와 함께 조회 — 단일 조인 쿼리.
     *
     * <p>신뢰도는 LS_DATA_LBL_AI_INFO 기준. 프레임 정보(frameNo)는 LS_DATA_SRC 조인으로 획득.
     * 결과 Object[]: [Long srcSn, Integer frameNo, BigDecimal confScore]. confScore ASC(낮은 순).
     * 동일 프레임에 여러 저신뢰 라벨이 있으면 각각 반환되므로 caller 가 프레임 단위로 중복 제거한다.
     */
    @Query("""
            SELECT s.srcSn, s.frameNo, ai.confScore
              FROM LsDataLblAiInfo ai
              JOIN LsDataSrc s ON ai.dataSrcSn = s.srcSn
             WHERE ai.dataRawSn = :rawSn
               AND ai.confScore IS NOT NULL
               AND ai.confScore < :threshold
             ORDER BY ai.confScore ASC
            """)
    List<Object[]> findLowConfidenceFrames(@Param("rawSn") Long rawSn,
                                           @Param("threshold") java.math.BigDecimal threshold);

    /**
     * 영상(rawSn)에 속한 자동 BBOX 라벨 중 trackId 가 있는 row 만 조회. — Phase 3 트랙 보간.
     * <p>보간 대상 정의:
     * <ul>
     *   <li>{@code AUTO_LBL_YN='Y'} — 자동 라벨링 결과만</li>
     *   <li>{@code LBL_TYPE_CD='BBOX'} — POLYGON/SEGMENT 는 보간 범위 외</li>
     *   <li>{@code TRACK_ID IS NOT NULL} — 트래커 저신뢰 detection 은 보간 대상 외</li>
     * </ul>
     * <p>같은 영상의 모든 트랙을 단일 IN 쿼리로 가져와 N+1 회피.
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l
              JOIN LsDataSrc s ON l.srcSn = s.srcSn
             WHERE s.rawSn = :rawSn
               AND EXISTS (
                   SELECT 1 FROM LsDataLblAiInfo ai
                    WHERE ai.dataLblSn = l.lblSn
                      AND ai.autoLblYn = 'Y'
               )
               AND l.lblTypeCd = 'BBOX'
               AND l.trackId IS NOT NULL
            """)
    List<LsDataLbl> findAutoBboxWithTrackId(@Param("rawSn") Long rawSn);
}
