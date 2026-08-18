package kr.co.cudo.authoring.portal.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 포털 업로드 정리 스윕 스케줄러 활성화 설정 — {@code portal.upload.sweep.enabled}.
 *
 * <h3>왜 이 클래스가 생겼나 (무관 토글 종속)</h3>
 * <p>{@link PortalUploadSweepJob} 은 자기 {@code @EnableScheduling} 없이 <b>남의 기능</b>이 켜 둔
 * 스케줄링에 얹혀 돌고 있었다({@code WorkLockSweepConfig} / {@code ControlNotifySchedulingConfig}).
 * 그래서 관제 통지처럼 <b>포털과 아무 상관 없는 기능</b>을 끄면 만료 TUS 세션 정리와 고착 자산
 * 마감이 <b>소리 없이 멈췄다</b> — 예외도 로그도 실패하는 테스트도 남지 않는다.
 *
 * <p>이 저장소는 이미 그 관례를 갖고 있다({@code WorkLockSweepConfig} 패턴: 기능 플래그 조건부
 * {@code @EnableScheduling} + 같은 키로 잡 빈 게이팅). 새 방식을 만들지 않고 그대로 따른다.
 *
 * <p>운영 기본은 {@code application.yml} 의 {@code true} 이고, 테스트는
 * {@code src/test/resources/application-local.yml} 에서 {@code false} 로 내려 스케줄 발화를 막는다
 * (로직 검증은 잡 메서드를 직접 호출한다). 회귀 고정: {@code PortalSweepSchedulingIT}.
 */
@Configuration
@ConditionalOnProperty(name = "portal.upload.sweep.enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class PortalUploadSweepSchedulingConfig {
}
