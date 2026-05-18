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

    /**
     * 영상별 프레임 개수를 한 번에 조회 (N+1 회피).
     *
     * <p>TaskBoardService.list 의 page.map 람다에서 각 row 마다 countByRawSn(...) 을 호출하면
     * 페이지 size 만큼 SELECT COUNT 쿼리가 발생한다 (size 20 기준 20회). 이를 단일 GROUP BY 쿼리로
     * 통합하여 페이지당 1회로 축소한다. 결과는 {@code [rawSn, frameCount]} Object 배열 리스트.
     * 프레임이 0건인 영상은 결과에 포함되지 않으므로 caller 가 0L 폴백 처리해야 한다.
     */
    @Query("select s.rawSn as rawSn, count(s) as frameCount "
            + "from LsDataSrc s where s.rawSn in :rawSns group by s.rawSn")
    List<Object[]> countByRawSnsGrouped(@Param("rawSns") Collection<Long> rawSns);
}
