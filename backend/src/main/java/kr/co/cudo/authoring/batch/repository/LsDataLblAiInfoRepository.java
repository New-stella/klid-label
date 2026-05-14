package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

@ControlRepo
public interface LsDataLblAiInfoRepository extends JpaRepository<LsDataLblAiInfo, Long> {

    Optional<LsDataLblAiInfo> findFirstByDataLblSn(Long dataLblSn);

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
