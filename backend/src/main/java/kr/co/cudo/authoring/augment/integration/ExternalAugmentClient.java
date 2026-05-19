package kr.co.cudo.authoring.augment.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase 9 — 외부 SFR-07 증강 시스템 결정 동기화 (placeholder).
 *
 * <p>V1.5 정책: 증강 본체는 외부 시스템 책임이며, 본 저작도구는 검수 결과(ACCEPT/REJECT)를
 * 외부에 통보만 한다. 외부 미연결 시(현 단계) mock 응답으로 동작하며, 본 트랜잭션은
 * 외부 실패 영향을 받지 않는다 (best-effort).
 */
@Slf4j
@Component
public class ExternalAugmentClient {

    /**
     * 외부 시스템에 검수 결정을 통보. 실패 시 로그만 남기고 호출자 트랜잭션은 유지.
     *
     * @return 외부 시스템 ack 여부 (현재는 항상 true — mock)
     */
    public boolean syncDecision(Long dataAugSn, String decision, String reasonOrNull) {
        // 외부 SFR-07 시스템 미연결 — mock 응답.
        // 실제 연동 시 WebClient + Resilience4j 적용 예정.
        log.info("[Augment] external decision sync (mock) dataAugSn={} decision={} reason={}",
                dataAugSn, decision, reasonOrNull == null ? "" : reasonOrNull);
        return true;
    }

    /**
     * 외부 시스템에 증강 요청을 전달 (placeholder).
     *
     * <p>V1.5 정책: 증강 본체는 외부 SFR-07 시스템이며, 본 저작도구는 검수 완료 영상 목록과
     * 증강 유형만 통보한다. 외부 미연결 시(현 단계) ack 응답을 mock 으로 반환하며,
     * 호출자 트랜잭션은 외부 실패 영향을 받지 않는다 (best-effort).
     *
     * @param videoIds 검수 완료된 원본 영상 ID 목록
     * @param types    요청 증강 유형 (WINTER/NIGHT/RAIN/RESOLUTION)
     * @return 외부 ack 여부 (현재는 항상 true — mock)
     */
    public boolean requestAugment(java.util.List<Long> videoIds, java.util.List<String> types) {
        // 외부 SFR-07 시스템 미연결 — mock 응답.
        log.info("[Augment] external request (mock) videoCount={} typeCount={}",
                videoIds == null ? 0 : videoIds.size(),
                types == null ? 0 : types.size());
        return true;
    }
}
