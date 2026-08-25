package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.pipeline.BatchBundleTogglePolicy;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 프리셋이 채워진 뒤 <b>보류됐던 오토라벨 묶음을 재개</b>하는 비동기 실행기.
 * [@design ADR-054] [@design AC-115] [@design SEQ-001]
 *
 * <h3>왜 필요한가 (보류의 영구 고착)</h3>
 * <p>탐지 단계는 실효 프리셋이 없으면 오토라벨 묶음을 <b>실패가 아니라 보류</b>로 끝낸다(SKIPPED +
 * 사유 적재). 보류는 실패 행을 남기지 않아 배치 재시도 큐·실패 회수기 어느 쪽도 집지 않는다. 즉
 * 프리셋을 등록해도 스스로 재개되지 않는다 — 이 러너가 유일한 복구 경로이며, 골격은 신고 해소 후
 * 시계열 위탁을 되살리는 {@link VlmWithheldResumeRunner} 와 같다(새 패턴을 만들지 않는다).
 *
 * <h3>재개 조건 (전부 만족해야 한다)</h3>
 * <ol>
 *   <li><b>보류 기록 존재</b> — {@code (rawSn, YOLO, SKIPPED, 재개 대상 사유)} 감사 행. 사유 목록의
 *       단일 원천은 {@link YoloAutolabelStep#RESUMABLE_SKIP_REASONS} 다(여기서 재정의하지 않는다).
 *       ★<b>오토라벨 제외 선언</b>(라벨 0건 프리셋)의 사유는 그 목록에 없어 여기 걸리지 않는다 —
 *       사람이 일부러 뺀 영상을 프리셋을 고칠 때마다 되살리면 안 된다. [@design AC-119]</li>
 *   <li><b>프리셋이 이제 실효</b> — 판정은 {@link PresetLabelLookupService#resolve(String)} 단일
 *       진실원이다. 아직 실효하지 않으면 아무것도 하지 않는다(돌려봐야 스스로 다시 보류한다).</li>
 *   <li><b>아직 완료되지 않음</b> — 배치 단계가 {@code COMPLETED} 면 이미 재개돼 완주했다는 뜻이다.
 *       보류 기록은 append-only 라 지워지지 않으므로 <b>이 조건이 재촉발의 멱등성</b>을 담당한다.
 *       이것이 없으면 프리셋을 저장할 때마다 완주 영상이 통째로 다시 추론된다.</li>
 * </ol>
 *
 * <h3>재개 범위 = 오토라벨 묶음 전체 (탐지 → 분할 → 보간)</h3>
 * <p>묶음 → stage 토글 환산은 단일 지점({@link BatchBundleTogglePolicy})이 담당하고 여기서 구성원을
 * 재유도하지 않는다. 앞선 단계(시계열 위탁·프레임 추출)는 토글이 꺼져 다시 돌지 않는다 — 그러지 않으면
 * 외부 벤더 위탁이 이중으로 나간다.
 *
 * <p>⚠ <b>{@code BatchStageRerunService}(단계 지목 재수행)를 재사용할 수 없다.</b> 그 입구는
 * ①사람이 누른 건너뜀 표식 이력과 ②완주(COMPLETED) 상태를 요구하는데, 보류된 영상은 <b>둘 다 없다</b>
 * (표식은 자동 보류 사유 행이고 상태는 진입 직전 값으로 되돌아가 있다). 재사용한 것은 그 경로와
 * <b>같은 토글 정책과 같은 오케스트레이터 진입</b>이다.
 *
 * <h3>동시성 (CWE-362 — 2노드 Active-Active)</h3>
 * <p>선점은 오케스트레이터 진입 가드의 <b>단일 조건부 UPDATE</b>({@code claimForProcessing} — "PROCESSING
 * 이 아닐 때만 PROCESSING")가 담당한다. 두 노드가 같은 영상을 동시에 집어도 통과하는 쪽은 정확히
 * 하나이고 진 쪽은 {@code SKIPPED} 로 끝난다. 여기서 상태를 미리 읽어 판정하지 않는다 — 아래
 * 완료 여부 확인은 <b>비싼 재추론을 아끼는 선별</b>이지 상호배제 수단이 아니다.
 *
 * <p>{@code @Async} 인 이유: 재개는 후보 조회 + 파이프라인 실행이라 프리셋 저장 API 응답을 잡아둘 수
 * 없다. 실패는 삼키고 로깅만 한다 — 재개 실패가 이미 커밋된 프리셋 저장에 영향을 주면 안 된다.
 * 실패해도 보류 기록과 미완료 상태가 그대로 남아 다음 촉발에 다시 시도된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutolabelWithheldResumeRunner {

    private final BatchStatusService batchStatusService;
    private final PresetLabelLookupService presetLabelLookup;
    private final EventTypeService eventTypeService;
    private final VideoRepository videoRepository;
    private final BatchBundleTogglePolicy togglePolicy;
    private final BatchOrchestrator orchestrator;

    /**
     * 그 이벤트 유형에서 보류된 영상들의 오토라벨 묶음을 다시 돈다.
     *
     * @param evntTypeCd 프리셋이 걸린 이벤트 유형 코드(그룹 대표코드 축). null/공백이면 유형으로
     *                   좁히지 않고 보류 기록 전체를 후보로 본다 — 판정은 후보마다 다시 하므로 안전하다
     */
    @Async("batchAsyncExecutor")
    public void resumeAsync(String evntTypeCd) {
        try {
            List<Long> candidates = batchStatusService.stageSkippedRawSns(
                    BatchStage.YOLO, YoloAutolabelStep.RESUMABLE_SKIP_REASONS);
            if (candidates.isEmpty()) {
                return;
            }
            // ★그룹 축 — 프리셋은 그룹 대표코드에 걸리고 영상은 상세 EV-코드를 갖는다. 대표코드로
            //   직접 비교하면 같은 표시명 그룹의 비대표 유형 영상이 전부 후보에서 빠진다.
            //   그룹을 알 수 없으면(미등록 등) 좁히지 않는다 — 조건 ②가 어차피 최종 판정을 한다.
            Set<String> targetCodes = evntTypeCd == null || evntTypeCd.isBlank()
                    ? Set.of()
                    : eventTypeService.codesForFilterKey(evntTypeCd);
            int resumed = 0;
            for (Long rawSn : candidates) {
                if (resumeOne(rawSn, targetCodes)) {
                    resumed++;
                }
            }
            log.info("[AutolabelResume] preset changed — candidates={} resumed={}", candidates.size(), resumed);
        } catch (RuntimeException e) {
            // @Async — 예외를 밖으로 던져도 받을 곳이 없다. 재개는 best-effort 이며, 실패해도 보류 기록과
            // 미완료 상태가 그대로 남아 다음 촉발에 다시 시도된다.
            log.warn("[AutolabelResume] withheld autolabel resume failed cause={}", e.getClass().getSimpleName());
        }
    }

    /** @return 이번 호출이 그 영상의 오토라벨 묶음을 실제로 돌렸는가 */
    private boolean resumeOne(Long rawSn, Set<String> targetCodes) {
        Optional<LsDataRaw> raw = videoRepository.findById(rawSn);
        if (raw.isEmpty()) {
            return false;
        }
        String eventTypeCd = raw.get().getEvntTypeCd();
        if (!targetCodes.isEmpty() && (eventTypeCd == null || !targetCodes.contains(eventTypeCd))) {
            return false; // 다른 이벤트 유형의 보류분 — 이번 프리셋 변경과 무관하다.
        }
        if (LsDataRaw.DATA_STTS_COMPLETED.equals(raw.get().getDataSttsCd())) {
            // 이미 재개돼 완주했다 — 보류 기록은 지워지지 않으므로 이 확인이 재촉발의 멱등성을 담당한다.
            return false;
        }
        PresetResolution preset = presetLabelLookup.resolve(eventTypeCd);
        if (!preset.isResolved()) {
            return false; // 아직 실효하지 않는다 — 돌려봐야 스스로 다시 보류한다.
        }
        Map<String, Boolean> toggles = togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL);
        BatchStage result = orchestrator.process(rawSn, toggles);
        log.info("[AutolabelResume] resumed rawSn={} result={}", rawSn, result);
        return true;
    }
}
