package kr.co.cudo.authoring.portal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 끊긴 등록을 <b>백그라운드로</b> 다시 시작하는 진입점 — 목록 창구가 회복 규칙에 걸리면 부른다.
 *
 * <p>{@code @Async} 는 프록시를 거쳐야 걸리므로 본체({@link PortalDatasetRegistrationService})와 다른 빈이다.
 * 조달과 같은 전용 풀을 쓴다 — 등록도 저장소 복사가 주 비용이라 조달과 자원 성격이 같고, 큐가 차면
 * 호출 스레드에 떠넘기지 않고 거부한다(그 풀의 정책).
 *
 * <p>선점({@code tryClaim})은 <b>호출자가 이미 마쳤다</b> — 큐에서 지연되는 동안 같은 데이터셋의 요청이 또
 * 들어와 중복 착수되지 않게 한다. 여기서는 끝나면 반드시 놓는다.
 *
 * @design ADR-068
 * @design API-253
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalDatasetRegistrationRunner {

    private final PortalDatasetRegistrationService registrationService;

    @Async("portalMaterialsExecutor")
    public void runAsync(long datasetId) {
        try {
            registrationService.registerClaimed(datasetId);
        } catch (RuntimeException e) {
            // 본체는 예외를 올리지 않지만, 올라와도 선점은 반드시 놓는다.
            log.warn("[PortalDataset] 등록 작업이 예외로 끝났습니다 datasetId={} type={}",
                    datasetId, e.getClass().getSimpleName());
        } finally {
            registrationService.release(datasetId);
        }
    }
}
