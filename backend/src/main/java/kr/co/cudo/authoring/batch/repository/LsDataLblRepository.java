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
