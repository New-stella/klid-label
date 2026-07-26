package kr.co.cudo.authoring.video.scheduler;

import kr.co.cudo.authoring.video.service.ResolutionBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 해상도 파생 백필의 <b>유예 삭제 스윕</b> Job.
 *
 * <p>백필은 이관 후 구 파일을 즉시 지우지 않고 유예 대기열에 올린다(2노드 프로세스 로컬 캐시가 옛 경로를
 * 최대 TTL 동안 계속 해석하므로, 파일이 사라지면 그 노드가 500 을 낸다 —
 * {@code ResolutionBackfillService} 클래스 javadoc 참조). 이 Job 이 유예가 지난 잔존물을 실제로 지운다.
 *
 * <p><b>왜 주기 잡인가</b>: "다음 요청이 올 때 청소"하는 기회적 정리는 트래픽이 끊기면 영원히 돌지 않아
 * 잔존물이 무기한 쌓인다(같은 함정을 앞선 단계에서 두 번 겪었다). 정리를 <b>트래픽과 무관한 스케줄</b>에
 * 묶어 백필을 다시 실행하지 않아도 대기열이 반드시 소진되게 한다. 백필 재실행
 * ({@code ResolutionBackfillService.run}) 도 매번 같은 스윕을 수행하므로 보루가 이중이다.
 *
 * <p>2노드 Active-Active 에서 양 노드가 동시에 발화해도 안전하다 — 스윕은 마커 단위 조건부·멱등이며,
 * 이미 없는 파일/마커는 조용히 넘어간다({@code Files.deleteIfExists}).
 *
 * <p>{@code authoring.resolution-backfill.sweep.enabled=true} 일 때만 빈 등록(local/test 는 override
 * false — 스케줄러 미발화, 테스트는 스윕 메서드를 직접 호출해 검증한다). 예외는 삼켜 스케줄러 스레드를
 * 보호한다({@code WorkLockSweepJob} 패턴).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "authoring.resolution-backfill.sweep.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ResolutionBackfillSweepJob {

    private final ResolutionBackfillService resolutionBackfillService;

    @Scheduled(fixedDelayString = "${authoring.resolution-backfill.sweep.interval-ms:300000}",
               initialDelayString = "${authoring.resolution-backfill.sweep.initial-delay-ms:120000}")
    public void run() {
        try {
            ResolutionBackfillService.StaleSweepResult result =
                    resolutionBackfillService.sweepPendingDeletions();
            if (result.deleted() > 0 || result.pending() > 0) {
                log.info("[ResolutionBackfillSweep] deleted={} pending={}", result.deleted(), result.pending());
            }
        } catch (RuntimeException e) {
            log.error("[ResolutionBackfillSweep] sweep failed reason={}", e.getClass().getSimpleName());
        }
    }
}
