package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataSrcRepository extends JpaRepository<LsDataSrc, Long> {

    List<LsDataSrc> findByRawSnOrderByFrameNoAsc(Long rawSn);

    /** Phase 4 — 외부 학습데이터 API: 특정 frmTypeCd(RAW/DEID) 만 정렬 조회. */
    List<LsDataSrc> findByRawSnAndFrmTypeCdOrderByFrameNoAsc(Long rawSn, String frmTypeCd);

    Optional<LsDataSrc> findByRawSnAndFrameNo(Long rawSn, Integer frameNo);

    long countByRawSn(Long rawSn);

    /**
     * 영상별 첫 프레임의 SRC_SN 을 한 번에 조회 (N+1 회피).
     *
     * <p>LS_DATA_SRC.SRC_SN 은 IDENTITY 로 발급되며, FRAME_EXTRACT 단계가 FRAME_NO 오름차순으로
     * insert 하므로 동일 RAW_SN 내에서 MIN(SRC_SN) 은 FRAME_NO=0 의 row 와 동치이다.
     * 결과는 {@code [rawSn, firstSrcSn]} Object 배열 리스트. 빈 인자는 빈 결과를 반환한다.
     */
    @Query("select s.rawSn as rawSn, min(s.srcSn) as firstSrcSn "
            + "from LsDataSrc s where s.rawSn in :rawSns group by s.rawSn")
    List<Object[]> findFirstSrcSnGroupedByRawSn(@Param("rawSns") Collection<Long> rawSns);
}
