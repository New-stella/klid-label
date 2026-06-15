package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link BatchDevTriggerController} 프로파일 게이팅 검증 — 운영(prd) 미노출.
 *
 * <p>컨트롤러는 {@code @Profile("!prd")} 라 prd 프로파일에서는 빈(따라서 scan 포함 모든
 * dev 트리거 엔드포인트)이 등록되지 않아야 한다. 실제 DB 부팅을 피하기 위해
 * {@link ApplicationContextRunner} 슬림 컨텍스트로 의존 빈을 mock 주입하고 빈 등록만 본다.
 */
class BatchDevTriggerControllerProfileTest {

    private ApplicationContextRunner runnerWith(String... props) {
        return new ApplicationContextRunner()
                .withUserConfiguration(BatchDevTriggerController.class)
                .withBean(BatchOrchestrator.class, () -> mock(BatchOrchestrator.class))
                .withBean(VideoRepository.class, () -> mock(VideoRepository.class))
                .withBean(TrainingVideoIngestService.class, () -> mock(TrainingVideoIngestService.class))
                .withPropertyValues(props);
    }

    @Test
    @DisplayName("local_프로파일에서는_BatchDevTriggerController_빈_등록")
    void controllerPresentWhenLocal() {
        runnerWith("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasSingleBean(BatchDevTriggerController.class));
    }

    @Test
    @DisplayName("prd_프로파일에서는_scan_엔드포인트_빈이_미등록된다")
    void controllerAbsentWhenPrd() {
        runnerWith("spring.profiles.active=prd")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BatchDevTriggerController.class));
    }
}
