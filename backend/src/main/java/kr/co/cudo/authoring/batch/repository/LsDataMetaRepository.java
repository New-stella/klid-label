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

    /**
     * 영상 메타 페이징 조회 — 관제 조회 API 전용(B-4, CWE-770).
     *
     * <p>VLM 콜백 1회당 최대 500 세그먼트 · {@code META_VL} 최대 2000자라 누적되면 전량 반환이 수 MB
     * 응답이 된다. 라벨 조회와 동일하게 페이징(기본 20 / 최대 100)으로만 노출한다.
     */
    org.springframework.data.domain.Page<LsDataMeta> findByRawSn(
            Long rawSn, org.springframework.data.domain.Pageable pageable);

    /**
     * 영상 메타 <b>전체</b> 건수 — 요약 조회에서 전량 적재 없이 카운트만 얻는다(CWE-770).
     *
     * <p>⚠ {@code LS_DATA_META} 에는 VLM 시계열 메타와 {@code video.*} 기술메타
     * ({@code VideoMetaService} 소유)가 <b>함께</b> 들어 있다. "시계열 메타가 있는가" 를 물으려면
     * 이 메서드가 아니라 {@link #countTimeseriesByRawSn} 을 써야 한다 — 기술메타만 있는 영상
     * (배치 직후 대다수 상태)에서 이 카운트는 양수라 시계열 존재로 <b>오산입</b>된다.
     */
    long countByRawSn(Long rawSn);

    /**
     * <b>시계열 메타만</b> 세는 건수 — {@code technicalKeyPrefix} 로 시작하는 기술메타를 제외한다.
     *
     * <p>제외 접두를 상수로 박지 않고 <b>파라미터로 받는</b> 이유: 판별의 단일 원천은
     * {@code VideoMetaService.KEY_PREFIX} / {@code VideoMetaService#isTechnicalKey} 이며, 여기에
     * 문자열을 복제하면 상수가 바뀌는 날 조용히 드리프트한다. 호출자는 반드시 그 상수를 넘긴다
     * (회귀 가드: {@code VlmWithheldResumeRunnerTest} · {@code LsDataMetaTimeseriesCountIT}).
     *
     * <p>{@code LIKE} 는 접두 매칭({@code startsWith})과 동치여야 하므로 접두에 LIKE 와일드카드
     * ({@code %}, {@code _})가 들어오면 안 된다 — 위 가드 테스트가 상수에 와일드카드가 없음을 고정한다.
     * 값은 전부 {@code :} 파라미터 바인딩이다(문자열 결합 없음 — CWE-89).
     *
     * @param technicalKeyPrefix 기술메타 키 접두 — {@code VideoMetaService.KEY_PREFIX}
     */
    @Query("select count(m) from LsDataMeta m "
            + "where m.rawSn = :rawSn and m.metaKey not like concat(:technicalKeyPrefix, '%')")
    long countTimeseriesByRawSn(@Param("rawSn") Long rawSn,
                                @Param("technicalKeyPrefix") String technicalKeyPrefix);

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
