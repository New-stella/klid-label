package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

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

    /** 감사 행에 실릴 사유 — 호출자가 정하는 값이라 여기서는 판정용 고정 문자열을 쓴다. */
    private static final String SKIP_REASON = "단계 미수행 사유";

    @Autowired private BatchStatusService statusService;
    @Autowired private LsBatchProcLogRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 시드한 부모 영상 — V146 FK(LS_BATCH_PROC_LOG → LS_DATA_RAW) 충족용. */
    private Long seededRawSn;

    @AfterEach
    void cleanSeededVideo() {
        if (seededRawSn != null) {
            RawVideoFixture.deleteRaws(jdbcTemplate, seededRawSn); // CASCADE 로 배치 로그까지 정리
            seededRawSn = null;
        }
    }

    private List<LsBatchProcLog> rowsOf(Long rawSn) {
        return repository.findAll().stream()
                .filter(l -> rawSn.equals(l.getRawSn()))
                .toList();
    }

    @Test
    @DisplayName("VLM_SKIPPED_감사행은_다음_단계_전이에_덮이지_않고_보존된다")
    void skippedRowSurvivesNextStageTransition() {
        Long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seededRawSn = rawSn;

        // given — VLM 단계 진입(진행 행 생성) 후 비활성으로 skip 기록
        statusService.markStage(rawSn, BatchStage.VLM);
        // 사유는 호출자가 정하는 값이다 — 폐지된 설정 키를 박지 않는다(오해 방지).
        statusService.recordVlmSkipped(rawSn, SKIP_REASON);

        // when — 파이프라인이 다음 단계로 전진
        statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);

        // then — 감사행 보존 + 진행 행은 FRAME_EXTRACT 로 전진
        List<LsBatchProcLog> rows = rowsOf(rawSn);
        assertThat(rows)
                .as("VLM SKIPPED 감사행이 사유와 함께 남아야 한다")
                .anySatisfy(l -> {
                    assertThat(l.getStageCd()).isEqualTo(BatchStage.VLM.name());
                    assertThat(l.getProcSttsCd()).isEqualTo("SKIPPED");
                    assertThat(l.getErrMsg()).isEqualTo(SKIP_REASON);
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
