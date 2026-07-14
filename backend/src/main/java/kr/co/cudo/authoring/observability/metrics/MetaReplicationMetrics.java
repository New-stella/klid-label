package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Phase 3 -- 포털 메타 복제 워커 Micrometer 메트릭.
 *
 * <p>조용한 복제 유실/skip 을 관측 가능하게 하기 위한 카운터 모음. control-notify 와 달리 복제는
 * 항상 활성이므로 조건부 등록을 두지 않는다.
 *
 * <p>메트릭:
 * <ul>
 *   <li>{@code meta.replication.replica.unavailable}{@code {reason=missing}} — 복제본 테이블 미프로비저닝(정상 skip)</li>
 *   <li>{@code meta.replication.replica.unavailable}{@code {reason=error}} — 포털 DB 실장애 probe 실패(승격 대상)</li>
 *   <li>{@code meta.replication.deadletter} — 재시도 초과 DEAD 전이(복제 유실 위험)</li>
 *   <li>{@code meta.replication.markfailure.error} — markFailure 자체 실패(control DB 오류, tick 계속)</li>
 * </ul>
 */
@Component
public class MetaReplicationMetrics {

    private static final String REPLICA_UNAVAILABLE = "meta.replication.replica.unavailable";

    private final Counter replicaUnavailableMissing;
    private final Counter replicaUnavailableError;
    private final Counter deadLetter;
    private final Counter markFailureError;

    public MetaReplicationMetrics(MeterRegistry registry) {
        this.replicaUnavailableMissing = Counter.builder(REPLICA_UNAVAILABLE)
                .tag("reason", "missing")
                .description("포털 복제본 테이블 미프로비저닝으로 tick graceful skip 건수")
                .register(registry);
        this.replicaUnavailableError = Counter.builder(REPLICA_UNAVAILABLE)
                .tag("reason", "error")
                .description("포털 DB 실장애로 복제본 probe 실패 건수")
                .register(registry);
        this.deadLetter = Counter.builder("meta.replication.deadletter")
                .description("재시도 초과로 DEAD(dead-letter) 전이한 outbox 건수")
                .register(registry);
        this.markFailureError = Counter.builder("meta.replication.markfailure.error")
                .description("markFailure 자체 실패(control DB 오류) 건수")
                .register(registry);
    }

    public void incrementReplicaUnavailableMissing() { replicaUnavailableMissing.increment(); }

    public void incrementReplicaUnavailableError() { replicaUnavailableError.increment(); }

    public void incrementDeadLetter() { deadLetter.increment(); }

    public void incrementMarkFailureError() { markFailureError.increment(); }
}
