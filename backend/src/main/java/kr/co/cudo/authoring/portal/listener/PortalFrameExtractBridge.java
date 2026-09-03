package kr.co.cudo.authoring.portal.listener;

import kr.co.cudo.authoring.portal.event.PortalMarkingCompletedEvent;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 포털 <b>마킹 저장 완료</b> → 프레임 추출 트리거 브릿지.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} 로 <b>마킹 저장 트랜잭션이 커밋된 이후에만</b>
 * 추출을 시작한다(롤백 시 미호출 — 경쟁 차단). 리스너는 얇게 위임만 하고, 실제 추출은
 * {@link PortalFrameExtractRunner#runAsync(Long)} 가 별도 스레드에서 수행한다
 * ({@code DatasetExportBridge} 와 동일 구조).
 *
 * <p><b>파이프라인 순서 반전(2026-09-02)</b>: 구 동작은 <b>업로드 직후</b> 고정 간격으로 뽑는 것이었고
 * 그 트리거 이벤트는 폐기됐다. 지금은 마킹이 정한 지점으로만 뽑으므로, 마킹하지 않은 자산은 프레임이
 * 없는 것이 정상이다.
 *
 * <p><b>관제 파이프라인과 분리</b>: 본 브릿지는 포털 전용 {@link PortalMarkingCompletedEvent} 만
 * 소비한다. 관제 마킹 완료 이벤트({@code MarkingCompletedEvent})와 <b>타입이 달라</b> 이 리스너가
 * 관제 마킹에 반응할 수 없고, 반대로 관제 배치 브리지도 포털 마킹에 반응할 수 없다 — 격리는 조건문이
 * 아니라 타입이 보장한다.
 *
 * @design API-240
 * @design ADR-013
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalFrameExtractBridge {

    private final PortalFrameExtractRunner runner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPortalMarkingCompleted(PortalMarkingCompletedEvent event) {
        Long uldSn = event.uldSn();
        log.info("[PortalFrameExtractBridge] portal marking saved uldSn={} markingSn={} — triggering frame extract",
                uldSn, event.markingSn());
        runner.runAsync(uldSn);
    }
}
