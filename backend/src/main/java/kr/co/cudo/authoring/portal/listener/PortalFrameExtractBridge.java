package kr.co.cudo.authoring.portal.listener;

import kr.co.cudo.authoring.portal.event.PortalVideoUploadedEvent;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 포털 영상 업로드 완료 → 프레임 추출 트리거 브릿지.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 <b>업로드 완료 트랜잭션이 커밋된 이후에만</b>
 * 추출을 시작한다(롤백 시 미호출 — 완료 경쟁 차단). 리스너는 얇게 위임만 하고, 실제 추출은
 * {@link PortalFrameExtractRunner#runAsync(Long)} 가 별도 스레드에서 수행한다
 * ({@code DatasetExportBridge} 와 동일 구조).
 *
 * <p><b>관제 파이프라인과 분리(#18)</b>: 본 브릿지는 포털 전용 {@link PortalVideoUploadedEvent} 만
 * 소비하며 관제 {@code VideoIngestedEvent}(비식별 트리거)와 무관하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalFrameExtractBridge {

    private final PortalFrameExtractRunner runner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPortalVideoUploaded(PortalVideoUploadedEvent event) {
        Long uldSn = event.uldSn();
        log.info("[PortalFrameExtractBridge] portal video uploaded uldSn={} — triggering frame extract", uldSn);
        runner.runAsync(uldSn);
    }
}
