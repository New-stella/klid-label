package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
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
        // Phase 5A — co-locate 산출 base 허용 마운트 루트(원본 영상이 이 하위에 있어야 한다)
        "authoring.storage.raw-mount-roots=" + ArtifactRootTestSupport.IT_MOUNT_ROOT,
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

    @Test
    @DisplayName("통합_enabled_false면_KPST폴링빈_미등록_mock_비식별_경로유지")
    void kpstDisabledKeepsMockDeidentify() throws Exception {
        // given — KPST 폴링 빈이 컨텍스트에 없어야 한다(토글 OFF).
        assertThat(applicationContext.getBeanNamesForType(KpstDeidentService.class)).isEmpty();

        // 실제 원본 파일 존재(허용 마운트 루트 하위) — mock 경로가 co-locate 비식별 경로로 복사한다.
        Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("mock-raw");
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "clip-mock-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60));

        // when — mock 비식별 경로 실행
        DeidentResult returned = deidentifyStep.run(raw);

        // then — 비식별 결과가 co-locate 위치(dirname(원본)/{rawSn}/deid/)에 복사되고 즉시 Y 전이.
        Path expected = ArtifactRootTestSupport.expectedMockDeidPath(rawFile, raw.getRawSn());
        assertThat(returned.completed()).isTrue();
        assertThat(returned.deidFilePath()).isEqualTo(expected.toString());
        assertThat(Files.readString(expected)).isEqualTo("raw-bytes");
        assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
    }
}
