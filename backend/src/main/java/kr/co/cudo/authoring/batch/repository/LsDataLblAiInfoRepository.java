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
     * 영상(rawSn)에서 <b>지정 출처의 자동 라벨이 이미 적재된 프레임</b>(DATA_SRC_SN) 목록 — 재실행 멱등 판정.
     *
     * <p>배치 재시도는 파이프라인을 선두부터 전부 다시 돌린다({@code BatchRetryQuartzJob} →
     * {@code BatchOrchestrator.process}). 오토라벨 단계가 "이미 했는지"를 보지 않으면 재시도마다 같은
     * 프레임에 자동 라벨이 <b>중복 적재</b>된다(2배·3배…). 판정 축을 {@code LS_DATA_LBL_AI_INFO} 로 삼는
     * 이유는 출처({@code LBL_SRC_CD}=YOLO/SAM2)와 자동 여부({@code AUTO_LBL_YN})가 라벨 본체가 아니라
     * <b>이 테이블에만</b> 있기 때문이다({@code LsDataLbl.autoLblYn}·{@code lblSrcCd} 는 {@code @Transient}).
     *
     * <p><b>단일 쿼리</b>로 프레임 집합을 한 번에 얻는다 — 프레임마다 존재 여부를 묻는 N+1 을 만들지
     * 않는다. 고정 리터럴 없이 파라미터 바인딩({@code :rawSn}, {@code :lblSrcCd})만 사용(CWE-89).
     * 결과는 식별자뿐이라 PII 를 싣지 않는다.
     *
     * <p>⚠ 이 판정은 <b>삭제-후-재삽입의 근거가 아니다</b>. 자동 라벨은 사람이 이미 수정했을 수 있어
     * ({@code TrackInterpolationStep} 의 보간 산출물과 달리) 지우면 작업 결과가 파괴된다 — 호출부는
     * 반드시 <b>추론·적재를 건너뛰는</b> 방향으로만 쓴다.
     *
     * @param lblSrcCd {@code LsDataLblAiInfo.SRC_YOLO} / {@code SRC_SAM2}
     * @req R1
     */
    @Query("""
            SELECT DISTINCT ai.dataSrcSn
              FROM LsDataLblAiInfo ai
             WHERE ai.dataRawSn = :rawSn
               AND ai.lblSrcCd = :lblSrcCd
            """)
    List<Long> findDistinctSrcSnsByRawSnAndLblSrcCd(@Param("rawSn") Long rawSn,
                                                    @Param("lblSrcCd") String lblSrcCd);

    /**
     * 일괄 물리 삭제 — 진짜 bulk DELETE (파생 delete 의 SELECT-후-엔티티별-remove N+1 회피).
     * <p>재실행 idempotency 경로에서 stale 보간 AI_INFO 를 한 번의 DELETE 로 제거한다.
     * 벌크 삭제 대상 엔티티는 이 트랜잭션에서 사전 로드되지 않고, 삭제 후 재삽입 row 는
     * 새 {@code dataLblSn} 으로 INSERT 되므로 영속성 컨텍스트 stale 참조가 없다
     * (→ {@code clearAutomatically} 불필요).</p>
     *
     * <p><b>주의(clearAutomatically 미적용)</b>: 본 메서드는 {@code DeidentReportService.report} /
     * {@code TrackEditService} 등과 공유된다. {@code clearAutomatically=true} 를 붙이면 삭제 호출 시점에
     * 영속성 컨텍스트가 비워져, 호출부가 <b>이후 수정하는 FOR UPDATE 잠금 엔티티(예: 부모 RAW 의
     * DE_IDNTF_YN='F')</b>가 detach 되어 flush 되지 않는 회귀가 발생한다(PII 노출 차단 실패,
     * AugmentDeidentConcurrencyIT 검증). 따라서 순수 bulk DELETE 만 유지한다.</p>
     */
    @Modifying
    @Query("DELETE FROM LsDataLblAiInfo ai WHERE ai.dataLblSn IN :dataLblSns")
    int deleteByDataLblSnIn(@Param("dataLblSns") Collection<Long> dataLblSns);
}
