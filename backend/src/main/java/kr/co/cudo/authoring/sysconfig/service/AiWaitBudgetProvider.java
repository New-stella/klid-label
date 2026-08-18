package kr.co.cudo.authoring.sysconfig.service;

import kr.co.cudo.authoring.common.client.AiWaitBudgetPolicy;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.dto.AiWaitBudgets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 운영자 설정을 반영한 <b>실효</b> AI 대기 예산 공급 — 도출은 {@link AiWaitBudgetPolicy}, 설정은 여기.
 *
 * <h3>왜 정책과 나눠 두는가</h3>
 * <p>{@link SystemConfigService} 가 저장 시점 하한 검증을 하려면 정책을 알아야 하는데, 정책이 설정을
 * 직접 읽으면 순환 의존이 된다. 그래서 <b>순수 도출(정책)</b> 과 <b>설정 반영(여기)</b> 을 나눈다.
 *
 * <h3>읽기 시점에도 하한을 강제한다 (두 겹의 두 번째)</h3>
 * <p>저장 검증만으로는 부족하다 — 검증 규칙이 나중에 바뀌거나(재시도 예산을 늘리면 하한이 함께
 * 올라간다) 다른 경로로 값이 들어오면 <b>이미 저장된 값</b>이 하한 아래로 남을 수 있다. 그 상태에서는
 * 설정 하나 때문에 정상 동작이 다시 "AI 실패"로 보인다. 그래서 읽을 때 하한으로 끌어올려 쓴다.
 *
 * <p><b>모자란 값은 올리고, 넘치는 값은 그대로 둔다.</b> 상한을 넉넉히 준 것은 운영자의 선택이고
 * 그 방향으로는 이 결함이 재발하지 않는다(다만 앞단이 먼저 끊을 수는 있다 — 그건 앞단의 문제로
 * 남겨 두는 것이 맞다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiWaitBudgetProvider {

    private final SystemConfigService systemConfigService;
    private final AiWaitBudgetPolicy policy;

    /** 화면에 내려보낼 4종 예산 — 실효 절대 상한이 반영된 값. */
    public AiWaitBudgets budgets() {
        return policy.budgets(ceilingSeconds());
    }

    /**
     * 실효 절대 상한(초) — 설정이 있으면 설정, 없거나 읽히지 않으면 도출 기본값. 하한 미만은 끌어올린다.
     *
     * <p>조회에 {@code getInt} 가 아니라 {@link SystemConfigService#findString} 을 쓰는 이유: 이 키는
     * <b>시드하지 않는 것이 설계</b>라 행이 없는 상태가 정상인데, {@code getInt} 는 그때 예외를 던지고
     * Spring 캐시는 <b>예외를 캐시하지 않는다</b> — 정상 배포에서 캐시가 영영 채워지지 않아 호출마다
     * DB 를 왕복한다. {@code findString} 은 <b>부재도 캐시</b>한다.
     */
    public int ceilingSeconds() {
        int floor = policy.minimumCeilingSeconds();
        int configured = configuredCeiling().orElse(AiWaitBudgetPolicy.DEFAULT_CEILING_SEC);
        if (configured < floor) {
            // 값 자체는 운영 파라미터라 로그에 남겨도 되지만, 왜 무시했는지가 더 중요하다.
            log.warn("[AiWaitBudget] 설정된 절대 상한 {}초가 도출 하한 {}초보다 작아 하한을 적용한다"
                    + " — 이대로 두면 정상 추론이 실패로 보인다", configured, floor);
            return floor;
        }
        return configured;
    }

    /** 저장된 상한. 행이 없거나 정수로 읽히지 않으면 {@link Optional#empty()}(기본값으로 폴백). */
    private Optional<Integer> configuredCeiling() {
        try {
            return systemConfigService.findString(ConfigKeys.AI_WAIT_BUDGET_CEILING_SEC)
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .map(Integer::parseInt);
        } catch (NumberFormatException e) {
            // 저장 검증을 통과했다면 나올 수 없다 — 수기 수정·마이그레이션으로 깨진 값 대비(fail-safe).
            log.warn("[AiWaitBudget] 절대 상한 설정값을 정수로 읽지 못해 기본값을 쓴다 key={}",
                    ConfigKeys.AI_WAIT_BUDGET_CEILING_SEC);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("[AiWaitBudget] 절대 상한 설정 조회에 실패해 기본값을 쓴다 key={}",
                    ConfigKeys.AI_WAIT_BUDGET_CEILING_SEC);
            return Optional.empty();
        }
    }
}
