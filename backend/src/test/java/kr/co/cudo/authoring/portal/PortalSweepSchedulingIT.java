package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.scheduler.PortalRetentionSweepJob;
import kr.co.cudo.authoring.portal.scheduler.PortalUploadSweepJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 스윕 두 잡이 <b>자기 전용 토글로</b> 스케줄링된다는 것을 고정한다. @design DFEAT-055, AC-036
 *
 * <h3>이 가드가 막는 결함의 성질 — 아무도 실패하지 않는다</h3>
 * <p>과거 두 잡은 자기 {@code @EnableScheduling} 없이 <b>남의 기능</b>이 켜 둔 스케줄링에 얹혀서
 * 돌았다({@code WorkLockSweepConfig} / {@code ControlNotifySchedulingConfig}). 그래서 관제 통지처럼
 * <b>포털과 아무 상관 없는 기능</b>을 끄면 보존기간 삭제·고착 마감이 <b>소리 없이 멈췄다</b> —
 * 예외도, 실패하는 테스트도, 로그도 남지 않는다. "켜져 있어야 할 것이 안 도는" 결함이라
 * <b>이 가드가 없으면 재발해도 아무도 모른다</b>.
 *
 * <h3>그래서 무관 토글을 전부 끈 상태로 검증한다</h3>
 * <p>{@code authoring.control-notify.enabled=false} + {@code authoring.work-lock.sweep.enabled=false}
 * (= 스케줄링을 켜 주던 두 축을 모두 제거)에서도 포털 두 잡이 <b>실제로 스케줄 등록</b>되는지를
 * {@link ScheduledTaskHolder} 로 확인한다. 빈 존재만 보면 부족하다 — 빈은 있는데
 * {@code @EnableScheduling} 이 없어 {@code @Scheduled} 가 무시되는 것이 정확히 그 결함이었기 때문이다.
 *
 * <p>발화 자체는 테스트 yml 이 최초 지연을 24시간으로 밀어 막는다(등록은 되고 실행은 안 된다).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 포털 스윕이 <자기 토글로> 서는지 보는 것이 목적이므로, 스케줄링을 켜 주던 남의 축은 전부 끈다.
        "authoring.control-notify.enabled=false",
        "authoring.work-lock.sweep.enabled=false",
        "portal.upload.sweep.enabled=true",
        "portal.retention.sweep.enabled=true",
})
class PortalSweepSchedulingIT {

    @Autowired private ObjectProvider<ScheduledTaskHolder> scheduledTaskHolder;

    @Test
    @DisplayName("관제통지와_작업락_스윕을_모두_꺼도_포털_스윕_두_잡이_스케줄에_등록된다")
    void portalSweepsAreScheduledWithoutUnrelatedToggles() {
        ScheduledTaskHolder holder = scheduledTaskHolder.getIfAvailable();
        assertThat(holder)
                .as("@EnableScheduling 이 없으면 이 빈 자체가 없다 — 포털 스윕이 남의 토글에 얹혀 있다는 뜻")
                .isNotNull();

        Set<String> scheduledTargets = holder.getScheduledTasks().stream()
                .map(PortalSweepSchedulingIT::targetClassName)
                .filter(name -> name != null)
                .collect(Collectors.toSet());

        assertThat(scheduledTargets)
                .as("포털 업로드 스윕(고착 자산 FAILED 마감 + 만료 TUS 세션 정리)이 스케줄에 없다")
                .contains(PortalUploadSweepJob.class.getName());
        assertThat(scheduledTargets)
                .as("포털 보존기간 스윕(만료 자산·라벨 삭제)이 스케줄에 없다")
                .contains(PortalRetentionSweepJob.class.getName());
    }

    /** {@code @Scheduled} 로 등록된 태스크의 대상 빈 클래스명(프록시면 원본 클래스). */
    private static String targetClassName(ScheduledTask scheduledTask) {
        Task task = scheduledTask.getTask();
        if (task.getRunnable() instanceof ScheduledMethodRunnable runnable) {
            return org.springframework.aop.support.AopUtils.getTargetClass(runnable.getTarget()).getName();
        }
        return null;
    }
}
