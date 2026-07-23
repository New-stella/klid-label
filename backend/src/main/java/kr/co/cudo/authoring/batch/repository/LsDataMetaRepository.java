package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataMetaRepository extends JpaRepository<LsDataMeta, Long>, LsDataMetaRepositoryCustom {

    List<LsDataMeta> findByRawSn(Long rawSn);

    Optional<LsDataMeta> findByRawSnAndMetaKey(Long rawSn, String metaKey);

    /** 다건 metaKey 를 IN 절 1회로 일괄 조회 (VLM 콜백 results 배치 upsert — N+1 제거). */
    List<LsDataMeta> findByRawSnAndMetaKeyIn(Long rawSn, Collection<String> metaKeys);

    /**
     * (RAW_SN, META_KEY) 원자적 upsert — PostgreSQL {@code ON CONFLICT} 로 단일 문장에서 처리한다.
     *
     * <p>read-then-write 대신 DB 가 UK 충돌을 원자적으로 해소하므로 동시 실행(CWE-362) race 가
     * 발생해도 중복 행·값 유실 없이 멱등하게 동작한다. 신규 삽입 시 {@code MDFCN_DT} 는 NULL,
     * 충돌(기존 행) 갱신 시에만 {@code MDFCN_DT=now()} 로 세팅해 엔티티 {@code updateValue} 의미를
     * 실 DB 레벨에서 그대로 재현한다. 모든 값은 {@code :}파라미터 바인딩(문자열 결합 없음 — CWE-89).
     *
     * <p>{@code clearAutomatically=true} 로 실행 후 영속성 컨텍스트를 비워 이후 조회가 최신 DB 값을
     * 읽도록 한다(1차 캐시 stale 방지).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, REG_DT, MDFCN_DT) "
            + "VALUES (:rawSn, :metaKey, :metaVl, now(), NULL) "
            + "ON CONFLICT (RAW_SN, META_KEY) "
            + "DO UPDATE SET META_VL = EXCLUDED.META_VL, MDFCN_DT = now()",
            nativeQuery = true)
    void upsertMeta(@Param("rawSn") Long rawSn,
                    @Param("metaKey") String metaKey,
                    @Param("metaVl") String metaVl);
}
