package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchStageProgressMapper.StageStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BatchStageProgressMapper 순수 로직 검증 — 배치 단계 세분화 표시(이슈1).
 */
class BatchStageProgressMapperTest {

    private Map<String, String> statusByName(List<StageStatus> stages) {
        return stages.stream().collect(Collectors.toMap(StageStatus::name, StageStatus::status));
    }

    @Test
    @DisplayName("FRAME_EXTRACT_진행중이면_이전DONE_현재PROGRESS_이후PENDING")
    void frameExtractInProgress() {
        List<StageStatus> stages =
                BatchStageProgressMapper.build(BatchStage.FRAME_EXTRACT.name(), "STARTED", false);

        // 7단계(비식별~보간) 전부 노출
        assertThat(stages).hasSize(7);
        Map<String, String> m = statusByName(stages);
        assertThat(m.get(BatchStage.DEIDENTIFY.name())).isEqualTo("DONE");
        assertThat(m.get(BatchStage.MARKING.name())).isEqualTo("DONE");
        assertThat(m.get(BatchStage.VLM.name())).isEqualTo("DONE");
        assertThat(m.get(BatchStage.FRAME_EXTRACT.name())).isEqualTo("PROGRESS");
        assertThat(m.get(BatchStage.YOLO.name())).isEqualTo("PENDING");
        assertThat(m.get(BatchStage.SAM2.name())).isEqualTo("PENDING");
        assertThat(m.get(BatchStage.INTERPOLATE.name())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("실패상태면_해당단계FAIL_이후PENDING")
    void failedStageMarksFail() {
        List<StageStatus> stages =
                BatchStageProgressMapper.build(BatchStage.YOLO.name(), "FAILED", false);

        Map<String, String> m = statusByName(stages);
        assertThat(m.get(BatchStage.FRAME_EXTRACT.name())).isEqualTo("DONE");
        assertThat(m.get(BatchStage.YOLO.name())).isEqualTo("FAIL");
        assertThat(m.get(BatchStage.SAM2.name())).isEqualTo("PENDING");
        assertThat(m.get(BatchStage.INTERPOLATE.name())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("로그없음(step_null)이면_빈배열")
    void noLogReturnsEmpty() {
        assertThat(BatchStageProgressMapper.build(null, null, false)).isEmpty();
    }

    @Test
    @DisplayName("PENDING_큐대기이면_빈배열_배지폴백")
    void pendingQueueReturnsEmpty() {
        assertThat(BatchStageProgressMapper.build(BatchStage.PENDING.name(), "STARTED", false)).isEmpty();
    }

    @Test
    @DisplayName("COMPLETED면_전단계DONE")
    void completedAllDone() {
        List<StageStatus> stages =
                BatchStageProgressMapper.build(BatchStage.COMPLETED.name(), "COMPLETED", false);

        assertThat(stages).hasSize(7);
        assertThat(stages).allMatch(s -> "DONE".equals(s.status()));
    }

    @Test
    @DisplayName("영상이_COMPLETED면_로그단계와_무관하게_전단계DONE")
    void videoCompletedOverrides() {
        List<StageStatus> stages =
                BatchStageProgressMapper.build(BatchStage.YOLO.name(), "STARTED", true);

        assertThat(stages).allMatch(s -> "DONE".equals(s.status()));
    }

    @Test
    @DisplayName("선두_DEIDENTIFY_진행중이면_현재만PROGRESS_나머지PENDING")
    void deidentifyFirst() {
        List<StageStatus> stages =
                BatchStageProgressMapper.build(BatchStage.DEIDENTIFY.name(), "STARTED", false);

        Map<String, String> m = statusByName(stages);
        assertThat(m.get(BatchStage.DEIDENTIFY.name())).isEqualTo("PROGRESS");
        assertThat(m.get(BatchStage.MARKING.name())).isEqualTo("PENDING");
    }
}
