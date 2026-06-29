package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UC018 — KPST 토글 OFF 회귀 통합 테스트.
 *
 * <p>{@code kpst.deid.enabled=false} 면 KPST 폴링 빈({@link KpstDeidentService})이 등록되지 않으며,
 * local 자족 환경은 {@code authoring.integration.deidentify.mock-mode=true} 의 mock 비식별 경로로
 * 동작함을 검증한다. 레거시 동기 SPI 경로는 제거되었으므로 mock/KPST 두 경로만 유효하다(회귀 방지).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "kpst.deid.enabled=false",
        "authoring.integration.deidentify.mock-mode=true"
})
class DeidentifyStepKpstDisabledIntegrationTest {

    @Autowired
    private DeidentifyStep deidentifyStep;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private ApplicationContext applicationContext;
    @org.springframework.beans.factory.annotation.Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    @Test
    @DisplayName("통합_enabled_false면_KPST폴링빈_미등록_mock_비식별_경로유지")
    void kpstDisabledKeepsMockDeidentify() throws Exception {
        // given — KPST 폴링 빈이 컨텍스트에 없어야 한다(토글 OFF).
        assertThat(applicationContext.getBeanNamesForType(KpstDeidentService.class)).isEmpty();

        // 실제 원본 파일 존재 — mock 경로가 비식별 경로로 복사한다.
        Path rawFile = Files.createTempFile("mock-raw", ".mp4");
        Files.writeString(rawFile, "raw");
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "clip-mock-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60));

        // when — mock 비식별 경로 실행
        DeidentResult returned = deidentifyStep.run(raw);

        // then — 비식별 결과가 storage.deidentified-path 하위에 복사되고 즉시 Y 전이(동기 완료).
        Path deidBase = Path.of(deidPath).toAbsolutePath().normalize();
        Path expected = deidBase.resolve("videos").resolve(String.valueOf(raw.getRawSn()))
                .resolve("deidentified.mp4");
        assertThat(returned.completed()).isTrue();
        assertThat(returned.deidFilePath()).isEqualTo(expected.toString());
        assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
    }
}
