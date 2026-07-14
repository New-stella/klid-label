package kr.co.cudo.authoring.dataset.worker;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.PortalDatasetVideoMetaRepository;
import kr.co.cudo.authoring.observability.metrics.MetaReplicationMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;

/**
 * 포털 DB 복제본 write 어댑터 — {@code portalTransactionManager} 트랜잭션 경계 담당.
 *
 * <p>워커가 control outbox 를 읽어 이 컴포넌트로 포털 upsert 를 위임한다. control 과 포털은 물리 분리
 * (XA 없음)이므로 이 트랜잭션은 control 트랜잭션과 <b>독립</b>이다 — 포털 실패가 control 커밋을 롤백하지
 * 않는다(관심 분리). 활성 1건 불변식은 control 과 동일한 deactivate-then-insert(+activateByHash) 순서로 유지한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalMetaReplicaWriter {

    /** PostgreSQL {@code undefined_table} SQLSTATE — 복제본 미프로비저닝 판별. */
    private static final String SQLSTATE_UNDEFINED_TABLE = "42P01";

    private final PortalDatasetVideoMetaRepository portalRepository;
    private final MetaReplicationMetrics metrics;

    /**
     * 포털 복제본에 스냅샷을 멱등 upsert 한다(포털 트랜잭션) — last-writer-wins.
     *
     * <p>at-least-once 재복제(중복 outbox)는 {@code ON CONFLICT DO NOTHING} 으로 무해하며,
     * 동일 해시 재복제로 대상이 이미 'N' 이면 {@code activateByHash} 로 되살려 활성 0건을 방지한다.
     *
     * <p><b>순서 역전(옛 해시 재전달) 방어는 주 방어(coalescing) 가 단독으로 보장한다</b>: 승인 트랜잭션의
     * {@code pg_advisory_xact_lock(rawSn)} 로 직렬화된 구간에서 신규 outbox 삽입 <b>직전</b>에 같은 rawSn 의
     * 기존 PENDING outbox 를 {@code SUPERSEDED} 로 coalescing 하고
     * ({@code DatasetVideoMetaSnapshotService.materialize} + {@code LsMetaReplOutboxRepository.supersedePending}),
     * 워커는 {@code PENDING} 만 regDt-ASC 로 순차 폴링한다(단일 인스턴스 + {@code @DisallowConcurrentExecution}).
     * 따라서 옛 스냅샷 outbox 가 최신 뒤에 이 writer 로 재전달되는 일 자체가 없어, 포털이 stale 해시로
     * 되살아나는 순서 역전(CWE-362)이 원천 차단된다. 마지막으로 적용되는 스냅샷이 항상 최신이므로 이
     * writer 는 단순 last-writer-wins 로 안전하다.
     *
     * <p>과거 이 자리에 있던 wall-clock({@code RVW_CMPL_DT}) 기반 단조성 가드는 제거했다 — NTP 되감김 등
     * 시계 역행 시 논리적으로 최신인 스냅샷을 stale 로 오판·skip 하여 포털이 옛 해시에 영구 고정되는
     * false-skip 회귀를 유발했고, 위 주 방어와 중복되는 심층 방어였다.
     */
    @Transactional("portalTransactionManager")
    public void replicate(LsDatasetVideoMeta snapshot) {
        portalRepository.deactivatePrevious(snapshot.getRawSn(), snapshot.getSnpshtHash());
        int inserted = portalRepository.upsertSnapshot(snapshot);
        if (inserted == 0) {
            portalRepository.activateByHash(snapshot.getRawSn(), snapshot.getSnpshtHash());
        }
    }

    /**
     * 포털 복제본 테이블 가용성 확인 — 미프로비저닝 포털 DB 에서 워커가 graceful skip 하도록 한다.
     *
     * <p>테이블 부재({@code 42P01})는 정상 skip(WARN + missing 메트릭)이나, 그 외 실장애(연결 불가/권한 등)는
     * 조용한 skip 을 금지하고 ERROR 로그 + error 메트릭으로 승격한다(관측성). 어느 경우든 tick 을 중단시키지
     * 않기 위해 false 를 반환한다(다음 tick 이 재개).
     *
     * @return 테이블 접근 가능 시 true, 부재/실장애 시 false
     */
    @Transactional(value = "portalTransactionManager", readOnly = true)
    public boolean isReplicaAvailable() {
        try {
            portalRepository.probeReplicaTable();
            return true;
        } catch (RuntimeException e) {
            if (isMissingRelation(e)) {
                log.warn("[MetaReplication] portal replica table not provisioned — graceful skip");
                metrics.incrementReplicaUnavailableMissing();
            } else {
                // 실장애(연결 불가/권한 등)는 조용히 삼키지 않는다 — ERROR + 메트릭으로 승격.
                log.error("[MetaReplication] portal replica probe failed (real failure) reason={}",
                        e.getClass().getSimpleName(), e);
                metrics.incrementReplicaUnavailableError();
            }
            return false;
        }
    }

    /** 예외 원인 체인에 PostgreSQL {@code undefined_table}(42P01) SQLException 이 있으면 미프로비저닝으로 본다. */
    private static boolean isMissingRelation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && SQLSTATE_UNDEFINED_TABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
