package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quartz 스케줄러 기동 + 헬스 엔드포인트 검증.
 *
 * <p><b>opt-in</b>: 테스트 기본값은 {@code spring.quartz.auto-startup=false} 다
 * ({@code src/test/resources/application-local.yml}) — 캐시된 컨텍스트마다 스케줄러 스레드가 상주해
 * 2개짜리 테스트 커넥션 풀을 물고 늘어지는 것을 막기 위함이다. 이 테스트는 <b>스케줄러가 부트 시점에
 * 실제로 시작되는지</b>를 검증 대상으로 삼으므로 여기서만 되켠다(운영 기본값 {@code true} 와 동일 형상).
 *
 * <p>이 {@code @TestPropertySource} 는 컨텍스트 캐시 키를 갈라 <b>전용 컨텍스트 1개</b>를 추가로 만든다.
 * 반대로 전역을 켜두면 컨텍스트 수십 개가 모두 스케줄러를 돌리므로, 1개 증가는 그 대가로 타당하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {"spring.quartz.auto-startup=true"})
class SchedulerHealthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private Scheduler scheduler;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    @Test
    @DisplayName("Quartz_스케줄러가_부트_시점에_시작되고_헬스체크_통과")
    void quartzStartedAndHealthEndpointReturnsUp() throws Exception {
        // 부트 시점에 Scheduler 가 시작되어 있어야 함.
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(scheduler.isShutdown()).isFalse();
        assertThat(scheduler.getSchedulerName()).isNotBlank();

        String reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(get("/v1/system/scheduler/health")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.started").value(true));
    }

    @Test
    @DisplayName("부트스트랩_Job이_등록되어_있음")
    void bootstrapJobRegistered() throws SchedulerException {
        var key = org.quartz.JobKey.jobKey(BootstrapSchedulerJob.JOB_NAME, BootstrapSchedulerJob.JOB_GROUP);
        assertThat(scheduler.checkExists(key)).isTrue();
    }
}
