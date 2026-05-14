package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataLblAiInfoRepository extends JpaRepository<LsDataLblAiInfo, Long> {

    Optional<LsDataLblAiInfo> findFirstByDataLblSn(Long dataLblSn);

    /**
     * 일괄 lookup — N+1 회피용 (Phase 6).
     * LabelService 가 프레임 라벨 응답 빌드 시점에 한 번에 AI 정보를 채워넣기 위해 사용.
     */
    List<LsDataLblAiInfo> findByDataLblSnIn(Collection<Long> dataLblSns);

    @Modifying
    @Query("""
            UPDATE LsDataLblAiInfo ai
               SET ai.confScore = :score,
                   ai.lblSrcCd = :source,
                   ai.mdfcnId = :actor
             WHERE ai.dataLblSn = :dataLblSn
            """)
    int updateConfidence(@Param("dataLblSn") Long dataLblSn,
                         @Param("score") BigDecimal score,
                         @Param("source") String source,
                         @Param("actor") String actor);

    @Modifying
    void deleteByDataLblSnIn(Collection<Long> dataLblSns);
}
