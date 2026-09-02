package kr.co.cudo.authoring.portal.event;

/**
 * 포털 업로드 영상 <b>마킹 저장 완료</b> 이벤트 — 프레임 추출 트리거 전용.
 *
 * <h3>★ 관제 마킹 완료 이벤트와 별개다 (2026-09-02 순서 반전)</h3>
 * <p>관제의 마킹 완료는 잔여 배치(외부 시계열 위탁 → 프레임 추출 → 오토라벨링)를 깨운다. 포털에는
 * 위탁도 오토라벨링도 <b>없다</b> — 그래서 포털 마킹은 <b>다른 종류의 이벤트</b>를 낸다. 같은 이벤트를
 * 쓰고 소비자에서 채널로 거르는 안은 채택하지 않았다: 거름망을 한 번 빠뜨리면 포털 사용자의 개인
 * 영상이 외부 벤더로 나가는데, 그 실패는 오류로 드러나지 않는다.
 *
 * <p>본 이벤트를 소비하는 것은 {@code PortalFrameExtractBridge} 하나뿐이며, 관제 배치 브리지는 이
 * 타입을 구독하지 않는다(구조적 격리).
 *
 * <h3>구 이벤트 폐기</h3>
 * <p>업로드 완료 직후 고정 간격으로 프레임을 뽑던 구 이벤트({@code PortalVideoUploadedEvent})는
 * 폐기됐다 — 추출 시점이 <b>마킹 저장 직후</b>로 옮겨졌기 때문이다. 되살리면 마킹하지 않은 자산이
 * 프레임을 갖게 되어 「마킹 지점으로만 뽑는다」가 깨진다.
 *
 * @param uldSn     마킹한 업로드 자산 식별자(= {@code LS_DATA_RAW.RAW_SN})
 * @param markingSn 저장된 마킹 식별자
 * @design API-240
 * @design ADR-013
 */
public record PortalMarkingCompletedEvent(Long uldSn, Long markingSn) {
}
