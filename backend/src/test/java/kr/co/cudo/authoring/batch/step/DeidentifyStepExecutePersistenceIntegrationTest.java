package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적재 경로(execute → run) mock 비식별의 <b>실제 영속</b> 회귀 검증 (DEV_FIX — self-invocation 트랜잭션 부재).
 *
 * <p><b>근본 원인</b>: {@code AsyncDeidentifyRunner.runAsync}(적재 경로)는 {@code @Transactional} 이 없고,
 * {@link DeidentifyStep#execute(BatchContext)} 가 {@code run()} 을 <b>self-invocation</b> 으로 호출했다.
 * {@code run()} 은 {@code @Transactional(REQUIRES_NEW)} 이지만 자기호출은 Spring 프록시를 우회해
 * <b>트랜잭션이 열리지 않는다</b>. 그 결과 mock 경로의 {@code markDeidentified("Y")}(dirty-check) 와
 * {@code procLog.succeed(...)} 가 DB 에 반영되지 않아 — DE_IDNTF_YN='N' + procLog REQUESTED 잔존 — 인데도
 * 호출자는 completed=true 로 보고 MARKING_READY 로 전이해 R1 위반(미비식별 영상 마킹 진입)이 발생했다.
 *
 * <p>본 테스트는 <b>실제 Spring 빈(프록시)</b> 의 {@code execute(ctx)} 를 호출한 뒤, AsyncDeidentifyRunner
 * 와 동일하게 완료 신호일 때만 {@code markRawDataMarkingReady} 를 적용하고, <b>별도 조회 트랜잭션</b>에서
 * DE_IDNTF_YN='Y' + procLog SUCCEEDED + dataSttsCd=MARKING_READY 가 <b>함께</b> 커밋됐는지 단언한다.
 * 수정 전에는 실패(RED), 수정 후에는 통과(GREEN)해야 한다.
 *
 * <p>기존 {@code DeidentifyStepFailurePersistenceIntegrationTest} 는 {@code run()} 직접 호출이라
 * self-invocation 경로를 타지 않으므로 본 버그를 잡지 못한다 — 그래서 execute() 경유로 작성한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // Phase 5A — co-locate 산출 base 허용 마운트 루트(원본 영상이 이 하위에 있어야 한다)
        "authoring.storage.raw-mount-roots=" + ArtifactRootTestSupport.IT_MOUNT_ROOT,
        "authoring.integration.deidentify.mock-mode=true"
})
class DeidentifyStepExecutePersistenceIntegrationTest {

    @Autowired
    private DeidentifyStep deidentifyStep;
    @Autowired
    private BatchTransitionService batchTransitionService;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private LsDeidentProcLogRepository procLogRepository;

    private LsDataRaw saveRaw(String filePath) {
        return videoRepository.save(LsDataRaw.createFromIngest(
                "clip-exec-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, filePath, null, 60));
    }

    @Test
    @DisplayName("execute_경유_mock비식별이_DB에_영속 — deIdntfYn=Y + procLog SUCCEEDED + MARKING_READY 함께 커밋")
    void executeViaProxy_persistsDeidentified() throws Exception {
        // given — 허용 마운트 루트 하위에 실제 원본 파일이 존재하는 적재 영상(deIdntfYn='N', PENDING).
        Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("exec-raw");
        LsDataRaw raw = saveRaw(rawFile.toString());
        Long rawSn = raw.getRawSn();

        // when — 실제 Spring 빈(프록시)의 execute(ctx) 호출. self-invocation 우회 없이 run() 이
        //        REQUIRES_NEW 로 열려 mock 영속이 커밋되어야 한다. 그 후 AsyncDeidentifyRunner 와
        //        동일하게 완료 신호일 때만 MARKING_READY 전이를 적용한다.
        BatchContext ctx = new BatchContext(rawSn, raw);
        deidentifyStep.execute(ctx);
        if (ctx.isDeidentCompleted()) {
            batchTransitionService.markRawDataMarkingReady(rawSn);
        }

        // then — 별도 조회 트랜잭션에서 세 가지가 함께 커밋되어 있어야 한다.
        assertThat(ctx.isDeidentCompleted()).isTrue();
        LsDataRaw reloaded = videoRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.getDeIdntfYn()).isEqualTo("Y");
        assertThat(reloaded.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);

        List<LsDeidentProcLog> logs = procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn);
        assertThat(logs).isNotEmpty();
        LsDeidentProcLog succeeded = logs.stream()
                .filter(l -> LsDeidentProcLog.SUCCEEDED.equals(l.getProcSttsCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SUCCEEDED procLog 가 커밋되지 않았다"));
        // 미비식별('N') 상태에서 REQUESTED 만 잔존하는 버그가 없어야 한다 — SUCCEEDED 로 마감.
        // Phase 5A — 산출 위치는 co-locate(dirname(원본)/{rawSn}/deid/). procLog 에 적재된 값이 진실원.
        Path expected = ArtifactRootTestSupport.expectedMockDeidPath(rawFile, rawSn);
        assertThat(succeeded.getDeIdntfFilePathNm()).isEqualTo(expected.toString());
        assertThat(Files.exists(expected)).isTrue();
    }
}
