package kr.co.cudo.authoring.dataset.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@ControlRepo
public interface LsMetaReplOutboxRepository extends JpaRepository<LsMetaReplOutbox, Long> {

    /**
     * 상태별 outbox 를 등록 순(오래된 것 우선)으로 폴링 조회한다.
     *
     * <p>복제 워커(Phase 3)가 {@code STATUS_PENDING} 을 오래된 순으로 배치 픽업할 때 사용한다.
     * {@code limit} 은 {@code Pageable}({@code PageRequest.of(0, limit)}) 로 전달한다.
     */
    List<LsMetaReplOutbox> findBySttsCdOrderByRegDtAsc(String sttsCd, Pageable pageable);

    /**
     * 같은 RAW_SN 의 기존 미완(PENDING) outbox 를 SUPERSEDED 로 전이한다(rawSn coalescing).
     *
     * <p>새 스냅샷 outbox 를 insert 하기 <b>직전</b>에 호출해 rawSn 당 PENDING outbox 를 최대 1건으로
     * 유지한다. materialize 는 {@code pg_advisory_xact_lock(rawSn)} 로 이미 직렬화되므로, 이 coalescing
     * 으로 옛(오래된 해시) outbox 가 나중에 재전달돼 포털을 stale 해시로 되살리는 순서 역전(CWE-362)을
     * 원천 차단한다. 반환값은 전이된 행수. 모든 값은 파라미터 바인딩(CWE-89).
     *
     * <p>{@code clearAutomatically=false} — 이 bulk update 는 outbox 행만 건드리며 승인 트랜잭션의 다른
     * managed 엔티티를 detach 시키지 않는다(footgun 회피). SUPERSEDED 는 워커가 PENDING 만 폴링하므로 제외된다.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE LsMetaReplOutbox o SET o.sttsCd = 'SUPERSEDED', o.prcsDt = CURRENT_TIMESTAMP "
            + "WHERE o.rawSn = :rawSn AND o.sttsCd = 'PENDING'")
    int supersedePending(@Param("rawSn") Long rawSn);
}
