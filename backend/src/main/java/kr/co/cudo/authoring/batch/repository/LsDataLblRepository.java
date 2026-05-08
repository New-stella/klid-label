package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
