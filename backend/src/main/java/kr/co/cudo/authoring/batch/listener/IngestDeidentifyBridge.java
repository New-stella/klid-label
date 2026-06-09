package kr.co.cudo.authoring.batch.listener;

import kr.co.cudo.authoring.batch.runner.AsyncDeidentifyRunner;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 영상 적재 완료 이벤트 수신 → 선두 비식별 트리거 (Phase 2).
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 적재 트랜잭션 커밋 이후에만 실행한다
 * (커밋 전 비식별이 시작되어 롤백과 경쟁하는 것을 방지). 리스너는 동기로 동작하고 실제 비식별은
 * {@link AsyncDeidentifyRunner#runAsync(Long)} 가 별도 스레드에서 수행한다 —
 * {@code MarkingBatchBridge} 와 동일 구조.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestDeidentifyBridge {

    private final AsyncDeidentifyRunner asyncDeidentifyRunner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVideoIngested(VideoIngestedEvent event) {
        Long rawSn = event.rawSn();
        log.info("[IngestDeidentifyBridge] video ingested rawSn={} — triggering deidentify", rawSn);
        asyncDeidentifyRunner.runAsync(rawSn);
    }
}
