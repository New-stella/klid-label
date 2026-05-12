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

    List<LsDataLbl> findBySrcSnAndAutoLblYn(Long srcSn, String autoLblYn);

    long countBySrcSnAndAutoLblYn(Long srcSn, String autoLblYn);

    /**
     * 페이지 단위 batch lookup — 영상(rawSn) 별 라벨 총개수.
     * LS_DATA_LBL.SRC_SN → LS_DATA_SRC.SRC_SN → LS_DATA_SRC.RAW_SN 조인.
     * Object[]: [Long rawSn, Long count]. N+1 방지를 위해 IN 절 batch.
     */
    @Query("""
            SELECT s.rawSn, COUNT(l)
              FROM LsDataLbl l, LsDataSrc s
             WHERE l.srcSn = s.srcSn
               AND s.rawSn IN :rawSns
             GROUP BY s.rawSn
            """)
    List<Object[]> countLabelsByRawSnIn(@Param("rawSns") Collection<Long> rawSns);

    /**
     * 영상(rawSn)에 속한 모든 프레임의 라벨 조회 (auto + manual).
     * LS_DATA_LBL.SRC_SN → LS_DATA_SRC.SRC_SN → LS_DATA_SRC.RAW_SN 조인.
     */
    @Query("""
            SELECT l
              FROM LsDataLbl l, LsDataSrc s
             WHERE l.srcSn = s.srcSn
               AND s.rawSn = :rawSn
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
            "AND l.autoLblYn = 'Y'")
    void deleteByRawSnAutoLbl(@Param("rawSn") Long rawSn);
}
