package kr.co.cudo.authoring.dev.controller;

import kr.co.cudo.authoring.dev.service.DeidentFrameRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link DeidentFrameRecoveryDevController} 노출 게이팅 — 운영(prd) 미노출 / local·dev 노출.
 *
 * <p>이 API 는 프레임 행을 직접 고치는 <b>운영 복구 도구</b>라 운영 환경에는 엔드포인트가 아예
 * 존재하면 안 된다({@code @Profile("!prd")}). 반대로 복구를 수행할 dev·local 에서는 반드시 열려야
 * 하므로 양방향을 함께 고정한다(선례: {@code DatasetVideoMetaBackfillDevControllerProfileTest}).
 *
 * <p>실 DB 부팅을 피해 슬림 컨텍스트로 빈 등록 여부만 본다.
 */
class DeidentFrameRecoveryDevControllerProfileTest {

    private ApplicationContextRunner runnerWith(String... props) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DeidentFrameRecoveryDevController.class)
                .withBean(DeidentFrameRecoveryService.class, () -> mock(DeidentFrameRecoveryService.class))
                .withPropertyValues(props);
    }

    @Test
    @DisplayName("local_프로파일에서는_복구_컨트롤러가_등록된다")
    void 로컬에서는_등록된다() {
        runnerWith("spring.profiles.active=local")
                .run(ctx -> assertThat(ctx).hasSingleBean(DeidentFrameRecoveryDevController.class));
    }

    @Test
    @DisplayName("dev_프로파일에서는_복구_컨트롤러가_등록된다")
    void dev에서는_등록된다() {
        runnerWith("spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasSingleBean(DeidentFrameRecoveryDevController.class));
    }

    @Test
    @DisplayName("prd_프로파일에서는_복구_엔드포인트가_미등록된다")
    void 운영에서는_미등록된다() {
        runnerWith("spring.profiles.active=prd")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DeidentFrameRecoveryDevController.class));
    }
}
