package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.common.client.DeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 4 / UC018 — KPST 토글 OFF(기본) 회귀 통합 테스트.
 *
 * <p>{@code kpst.deid.enabled=false} 면 KPST 폴링 빈({@link KpstDeidentService})이 등록되지 않고
 * {@link DeidentifyStep} 이 기존 동기 비식별 경로(콜백/즉시 Y 전이)를 유지함을 검증한다(회귀 방지).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "kpst.deid.enabled=false"
})
class DeidentifyStepKpstDisabledIntegrationTest {

    @MockBean
    private DeidentifyClient deidentifyClient;

    @Autowired
    private DeidentifyStep deidentifyStep;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private ApplicationContext applicationContext;
    @org.springframework.beans.factory.annotation.Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    @Test
    @DisplayName("통합_enabled_false면_KPST폴링빈_미등록_기존_동기_비식별_경로유지")
    void kpstDisabledKeepsSynchronousDeidentify() throws Exception {
        // given — KPST 폴링 빈이 컨텍스트에 없어야 한다(토글 OFF).
        assertThat(applicationContext.getBeanNamesForType(KpstDeidentService.class)).isEmpty();

        // 비식별 결과는 storage.deidentified-path 하위에 저장되어야 경로 검증을 통과한다.
        Path deidBase = Path.of(deidPath).toAbsolutePath().normalize();
        Path resultPath = deidBase.resolve("videos").resolve("sync").resolve("deidentified.mp4");
        Files.createDirectories(resultPath.getParent());
        Files.writeString(resultPath, "MASKED");
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("success", resultPath.toString())));

        Path rawFile = Files.createTempFile("sync-raw", ".mp4");
        Files.writeString(rawFile, "raw");
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "clip-sync-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60));

        // when — 동기 경로 실행
        String returned = deidentifyStep.run(raw);

        // then — 동기 비식별 즉시 Y 전이 (폴링 위탁 경로가 아니라 결과 경로 반환)
        assertThat(returned).isEqualTo(resultPath.toString());
        assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
    }
}
