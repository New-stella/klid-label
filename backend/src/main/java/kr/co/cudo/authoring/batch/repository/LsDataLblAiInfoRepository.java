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

    /**
     * 일괄 물리 삭제 — 진짜 bulk DELETE (파생 delete 의 SELECT-후-엔티티별-remove N+1 회피).
     * <p>재실행 idempotency 경로에서 stale 보간 AI_INFO 를 한 번의 DELETE 로 제거한다.
     * 벌크 삭제 대상 엔티티는 이 트랜잭션에서 사전 로드되지 않고, 삭제 후 재삽입 row 는
     * 새 {@code dataLblSn} 으로 INSERT 되므로 영속성 컨텍스트 stale 참조가 없다
     * (→ {@code clearAutomatically} 불필요).</p>
     */
    @Modifying
    @Query("DELETE FROM LsDataLblAiInfo ai WHERE ai.dataLblSn IN :dataLblSns")
    int deleteByDataLblSnIn(@Param("dataLblSns") Collection<Long> dataLblSns);
}
