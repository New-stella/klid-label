package kr.co.cudo.authoring.version.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「시작 버전 선택」 프레임 상한 설정의 <b>fail-closed 검증</b>.
 *
 * <p>이 설정이 무너지면 기능이 통째로 죽거나(0 이하 = 모든 요청 거부) 상한이 사라진다. 이 저장소에는
 * {@code .env.example} 의 <b>빈 값</b>이 {@code ${KEY:default}} 를 무력화해 운영에서만 조용히 다르게
 * 동작한 실사고가 있어, 경고가 아니라 <b>기동 차단</b>으로 고정한다.
 *
 * @design D5
 * @req R6
 */
class StartVersionPropertiesTest {

    @Test
    @DisplayName("프레임_상한이_0이하면_기동을_거부한다")
    void 프레임_상한이_0이하면_기동을_거부한다() {
        assertThatThrownBy(() -> new StartVersionProperties(0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-frames");
        assertThatThrownBy(() -> new StartVersionProperties(-1).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("정상값은_통과한다")
    void 정상값은_통과한다() {
        new StartVersionProperties(StartVersionProperties.MIN_MAX_FRAMES).validate();
        new StartVersionProperties(2000).validate();
    }

    @Test
    @DisplayName("미설정이면_기본_상한_2000이_바인딩된다")
    void 미설정이면_기본_상한이_바인딩된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .run(ctx -> assertThat(ctx.getBean(StartVersionProperties.class).maxFrames())
                        .isEqualTo(2000));
    }

    @Test
    @DisplayName("설정_바인딩_경로에서도_0은_기동_실패다 — 검증이_실제로_배선돼_있다")
    void 설정_바인딩_경로에서도_0은_기동_실패다() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues("authoring.version.start-version.max-frames=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @EnableConfigurationProperties(StartVersionProperties.class)
    static class Config {
    }
}
