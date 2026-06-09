package kr.co.cudo.authoring.batch.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [개발/검수 전용] dev 업로드 경로를 단일 프로덕션 파이프라인으로 수렴시키는 비동기 실행기
 * (Phase 3 — 배치 파이프라인 재정렬).
 *
 * <p>운영(prd) 환경에서는 {@code @Profile("!prd")} 로 빈 자체가 등록되지 않는다.
 *
 * <p>dev 업로드는 사람 마킹·{@code VideoIngestedEvent} 가 없으므로 이 러너가 토글에 따라
 * 선두 단계(비식별 + MARKING_READY 전이)와 합성 마킹 생성을 순차 수행한 뒤,
 * 단일 프로덕션 {@link BatchOrchestrator#process(Long, Map)} 를 토글과 함께 호출한다.
 * 토글 대상 단계(FRAME_EXTRACT/YOLO/SAM2)는 orchestrator 내부에서 {@code isEnabled} 로 skip 된다.
 *
 * <h3>토글 의미 (신 순서)</h3>
 * <ul>
 *   <li>DEIDENTIFY on → {@code deidentifyStep.run(raw)} + MARKING_READY 전이. off 면 둘 다 건너뜀.
 *       (off 시 deIdntfYn 미설정 → FRAME_EXTRACT 가드에 걸리므로 FRAME 도 off 여야 정상 진행.)</li>
 *   <li>FRAME_EXTRACT on → 합성 마킹(frameIndex 0) 1건 생성·저장(프레임 추출이 marks 를 필요로 하므로).
 *       off 면 합성 마킹 미생성 — 기존 프레임으로 YOLO/SAM2 만 실행.</li>
 *   <li>YOLO/SAM2 → orchestrator 내부 {@code isEnabled} skip.</li>
 * </ul>
 *
 * <h3>설계 결정</h3>
 * <ul>
 *   <li>이벤트(VideoIngestedEvent) 대신 직접 순차 실행 — dev 는 비식별→마킹→배치를 한 스레드에서
 *       순서대로 처리해 비동기 타이밍 레이스(read-after-write 가시성)를 회피한다.</li>
 *   <li>{@code AsyncBatchRunner}/{@code AsyncDeidentifyRunner} 의 try/catch + WARN + 예외 삼킴 패턴을 따른다.
 *       재시도 큐는 주입하지 않아 구조적으로 큐 사용을 차단한다(외부 수동 재비식별 정책).</li>
 * </ul>
 */
@Slf4j
@Service
@Profile("!prd")
public class DevPipelineRunner {

    /** 합성 마킹 모드 식별용 이벤트명 fallback (raw 의 EVNT_TYPE_CD 미설정 시). */
    private static final String SYNTHETIC_EVENT_NAME = "DEV_SYNTHETIC";
    /** 합성 마킹 자동 모드의 프레임 간격(프레임 수) — createAuto 의 intervalFrames(1 이상 필수). */
    private static final int SYNTHETIC_INTERVAL_FRAMES = 1;
    /** 토글 키 — DTO/orchestrator 와 1:1 매핑. */
    private static final String STAGE_DEIDENTIFY = "DEIDENTIFY";
    private static final String STAGE_FRAME_EXTRACT = "FRAME_EXTRACT";

    private final BatchOrchestrator orchestrator;
    private final DeidentifyStep deidentifyStep;
    private final BatchTransitionService transitionService;
    private final VideoRepository videoRepository;
    private final LsMarkingRepository markingRepository;
    private final ObjectMapper objectMapper;

    public DevPipelineRunner(BatchOrchestrator orchestrator,
                             DeidentifyStep deidentifyStep,
                             BatchTransitionService transitionService,
                             VideoRepository videoRepository,
                             LsMarkingRepository markingRepository,
                             ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.deidentifyStep = deidentifyStep;
        this.transitionService = transitionService;
        this.videoRepository = videoRepository;
        this.markingRepository = markingRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * dev 업로드 영상에 대해 토글 기반 단일 파이프라인을 비동기로 실행한다.
     *
     * @param rawSn   영상 식별자
     * @param toggles {@link BatchStage} 이름 → enabled. null/누락 키는 enabled(true) 로 간주.
     */
    @Async("batchAsyncExecutor")
    public void runAsync(Long rawSn, Map<String, Boolean> toggles) {
        log.info("[DevPipelineRunner] starting dev pipeline rawSn={}", rawSn);
        LsDataRaw raw = loadRaw(rawSn).orElse(null);
        if (raw == null) {
            log.warn("[DevPipelineRunner] raw not found rawSn={} — skip", rawSn);
            return;
        }
        Map<String, Boolean> safeToggles = toggles == null ? Map.of() : toggles;
        boolean deidentOn = isOn(safeToggles, STAGE_DEIDENTIFY);
        boolean frameOn = isOn(safeToggles, STAGE_FRAME_EXTRACT);

        try {
            // 선두 비식별 — dev 한정 토글 skip 가능. 성공 시에만 MARKING_READY 전이.
            if (deidentOn) {
                deidentifyStep.run(raw);
                transitionService.markRawDataMarkingReady(rawSn);
            } else {
                log.info("[DevPipelineRunner] deidentify skipped (toggle off) rawSn={}", rawSn);
            }

            // FRAME_EXTRACT on → 합성 마킹 생성(프레임 추출이 marks 필요). dev 전용.
            if (frameOn) {
                createSyntheticMarking(raw);
            }

            orchestrator.process(rawSn, safeToggles);
            log.info("[DevPipelineRunner] dev pipeline completed rawSn={}", rawSn);
        } catch (RuntimeException e) {
            // @Async 이므로 예외 삼킴 + WARN. 재시도 큐 미사용(외부 수동 재비식별 정책).
            log.warn("[DevPipelineRunner] dev pipeline failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * dev 합성 마킹 1건(frameIndex 0)을 생성·저장한다. 프로덕션 마킹 로직 오염 금지 —
     * 기존 marking 도메인 팩토리({@link LsMarking#createAuto})만 재사용한다.
     */
    private void createSyntheticMarking(LsDataRaw raw) {
        String eventName = raw.getEvntTypeCd() == null || raw.getEvntTypeCd().isBlank()
                ? SYNTHETIC_EVENT_NAME : raw.getEvntTypeCd();
        String videoPath = raw.getRawFilePathNm();
        String marksJson = serializeMarks(List.of(new MarkItem(0, null)));
        LsMarking marking = LsMarking.createAuto(
                raw.getRawSn(), eventName, SYNTHETIC_INTERVAL_FRAMES, videoPath, marksJson, null);
        markingRepository.save(marking);
        log.info("[DevPipelineRunner] synthetic marking created rawSn={}", raw.getRawSn());
    }

    private String serializeMarks(List<MarkItem> marks) {
        try {
            return objectMapper.writeValueAsString(marks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("합성 마킹 직렬화 실패 rawSn=" + (marks == null ? "?" : ""), e);
        }
    }

    private static boolean isOn(Map<String, Boolean> toggles, String key) {
        Boolean v = toggles.get(key);
        return v == null || v; // 누락/null → enabled(true)
    }

    /** 영상 메타 조회 — REQUIRES_NEW readOnly (AsyncDeidentifyRunner.loadRaw 패턴). */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    protected Optional<LsDataRaw> loadRaw(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }
        return videoRepository.findById(rawSn);
    }
}
