package kr.co.cudo.authoring.batch.listener;

import kr.co.cudo.authoring.batch.runner.AutolabelWithheldResumeRunner;
import kr.co.cudo.authoring.preset.event.PresetLabelsChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 프리셋 등록·수정 → <b>보류됐던 오토라벨 묶음 재개</b> 브릿지.
 * [@design ADR-054] [@design AC-115]
 *
 * <p>{@link PresetLabelsChangedEvent} 를 {@code AFTER_COMMIT} 으로 받는다 — 프리셋 저장이 커밋된 뒤에
 * 실행되어야 재개 러너가 <b>그 프리셋을 실제로 보고</b> 실효 판정을 내린다. 커밋 전에 돌면 아직 보이지
 * 않는 프리셋을 조회해 스스로 다시 보류한다({@code VlmResumeBridge} 와 같은 구조).
 *
 * <p>리스너는 얇게 위임만 하고 실제 재개(후보 조회 + 파이프라인 실행)는
 * {@link AutolabelWithheldResumeRunner#resumeAsync(String)} 가 별도 스레드에서 수행한다 — 오토라벨은
 * 프레임마다 추론을 도는 긴 작업이라 프리셋 저장 API 응답을 잡아둘 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutolabelResumeBridge {

    private final AutolabelWithheldResumeRunner resumeRunner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPresetLabelsChanged(PresetLabelsChangedEvent event) {
        log.info("[AutolabelResumeBridge] preset labels changed evntTypeCd={} — checking withheld autolabel",
                event.evntTypeCd());
        resumeRunner.resumeAsync(event.evntTypeCd());
    }
}
