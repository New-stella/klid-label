package kr.co.cudo.authoring.transfer.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 마킹 일괄 적재 복구 잡 스케줄링 활성화 — {@code authoring.import.marking.recovery.enabled}.
 *
 * <h3>왜 자기 활성화를 갖는가</h3>
 * <p>{@code @Scheduled} 는 어딘가에서 스케줄링이 켜져 있기만 하면 돈다. 남의 기능이 켜 둔 것에 얹히면
 * <b>그 기능을 끄는 순간 이 복구가 소리 없이 멈춘다</b> — 예외도 로그도 실패하는 시험도 남지 않는다.
 * 이 저장소가 이미 그 사고를 겪고 관례로 만들어 두었다(포털 스윕). 그대로 따른다.
 *
 * <p>잡 빈과 <b>같은 키</b>여야 한다. 한쪽만 걸면 반대 방향의 결함이 생긴다 — 잡이 자기 토글과
 * 무관하게 남의 스케줄링 위에서 돈다.
 *
 * <p>시험에서는 이 토글을 내려 스케줄 발화를 막고, 복구 논리는 잡 메서드를 직접 불러 검증한다.
 *
 * @design DOMAIN-017
 * @design AC-1033
 */
@Configuration
@ConditionalOnProperty(name = "authoring.import.marking.recovery.enabled",
        havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class MarkingImportRecoverySchedulingConfig {
}
