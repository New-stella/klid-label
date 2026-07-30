package kr.co.cudo.authoring.dataset.controller;

import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaBackfillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link DatasetVideoMetaBackfillDevController} 노출 게이팅 검증 — 운영(prd) 미노출 / 로컬 노출.
 *
 * <p>컨트롤러는 {@code BatchDevTriggerController} 와 동일하게 {@code @Profile("!prd")} 라 운영 표식
 * 프로파일에서는 빈(따라서 dry-run·실행 두 엔드포인트 전부)이 등록되지 않아야 한다. 반대로 이 작업의
 * 목적인 <b>로컬 검증 경로</b>가 열려 있어야 하므로 local 에서는 반드시 등록돼야 한다.
 *
 * <p>실제 DB 부팅을 피하려 {@link ApplicationContextRunner} 슬림 컨텍스트로 의존 빈만 mock 주입하고
 * 빈 등록 여부만 본다(선례: {@code BatchDevTriggerControllerProfileTest}).
 */
class DatasetVideoMetaBackfillDevControllerProfileTest {

    private ApplicationContextRunner runnerWith(String... props) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DatasetVideoMetaBackfillDevController.class)
                .withBean(DatasetVideoMetaBackfillService.class,
                        () -> mock(DatasetVideoMetaBackfillService.class))
                .withPropertyValues(props);
    }

    @Test
    @DisplayName("local_프로파일에서는_백필_트리거_컨트롤러가_등록된다")
    void 로컬에서는_등록된다() {
        runnerWith("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasSingleBean(DatasetVideoMetaBackfillDevController.class));
    }

    @Test
    @DisplayName("dev_프로파일에서는_백필_트리거_컨트롤러가_등록된다")
    void dev에서는_등록된다() {
        runnerWith("spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasSingleBean(DatasetVideoMetaBackfillDevController.class));
    }

    @Test
    @DisplayName("prd_프로파일에서는_백필_트리거_엔드포인트가_미등록된다")
    void 운영에서는_미등록된다() {
        runnerWith("spring.profiles.active=prd")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DatasetVideoMetaBackfillDevController.class));
    }
}
