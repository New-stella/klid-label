package kr.co.cudo.authoring.batch.listener;

import kr.co.cudo.authoring.batch.runner.VlmWithheldResumeRunner;
import kr.co.cudo.authoring.label.event.DeidentGateReopenedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 비식별 누락 신고 해소 → <b>보류됐던 VLM 시계열 위탁 재개</b> 브릿지
 * (2026-07-29 — "VLM SKIPPED 에 재개 트리거가 없다" 대응).
 *
 * <p>{@link DeidentGateReopenedEvent} 를 {@code AFTER_COMMIT} 으로 받는다 — {@code DE_IDNTF_YN 'F'→'Y'}
 * 복원이 커밋된 뒤에 실행되어야 재개된 위탁이 스텝 진입부 게이트에서 스스로 다시 보류되지 않는다
 * ({@code DatasetExportBridge}/{@code AugmentRequestBridge} 와 동일 구조).
 *
 * <p>승인 노드 전용 이벤트가 아니라 게이트 재개방 이벤트를 듣는 이유: VLM 보류는 <b>파이프라인 진행
 * 중(=대개 미승인)</b> 영상에서 일어나므로, 승인 노드에만 오는 이벤트로는 재개 신호가 영원히 도달하지
 * 않는다. (해소 시 승인 노드에만 오는 것은 현재 {@code TaskModifiedEvent}(needsRecheck=true)이며,
 * 구 기재 {@code DeidentReportResolvedEvent} 는 발행처가 없는 휴면 확장점이다 — 어느 쪽이든 이 리스너의
 * 선택 근거는 같다.)
 *
 * <p>리스너는 얇게 위임만 하고 실제 재개(조건 판정 + 외부 위탁)는
 * {@link VlmWithheldResumeRunner#resumeAsync(Long)} 가 별도 스레드에서 수행한다 — 외부 호출은 블로킹
 * (최대 45s)이라 신고 해소 API 응답을 잡아둘 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlmResumeBridge {

    private final VlmWithheldResumeRunner resumeRunner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeidentGateReopened(DeidentGateReopenedEvent event) {
        log.info("[VlmResumeBridge] deident gate reopened rawSn={} — checking withheld VLM submit",
                event.rawSn());
        resumeRunner.resumeAsync(event.rawSn());
    }
}
