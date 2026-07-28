package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-ISSUE-24 — VLM skip 기록의 <b>내구성</b> 통합 테스트 (실 DB, PostgreSQL Testcontainer).
 *
 * <p>{@code LS_BATCH_PROC_LOG} 는 영상당 진행 행 1건을 <b>갱신</b>하며 전진한다. 따라서 skip 을 그 행에
 * 기록하면 다음 단계({@code FRAME_EXTRACT}) 전이가 즉시 덮어써 흔적이 사라진다 — 실측 결함의 형태 그대로다.
 * skip 은 별도 <b>감사 행</b>으로 적재되어야 하고, 진행 조회는 그 행을 건너뛰어야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class BatchSkipRecordIT {

    @Autowired private BatchStatusService statusService;
    @Autowired private LsBatchProcLogRepository repository;

    private List<LsBatchProcLog> rowsOf(Long rawSn) {
        return repository.findAll().stream()
                .filter(l -> rawSn.equals(l.getRawSn()))
                .toList();
    }

    @Test
    @DisplayName("VLM_SKIPPED_감사행은_다음_단계_전이에_덮이지_않고_보존된다")
    void skippedRowSurvivesNextStageTransition() {
        Long rawSn = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);

        // given — VLM 단계 진입(진행 행 생성) 후 비활성으로 skip 기록
        statusService.markStage(rawSn, BatchStage.VLM);
        statusService.recordVlmSkipped(rawSn, "VLM 비활성(vlm.client.enabled=false)");

        // when — 파이프라인이 다음 단계로 전진
        statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);

        // then — 감사행 보존 + 진행 행은 FRAME_EXTRACT 로 전진
        List<LsBatchProcLog> rows = rowsOf(rawSn);
        assertThat(rows)
                .as("VLM SKIPPED 감사행이 사유와 함께 남아야 한다")
                .anySatisfy(l -> {
                    assertThat(l.getStageCd()).isEqualTo(BatchStage.VLM.name());
                    assertThat(l.getProcSttsCd()).isEqualTo("SKIPPED");
                    assertThat(l.getErrMsg()).contains("vlm.client.enabled");
                });
        assertThat(statusService.currentStage(rawSn))
                .as("진행 상태 조회는 감사행을 건너뛰고 최신 진행 단계를 봐야 한다")
                .isEqualTo(BatchStage.FRAME_EXTRACT);
        assertThat(statusService.stagesFor(rawSn, false))
                .as("진행률 표시도 감사행에 영향받지 않는다")
                .anySatisfy(s -> {
                    assertThat(s.name()).isEqualTo(BatchStage.FRAME_EXTRACT.name());
                    assertThat(s.status()).isEqualTo(BatchStageProgressMapper.PROGRESS);
                });
    }
}
