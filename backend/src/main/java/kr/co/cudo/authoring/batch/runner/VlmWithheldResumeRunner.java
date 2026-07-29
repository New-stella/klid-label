package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 비식별 누락 신고 해소 후 <b>보류됐던 VLM 시계열 위탁을 재개</b>하는 비동기 실행기
 * (2026-07-29 — MEDIUM "VLM SKIPPED 에 재개 트리거가 없다" 대응).
 *
 * <h3>왜 필요한가 (시계열 메타 영구 결손)</h3>
 * {@code VlmTimeseriesStep} 은 신고 구간에서 외부 전송을 <b>실패가 아니라 보류</b>로 끝낸다(SKIPPED +
 * 사유를 {@code LS_BATCH_PROC_LOG} 에 적재). 보류는 실패 행을 남기지 않으므로 배치 재시도 큐
 * ({@code BatchRetryQueue})·실패 회수기 어느 쪽도 이를 집지 않는다. 즉 신고를 해소해도 <b>스스로
 * 재개되지 않고</b> 그 영상의 시계열 메타(LS_DATA_META)가 영원히 비게 된다 —
 * {@code AugmentRequestBridge#onDeidentReportResolved}(보류된 증강 위탁 재개)와 <b>동일한 구조의
 * 결손</b>이며, 해제 시점 재트리거가 유일한 복구 경로다.
 *
 * <h3>재개 조건 (둘 다 만족)</h3>
 * <ol>
 *   <li><b>보류 기록 존재</b> — {@code (rawSn, VLM, SKIPPED, 신고 보류 사유)} 감사 행. 사유 문자열의
 *       단일 원천은 {@link VlmTimeseriesStep#SKIP_REASON_DEIDENT_REPORT} 다(여기서 재정의하지 않는다).</li>
 *   <li><b>시계열 메타 0건</b> — 콜백으로 이미 결과가 적재됐다면 재위탁은 중복 메타·중복 검수행을 만든다.
 *       보류 기록은 append-only 라 지워지지 않으므로, 이 조건이 <b>재개의 멱등성</b>을 담당한다.</li>
 * </ol>
 *
 * <p>마킹이 있으면 {@code runWithMarking} 으로 위탁해 마킹 상태 전이(PENDING → VLM_REQUESTED)까지
 * 원래 파이프라인과 동일하게 이어간다({@code VlmTimeseriesStep.execute} 의 분기와 같은 규칙 ·
 * {@code MarkingLoadStep} 과 같은 정렬로 최신 마킹 1건 선택).
 *
 * <p>{@code @Async} 인 이유: 위탁은 외부 호출(블로킹, 최대 45s)이라 신고 해소 API 응답을 잡아둘 수 없다.
 * 실패는 삼키고 로깅만 한다 — 재개 실패가 신고 해소 트랜잭션(이미 커밋됨)에 영향을 주면 안 된다.
 * 게이트 판정은 스텝 안에서 다시 수행되므로, 어떤 이유로든 아직 닫혀 있으면 스스로 다시 보류된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VlmWithheldResumeRunner {

    private final VlmTimeseriesStep vlmTimeseriesStep;
    private final BatchStatusService batchStatusService;
    private final LsDataMetaRepository metaRepository;
    private final LsMarkingRepository markingRepository;

    @Async("batchAsyncExecutor")
    public void resumeAsync(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        try {
            if (!isWithheld(rawSn)) {
                return;
            }
            log.info("[VlmResume] deident report resolved — resuming withheld VLM submit rawSn={}", rawSn);
            List<LsMarking> markings = markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn);
            if (markings.isEmpty()) {
                vlmTimeseriesStep.run(rawSn);
            } else {
                vlmTimeseriesStep.runWithMarking(rawSn, markings.get(0));
            }
        } catch (RuntimeException e) {
            // @Async — 예외를 밖으로 던져도 받을 곳이 없다. 재개는 best-effort 이며, 실패해도 보류 기록과
            // "메타 0건" 조건이 그대로 남아 다음 해소/재처리 시 다시 시도된다.
            log.warn("[VlmResume] withheld VLM resume failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /** 신고 보류로 건너뛴 기록이 있고, 아직 시계열 메타가 한 건도 없는가. */
    private boolean isWithheld(Long rawSn) {
        boolean withheldLogged = batchStatusService.isStageSkippedWithReason(
                rawSn, BatchStage.VLM, VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT);
        if (!withheldLogged) {
            return false;
        }
        long metaCount = metaRepository.countByRawSn(rawSn);
        if (metaCount > 0) {
            log.info("[VlmResume] resume skipped — timeseries meta already present rawSn={} count={}",
                    rawSn, metaCount);
            return false;
        }
        return true;
    }
}
