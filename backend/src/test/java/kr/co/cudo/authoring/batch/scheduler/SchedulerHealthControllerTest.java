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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
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
