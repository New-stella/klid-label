package kr.co.cudo.authoring.label.listener;

import kr.co.cudo.authoring.label.event.DeidentStageResumeEvent;
import kr.co.cudo.authoring.label.service.DeidentStageResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 비식별 누락 신고 해소 → <b>신고 단계별 작업 재개</b> 브릿지 (V171).
 *
 * <p>{@link DeidentStageResumeEvent} 를 {@code AFTER_COMMIT} 으로 받는다 — {@code DE_IDNTF_YN 'F'→'Y'}
 * 복원이 커밋된 뒤에 실행되어야 재개 작업이 자기 신고 게이트에 스스로 막히지 않는다
 * ({@code VlmResumeBridge}/{@code DatasetExportBridge} 와 동일 구조).
 *
 * <p>리스너는 얇게 위임만 하고 실제 재개는 {@link DeidentStageResumeService}(별도 빈,
 * {@code REQUIRES_NEW})가 수행한다 — AFTER_COMMIT 컨텍스트에는 활성 트랜잭션이 없어 dirty checking 이
 * 동작하지 않는다.
 *
 * <p><b>예외를 삼킨다</b>: 이 시점의 실패는 이미 커밋된 해소(RESOLVED · 작업락 해제 · {@code 'Y'} 복원)를
 * 되돌리지 못한다. 예외를 밖으로 던지면 같은 이벤트를 듣는 <b>다른 리스너</b>(export 재산출 등)의 실행까지
 * 위태로워지므로, 여기서 ERROR 로 드러내고 종료한다(재개 실패 = 수동 재처리 대상).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeidentStageResumeBridge {

    private final DeidentStageResumeService resumeService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeidentStageResume(DeidentStageResumeEvent event) {
        log.info("[DeidentStageResumeBridge] resuming after deident report resolve rawSn={} stage={}",
                event.rawSn(), event.stage());
        try {
            resumeService.resume(event.rawSn(), event.stage());
        } catch (RuntimeException e) {
            log.error("[DeidentStageResumeBridge] resume failed rawSn={} stage={} err={}",
                    event.rawSn(), event.stage(), e.getMessage());
        }
    }
}
