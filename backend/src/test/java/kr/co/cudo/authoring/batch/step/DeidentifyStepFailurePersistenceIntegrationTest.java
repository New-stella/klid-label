package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 라이브 검증 결함 회귀 — DeidentifyStep 실패 경로의 {@code DE_IDENT_YN='F'} 영속 검증.
 *
 * <p>UC018 경로 단일화 후 레거시 동기 SPI 경로는 제거되었으므로, 본 테스트는 <b>local mock 경로</b>
 * (원본 부재 → 실패)에서 실패 기록이 REQUIRES_NEW 롤백과 독립 커밋되는지 검증한다.
 *
 * <p><b>근본 원인</b>: {@code runMock()} 은 {@code REQUIRES_NEW} 트랜잭션이다. 실패 분기에서 인라인으로
 * {@code markDeidentified("F")} + {@code procLog.fail(...)} 한 직후 {@code CustomException} 을 던지면,
 * RuntimeException 전파로 Spring 이 그 트랜잭션을 전체 롤백한다 → 'F' 와 FAIL 기록이 함께 롤백된다.
 *
 * <p>수정 방향: 실패 기록을 별도 빈({@code BatchTransitionService})의 {@code REQUIRES_NEW} 메서드로
 * 위임해 독립 커밋한다. 본 테스트는 {@code run()} 이 던진 뒤 별도 조회 트랜잭션에서 'F' 가 살아있는지
 * (=커밋됨)를 단언한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 테스트 기본(application-local.yml): kpst.deid.enabled=false + deidentify.mock-mode=true → mock 경로.
        "authoring.integration.deidentify.mock-mode=true"
})
class DeidentifyStepFailurePersistenceIntegrationTest {

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
    @DisplayName("mock_원본부재시_DE_IDNTF_YN이_F로_DB에_영속된다 — REQUIRES_NEW 롤백과 독립 커밋")
    void missingSource_persistsFFlag() {
        // given — 실제 적재된 영상(deIdntfYn='N', PENDING). 원본 파일은 존재하지 않는 경로.
        String missing = "/var/raw/no-such-" + System.nanoTime() + ".mp4";
        LsDataRaw raw = saveRaw(missing);
        Long rawSn = raw.getRawSn();

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
    @DisplayName("mock_실패시_procLog에_FAIL_기록이_DB에_영속된다")
    void missingSource_persistsProcLogFail() {
        // given
        String missing = "/var/raw/no-such-" + System.nanoTime() + ".mp4";
        LsDataRaw raw = saveRaw(missing);
        Long rawSn = raw.getRawSn();

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
        assertThat(failLog.getErrorMsg()).doesNotContain(missing);
        assertThat(failLog.getErrorMsg()).doesNotContain("/var/raw");
    }

    @Test
    @DisplayName("mock_성공경로_회귀 — 원본 존재 시 Y 전이 + 비식별경로 복사(무변경)")
    void successPath_regression() throws Exception {
        // given — 실제 원본 파일 존재.
        Path rawFile = Files.createTempFile("ok-raw", ".mp4");
        Files.writeString(rawFile, "raw-bytes");
        LsDataRaw raw = saveRaw(rawFile.toString());
        Long rawSn = raw.getRawSn();

        // when
        String returned = deidentifyStep.run(raw);

        // then — 비식별 결과가 storage.deidentified-path 하위에 복사되고 Y 전이.
        Path deidBase = Path.of(deidPath).toAbsolutePath().normalize();
        Path expected = deidBase.resolve("videos").resolve(String.valueOf(rawSn)).resolve("deidentified.mp4");
        assertThat(returned).isEqualTo(expected.toString());
        assertThat(Files.readString(expected)).isEqualTo("raw-bytes");
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
    }
}
