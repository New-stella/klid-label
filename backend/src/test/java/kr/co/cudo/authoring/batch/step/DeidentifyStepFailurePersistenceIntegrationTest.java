package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.DeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 라이브 검증 결함 회귀 — DeidentifyStep 실패 경로의 {@code DE_IDENT_YN='F'} 영속 검증.
 *
 * <p><b>근본 원인</b>: {@code run()}/{@code runMock()} 은 {@code REQUIRES_NEW} 트랜잭션이다.
 * 실패 분기에서 인라인으로 {@code markDeidentified("F")} + {@code procLog.fail(...)} 한 직후
 * {@code CustomException} 을 던지면, RuntimeException 전파로 Spring 이 그 트랜잭션을 전체
 * 롤백한다 → 'F' 와 FAIL 기록이 함께 롤백되어 DB 에는 {@code DE_IDENT_YN='N'} 만 남는다.
 *
 * <p>따라서 <b>mock-verify 로는 잡히지 않는다</b>(메모리상 엔티티엔 'F' 가 찍혀 보임). 실제
 * 트랜잭션 경계 위에서만 롤백을 재현할 수 있어 Testcontainers PG + 실제
 * controlTransactionManager 로 검증한다.
 *
 * <p>수정 방향: 실패 기록을 {@code run()} 의 REQUIRES_NEW 롤백과 독립적으로 커밋되는 별도 빈의
 * {@code REQUIRES_NEW} 메서드로 위임한다. 본 테스트는 {@code run()} 이 던진 뒤 <b>별도 조회
 * 트랜잭션</b>(findById)에서 'F' 가 살아있는지(=커밋됨)를 단언한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "kpst.deid.enabled=false"
})
class DeidentifyStepFailurePersistenceIntegrationTest {

    @MockBean
    private DeidentifyClient deidentifyClient;

    @Autowired
    private DeidentifyStep deidentifyStep;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private LsDeidentProcLogRepository procLogRepository;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    private LsDataRaw saveRaw(String filePath) {
        return videoRepository.save(LsDataRaw.createFromIngest(
                "clip-fail-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, filePath, null, 60));
    }

    @Test
    @DisplayName("외부비식별_호출실패시_DE_IDNTF_YN이_F로_DB에_영속된다 — REQUIRES_NEW 롤백과 독립 커밋")
    void externalFailure_persistsFFlag() throws Exception {
        // given — 실제 적재된 영상(deIdntfYn='N', PENDING)
        Path rawFile = Files.createTempFile("ext-fail-raw", ".mp4");
        Files.writeString(rawFile, "raw");
        LsDataRaw raw = saveRaw(rawFile.toString());
        Long rawSn = raw.getRawSn();

        // 외부 비식별 호출이 빈 응답(resultPath=null) → IllegalStateException → 실패 분기
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("FAIL", null)));

        // when — run() 실패(CustomException). run() 의 REQUIRES_NEW 는 롤백됨.
        assertThatThrownBy(() -> deidentifyStep.run(raw))
                .isInstanceOf(CustomException.class);

        // then — 별도 조회 트랜잭션에서 'F' 가 커밋되어 살아있어야 한다(영속). 롤백되면 'N'.
        LsDataRaw reloaded = videoRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.getDeIdntfYn()).isEqualTo("F");
        // MARKING_READY 미전이(성공 위장 0) — 적재 직후 PENDING 유지.
        assertThat(reloaded.getDataSttsCd()).isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("실패시_procLog에_FAIL_기록이_DB에_영속된다")
    void externalFailure_persistsProcLogFail() throws Exception {
        // given
        Path rawFile = Files.createTempFile("ext-fail-proclog", ".mp4");
        Files.writeString(rawFile, "raw");
        LsDataRaw raw = saveRaw(rawFile.toString());
        Long rawSn = raw.getRawSn();

        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("FAIL", null)));

        // when
        assertThatThrownBy(() -> deidentifyStep.run(raw))
                .isInstanceOf(CustomException.class);

        // then — 커밋된 FAIL procLog 레코드가 존재(REQUESTED 레코드는 run T1 롤백으로 사라짐).
        List<LsDeidentProcLog> logs = procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn);
        assertThat(logs).isNotEmpty();
        assertThat(logs).anyMatch(l -> LsDeidentProcLog.FAILED.equals(l.getProcSttsCd()));
        // 보안(CWE-209): 에러 메시지에 원본경로/PII/외부원문 미노출 — 고정 코드/예외클래스명만.
        LsDeidentProcLog failLog = logs.stream()
                .filter(l -> LsDeidentProcLog.FAILED.equals(l.getProcSttsCd()))
                .findFirst().orElseThrow();
        assertThat(failLog.getErrorMsg()).doesNotContain(rawFile.toString());
        assertThat(failLog.getErrorMsg()).doesNotContain("/var/raw");
    }

    @Test
    @DisplayName("성공경로_회귀 — 정상 응답이면 Y 전이 + 결과경로 반환(무변경)")
    void successPath_regression() throws Exception {
        // given
        Path rawFile = Files.createTempFile("ok-raw", ".mp4");
        Files.writeString(rawFile, "raw");
        LsDataRaw raw = saveRaw(rawFile.toString());
        Long rawSn = raw.getRawSn();

        Path deidBase = Path.of(deidPath).toAbsolutePath().normalize();
        Path resultPath = deidBase.resolve("videos").resolve(String.valueOf(rawSn)).resolve("deidentified.mp4");
        Files.createDirectories(resultPath.getParent());
        Files.writeString(resultPath, "MASKED");
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", resultPath.toString())));

        // when
        String returned = deidentifyStep.run(raw);

        // then — 무변경 성공 회귀
        assertThat(returned).isEqualTo(resultPath.toString());
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
    }
}
