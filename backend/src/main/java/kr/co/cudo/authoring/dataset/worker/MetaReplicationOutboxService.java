package kr.co.cudo.authoring.dataset.worker;

import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import kr.co.cudo.authoring.observability.metrics.MetaReplicationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * control DB outbox 상태 전이 담당 — {@code controlTransactionManager} 트랜잭션 경계.
 *
 * <p>포털 write(별도 트랜잭션)와 분리해, 복제 성공 시 {@code DONE}, 실패 시 {@code RTRY_NMTM++}
 * (max 초과 시 {@code DEAD} dead-letter)로 전이한다. 두 DB 는 XA 가 없으므로 "포털 성공 후 DONE 표기 실패"
 * 시 다음 tick 이 멱등 재복제(at-least-once)로 흡수한다.
 */
@Slf4j
@Service
public class MetaReplicationOutboxService {

    private final LsMetaReplOutboxRepository outboxRepository;
    private final MetaReplicationMetrics metrics;
    private final int maxRetry;

    public MetaReplicationOutboxService(
            LsMetaReplOutboxRepository outboxRepository,
            MetaReplicationMetrics metrics,
            @Value("${authoring.meta-replication.max-retry:5}") int maxRetry) {
        this.outboxRepository = outboxRepository;
        this.metrics = metrics;
        this.maxRetry = maxRetry;
    }

    /** 복제 성공 — DONE 전이. */
    @Transactional("controlTransactionManager")
    public void markDone(Long outboxSn) {
        outboxRepository.findById(outboxSn).ifPresent(o -> {
            o.markDone();
            outboxRepository.save(o);
        });
    }

    /** 복제 실패 — RTRY_NMTM 증가 후 max 도달 시 DEAD(dead-letter), 아니면 PENDING 유지(다음 tick 재시도). */
    @Transactional("controlTransactionManager")
    public void markFailure(Long outboxSn) {
        outboxRepository.findById(outboxSn).ifPresent(o -> {
            o.incrementRetry();
            if (o.getRtryNmtm() >= maxRetry) {
                o.markDead();
                // dead-letter 는 조용한 복제 유실이므로 ERROR + 메트릭으로 승격(관측성). DEAD→PENDING 재큐는 후속 백로그.
                log.error("[MetaReplication] outbox dead-letter — replication lost outboxSn={} rawSn={} rtryNmtm={}",
                        outboxSn, o.getRawSn(), o.getRtryNmtm());
                metrics.incrementDeadLetter();
            }
            outboxRepository.save(o);
        });
    }
}
