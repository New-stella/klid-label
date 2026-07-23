package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;

import java.util.ArrayList;
import java.util.List;

/**
 * 배치 진행률(단계별 상태) 표시용 순수 매퍼.
 *
 * <p>최신 {@link LsBatchProcLog} 1행의 현재 단계({@code procStepCd})와 상태({@code procSttsCd})를
 * canonical 파이프라인 순서로 펼쳐 단계별 상태(DONE/PROGRESS/PENDING/FAIL) 리스트를 만든다.
 *
 * <p>DB 접근이 없는 정적 메서드로 분리해 단위 테스트가 가능하다.
 */
public final class BatchStageProgressMapper {

    private BatchStageProgressMapper() {
    }

    /** 화면 표시 단계의 canonical 순서 (선두 비식별 → 마킹 → … → 보간). PENDING/COMPLETED/FAILED 는 표시 단계 아님. */
    public static final List<BatchStage> DISPLAY_ORDER = List.of(
            BatchStage.DEIDENTIFY,
            BatchStage.MARKING,
            BatchStage.VLM,
            BatchStage.FRAME_EXTRACT,
            BatchStage.YOLO,
            BatchStage.SAM2,
            BatchStage.INTERPOLATE
    );

    public static final String DONE = "DONE";
    public static final String PROGRESS = "PROGRESS";
    public static final String PENDING = "PENDING";
    public static final String FAIL = "FAIL";

    private static final String STTS_COMPLETED = "COMPLETED";
    private static final String STTS_FAILED = "FAILED";

    /** 단계별 상태 항목 (name=단계코드, status=DONE/PROGRESS/PENDING/FAIL, progress=nullable). */
    public record StageStatus(String name, String status, Integer progress) {
    }

    /**
     * 단계별 상태 리스트를 구성한다.
     *
     * @param currentStepCd  최신 로그의 {@code PROC_STEP_CD} — {@code null} 이면 로그 없음(빈 배열)
     * @param procSttsCd     최신 로그의 {@code PROC_STTS_CD} (STARTED/COMPLETED/FAILED)
     * @param videoCompleted 영상이 이미 COMPLETED(LS_DATA_RAW.DATA_STTS_CD) 인지
     * @return canonical 순서의 단계별 상태. 표시 대상이 아니면(로그 없음/큐 대기/단계 미상) 빈 배열 → FE 배지 폴백.
     */
    public static List<StageStatus> build(String currentStepCd, String procSttsCd, boolean videoCompleted) {
        // 로그 없음(배치 미진행/기존 영상) → 빈 배열 (FE 가 기존 배지로 폴백).
        if (currentStepCd == null) {
            return List.of();
        }
        boolean completed = videoCompleted
                || BatchStage.COMPLETED.name().equals(currentStepCd)
                || STTS_COMPLETED.equals(procSttsCd);
        if (completed) {
            return fill(-1, false);
        }
        int currentIdx = indexOf(currentStepCd);
        // 표시 단계가 아님(PENDING 큐 대기 또는 단계 미상 FAILED) → 빈 배열 (배지 폴백).
        if (currentIdx < 0) {
            return List.of();
        }
        boolean failed = STTS_FAILED.equals(procSttsCd);
        return fill(currentIdx, failed);
    }

    /** currentIdx=-1 이면 전 단계 DONE. 그 외 currentIdx 이전=DONE, 현재=PROGRESS(또는 FAIL), 이후=PENDING. */
    private static List<StageStatus> fill(int currentIdx, boolean failed) {
        List<StageStatus> out = new ArrayList<>(DISPLAY_ORDER.size());
        for (int i = 0; i < DISPLAY_ORDER.size(); i++) {
            String name = DISPLAY_ORDER.get(i).name();
            String status;
            if (currentIdx < 0 || i < currentIdx) {
                status = DONE;
            } else if (i == currentIdx) {
                status = failed ? FAIL : PROGRESS;
            } else {
                status = PENDING;
            }
            out.add(new StageStatus(name, status, null));
        }
        return out;
    }

    private static int indexOf(String stepCd) {
        for (int i = 0; i < DISPLAY_ORDER.size(); i++) {
            if (DISPLAY_ORDER.get(i).name().equals(stepCd)) {
                return i;
            }
        }
        return -1;
    }
}
