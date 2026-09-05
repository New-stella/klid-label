package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.marking.dto.MarkItem;

import java.util.List;

/**
 * 마킹 저장이 <b>채널마다 갈리는 지점 전부</b>를 모은 판정기(SPI).
 *
 * <p>{@link kr.co.cudo.authoring.marking.service.MarkingService} 의 오케스트레이션·검증·직렬화·영속은
 * <b>한 벌</b>이고, 아래 메서드만 채널이 각자 답한다. 새 채널이 생기면 이 인터페이스를 구현할 뿐
 * 마킹 로직을 복제하지 않는다.
 *
 * <p>구현은 둘이다 — 관제({@code ControlMarkingChannel})와 포털 업로드
 * ({@code PortalUploadMarkingChannel}).
 *
 * @design ADR-058
 * @design API-240
 */
public interface MarkingChannel extends MarkingChannelGuard {

    /**
     * 마킹 산출·검증에 쓸 영상 길이(초). 미상이면 {@code null}.
     *
     * <p>조달처가 채널마다 다르다 — 관제는 원장·기술메타·직접 프로브 3단 폴백이고(자동은 프로브까지,
     * 수동은 프로브 없이), 포털은 <b>업로드 시 확정한 길이</b>만 쓴다(대화형 저장에 프로브를 태우지
     * 않는다). 포털에서 자동인데 길이를 모르면 지점을 산출할 수 없어 여기서 거절한다.
     *
     * @param mode 요청 마킹 모드(AUTO/MANUAL) — 조달 방식이 모드마다 갈리는 채널이 있다
     */
    Integer resolveDurationSec(Long rawSn, String mode);

    /** 마킹 시점에 고정(pin)할 실 프레임레이트. 미상이면 채널이 폴백값을 정한다. */
    double resolveFps(Long rawSn);

    /**
     * 마킹 행에 남길 <b>검증 이벤트 질문</b> 일련번호. 그 축이 없는 채널은 {@code null}.
     *
     * <p>포털에는 관제 인입 검증 이벤트 유형이 오지 않으므로 고를 축 자체가 없다 — 지어내지 않는다.
     */
    Long resolveQuestionSn(Long rawSn, Long requestedQstnSn);

    /**
     * 실제로 저장할 지점을 확정한다 — <b>추출 장수 상한</b>이 있는 채널은 여기서 자른다.
     *
     * <p>상한이 없는 채널은 {@link MarkPlan#unchanged(List)} 를 그대로 돌려준다.
     */
    MarkPlan capMarks(String mode, List<MarkItem> marks);

    /**
     * 마킹이 영속된 <b>같은 트랜잭션</b>에서 수행할 채널 후속 처리(상태 전이 등).
     *
     * <p>포털은 여기서 자산을 「마킹 대기 → 추출 중」으로 원자 전이한다 — 그 전이가 0행이면 동시
     * 저장이 먼저 지나간 것이라 충돌로 거절한다. 관제는 할 일이 없다.
     */
    void onSaved(Long rawSn, Long markingSn);

    /**
     * 커밋 이후 발행할 <b>채널 전용 완료 이벤트</b>.
     *
     * <p>★ 이 자리가 「포털 마킹이 관제 배치를 깨우지 않는다」의 구조적 보증이다. 관제는 배치 브리지가
     * 소비하는 이벤트를, 포털은 <b>포털 프레임 추출만</b> 소비하는 별도 이벤트를 낸다. 같은 이벤트를
     * 쓰고 소비자에서 거르는 안은 채택하지 않았다 — 거름망을 한 번 빠뜨리면 포털 사용자의 개인 영상이
     * 외부 시계열 위탁으로 나간다.
     */
    Object completionEvent(Long rawSn, Long markingSn);
}
