package kr.co.cudo.authoring.dataset.export.listener;

import kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link DatasetExportBridge} 빈 활성 조건(@ConditionalOnProperty) 검증.
 *
 * <p>토글 {@code authoring.dataset-export.enabled}:
 * <ul>
 *   <li>true → 브릿지 로드(승인 이벤트 소비).</li>
 *   <li>false → 미로드(산출 리스너 미동작).</li>
 *   <li>미설정(기본) → 로드({@code matchIfMissing=true}).</li>
 * </ul>
 */
class DatasetExportBridgeBeanConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestBeans.class);

    @Test
    @DisplayName("enabled_true면_DatasetExportBridge_빈이_로드된다")
    void loadsWhenEnabledTrue() {
        runner.withPropertyValues("authoring.dataset-export.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(DatasetExportBridge.class));
    }

    @Test
    @DisplayName("enabled_false면_DatasetExportBridge_빈이_로드되지_않는다")
    void doesNotLoadWhenEnabledFalse() {
        runner.withPropertyValues("authoring.dataset-export.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DatasetExportBridge.class));
    }

    @Test
    @DisplayName("미설정_기본에서는_matchIfMissing으로_로드된다")
    void loadsWhenMissingByMatchIfMissing() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(DatasetExportBridge.class));
    }

    @Configuration
    @Import(DatasetExportBridge.class)
    static class TestBeans {
        @Bean
        AsyncDatasetExportRunner asyncDatasetExportRunner() {
            return mock(AsyncDatasetExportRunner.class);
        }
    }
}
