package kr.co.cudo.authoring.dataset.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import kr.co.cudo.authoring.observability.metrics.MetaReplicationMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * control DB outbox → 포털 DB 복제본 단방향 at-least-once 복제 워커.
 *
 * <p>주기 트리거({@link kr.co.cudo.authoring.dataset.config.MetaReplicationJobConfig}, Quartz
 * {@code @DisallowConcurrentExecution})가 {@link #replicatePending()} 을 호출한다. 각 outbox 는
 * <b>self-contained PAYLOAD</b>(비식별 메타 JSON)로부터 스냅샷을 복원해 포털에 멱등 upsert 하므로
 * control 원본 테이블을 되읽지 않는다(표준 outbox 패턴).
 *
 * <p>트랜잭션 경계 — control 과 포털은 물리 분리(XA 없음)라 분리한다:
 * <ol>
 *   <li>outbox 폴링(control readOnly)</li>
 *   <li>{@link PortalMetaReplicaWriter#replicate}(portalTransactionManager)</li>
 *   <li>{@link MetaReplicationOutboxService#markDone/markFailure}(controlTransactionManager)</li>
 * </ol>
 * 포털 성공 후 DONE 표기 실패(크래시) 시 다음 tick 이 멱등 재복제로 흡수(at-least-once). 복제 실패는
 * 승인/materialize(이미 커밋됨)에 영향을 주지 않는다(관심 분리).
 *
 * <p>동시성: 단일 인스턴스 배포(CLAUDE.md) + Quartz {@code @DisallowConcurrentExecution}(HA 는 QRTZ 락)로
 * 동시 tick 이 없으므로 outbox row-level claim 은 두지 않는다. 보안: 페이로드/로그에 식별자(rawSn·outboxSn)만
 * 남기고 PII/본문을 남기지 않는다(CWE-359/209).
 */
@Slf4j
@Component
public class MetaReplicationWorker {

    private final LsMetaReplOutboxRepository outboxRepository;
    private final PortalMetaReplicaWriter replicaWriter;
    private final MetaReplicationOutboxService outboxService;
    private final MetaReplicationMetrics metrics;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public MetaReplicationWorker(LsMetaReplOutboxRepository outboxRepository,
                                 PortalMetaReplicaWriter replicaWriter,
                                 MetaReplicationOutboxService outboxService,
                                 MetaReplicationMetrics metrics,
                                 ObjectMapper objectMapper,
                                 @Value("${authoring.meta-replication.batch-size:100}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.replicaWriter = replicaWriter;
        this.outboxService = outboxService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    /**
     * PENDING outbox 를 배치 폴링해 포털로 복제한다.
     *
     * @return 이번 tick 에 복제 완료(DONE)한 건수
     */
    public int replicatePending() {
        // 포털 복제본 테이블 미프로비저닝 시 graceful skip(재시도/dead-letter 폭주 방지). 중단 아님.
        if (!replicaWriter.isReplicaAvailable()) {
            return 0;
        }

        List<LsMetaReplOutbox> pending = outboxRepository.findByStatusOrderByRegDtAsc(
                LsMetaReplOutbox.STATUS_PENDING, PageRequest.of(0, batchSize));
        int done = 0;
        for (LsMetaReplOutbox outbox : pending) {
            try {
                replicaWriter.replicate(toSnapshot(outbox));
                outboxService.markDone(outbox.getOutboxSn());
                done++;
            } catch (RuntimeException e) {
                log.warn("[MetaReplication] replicate failed outboxSn={} rawSn={} reason={}",
                        outbox.getOutboxSn(), outbox.getRawSn(), e.getClass().getSimpleName());
                // markFailure 자체가 control DB 오류로 실패해도 tick 을 중단하지 않고 나머지 outbox 를 계속 처리한다.
                // (데이터 유실 아님 — outbox 는 PENDING 유지되어 다음 tick 이 재시도. SLA 보호.)
                try {
                    outboxService.markFailure(outbox.getOutboxSn());
                } catch (RuntimeException me) {
                    metrics.incrementMarkFailureError();
                    log.error("[MetaReplication] markFailure failed outboxSn={} — continuing tick reason={}",
                            outbox.getOutboxSn(), me.getClass().getSimpleName());
                }
            }
        }
        if (done > 0) {
            log.info("[MetaReplication] tick replicated count={}", done);
        }
        return done;
    }

    /** outbox PAYLOAD(JSON) → 포털 복제용 스냅샷 엔티티. 관리 컬럼은 기본값으로 채운다. */
    private LsDatasetVideoMeta toSnapshot(LsMetaReplOutbox outbox) {
        MetaReplicationPayload p;
        try {
            p = objectMapper.readValue(outbox.getPayload(), MetaReplicationPayload.class);
        } catch (Exception e) {
            // 역직렬화 실패는 이 outbox 로 복구 불가 — 예외로 올려 markFailure→dead-letter 로 격리한다.
            throw new IllegalStateException("outbox payload 역직렬화 실패 outboxSn=" + outbox.getOutboxSn(), e);
        }
        return LsDatasetVideoMeta.builder()
                .rawSn(outbox.getRawSn())          // outbox 키를 권위값으로 사용
                .snpshtHash(outbox.getSnpshtHash())
                .activeYn(LsDatasetVideoMeta.ACTIVE_YES)
                .orgnlRawSn(p.orgnlRawSn())
                .vmsClipId(p.vmsClipId())
                .vmsCctvId(p.vmsCctvId())
                .rawFilePathNm(p.rawFilePathNm())
                .shtDt(p.shtDt())
                .vdoLenSec(p.vdoLenSec())
                .lclgvCd(p.lclgvCd())
                .prvcYn(p.prvcYn())
                .prvcTypeCd(p.prvcTypeCd())
                .deIdentYn(p.deIdentYn())
                .aiCrtYn(p.aiCrtYn())
                .evntTypeCd(p.evntTypeCd())
                .cctvNm(p.cctvNm())
                .wgs84Lat(p.wgs84Lat())
                .wgs84Lot(p.wgs84Lot())
                .sidoNm(p.sidoNm())
                .sggNm(p.sggNm())
                .fileFmt(p.fileFmt())
                .evntNm(p.evntNm())
                .vdoCdc(p.vdoCdc())
                .fps(p.fps())
                .bitRt(p.bitRt())
                .asprtRt(p.asprtRt())
                .resl(p.resl())
                .vdoWdth(p.vdoWdth())
                .vdoHgt(p.vdoHgt())
                .fileSz(p.fileSz())
                .dayNgtCd(p.dayNgtCd())
                .sesnCd(p.sesnCd())
                .wthrNm(null)               // 페이로드 미포함(UI 수기) — 복제 시 null
                .rvwCmplDt(p.rvwCmplDt())
                .regDt(LocalDateTime.now())
                .regId(null)                // 복제본 등록자 없음 — SoT 감사 추적으로 대체
                .build();
    }
}
