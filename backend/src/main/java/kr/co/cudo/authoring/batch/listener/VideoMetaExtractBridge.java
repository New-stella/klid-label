package kr.co.cudo.authoring.batch.listener;

import kr.co.cudo.authoring.batch.runner.AsyncVideoMetaRunner;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 영상 적재 완료 이벤트 수신 → 영상 기술메타(ffprobe) 추출 트리거 — NIA export Phase 2.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 적재 트랜잭션 커밋 이후에만 실행한다
 * (probe/저장 실패가 적재를 롤백하지 못하도록). 실제 ffprobe·META 저장은
 * {@link AsyncVideoMetaRunner#runAsync(Long)} 가 별도 스레드에서 graceful 하게 수행한다.
 *
 * <p>{@code IngestDeidentifyBridge}(선두 비식별)와 동일 이벤트를 구독하지만 서로 독립이다 —
 * 메타 추출 실패가 비식별을, 비식별 실패가 메타 추출을 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoMetaExtractBridge {

    private final AsyncVideoMetaRunner asyncVideoMetaRunner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVideoIngested(VideoIngestedEvent event) {
        Long rawSn = event.rawSn();
        log.debug("[VideoMetaExtractBridge] video ingested rawSn={} — triggering probe", rawSn);
        asyncVideoMetaRunner.runAsync(rawSn);
    }
}
