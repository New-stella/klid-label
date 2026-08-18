package kr.co.cudo.authoring.portal.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 포털 보존기간 만료 삭제 스윕 스케줄러 활성화 설정 — {@code portal.retention.sweep.enabled}.
 *
 * <h3>업로드 스윕과 <b>별개 토글</b>인 것은 의도다</h3>
 * <p>{@link PortalRetentionSweepJob} 은 사용자 데이터를 <b>비가역으로 지운다</b>. 정리 성격의
 * {@link PortalUploadSweepJob} 과 같은 스위치에 묶으면 한쪽을 끄려다 다른 쪽까지 끄게 되고,
 * 그 잘못은 "지워지지 않는다"(고아 누적) 또는 "지워진다"(데이터 소실) 어느 방향으로든 조용하다.
 * 잡 자체가 이미 주기·실패 격리를 분리해 둔 것과 같은 이유다.
 *
 * <p>{@code @EnableScheduling} 이 두 곳에 붙는 것은 문제가 아니다 — Spring 이 같은
 * {@code SchedulingConfiguration} 을 한 번만 등록하며, 이 저장소에도 이미 두 개
 * ({@code WorkLockSweepConfig} / {@code ControlNotifySchedulingConfig})가 공존해 왔다.
 *
 * <p>운영 기본은 {@code application.yml} 의 {@code true}. 회귀 고정: {@code PortalSweepSchedulingIT}.
 */
@Configuration
@ConditionalOnProperty(name = "portal.retention.sweep.enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class PortalRetentionSweepSchedulingConfig {
}
