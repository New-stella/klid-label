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
     * 이 마킹에 쓸 <b>검증 이벤트 유형</b> 조달 — 관제 인입값이 진실원이고, 없을 때만 작업자 선택값.
     *
     * <p>그 축이 없는 채널(포털)은 {@link MarkingEventType#NONE} 이다 — 관제 인입 검증 이벤트 유형이
     * 그 경로로 오지 않고, 화면도 유형 선택을 내보내지 않는다. 그래서 기본 구현이 곧 정답이다.
     *
     * @param requestedTypeCd 요청이 실어 온 작업자 선택값(정규화 완료 또는 {@code null})
     * @design API-047
     */
    default MarkingEventType resolveEventType(Long rawSn, String requestedTypeCd) {
        return MarkingEventType.NONE;
    }

    /**
     * 마킹 행에 남길 <b>검증 이벤트 질문</b> 일련번호. 그 축이 없는 채널은 {@code null}.
     *
     * <p>포털에는 관제 인입 검증 이벤트 유형이 오지 않으므로 고를 축 자체가 없다 — 지어내지 않는다.
     *
     * <p>★ <b>유형은 인자로 받는다 — 채널이 다시 조달하지 않는다.</b> 조달은 위
     * {@link #resolveEventType} 한 번뿐이고, 그 결과를 그대로 넘겨야 「질문을 고른 유형」과 「마킹 행에
     * 저장되는 유형」이 구조적으로 같은 값이 된다. 여기서 다시 인입을 읽으면 두 값이 갈릴 수 있다.
     *
     * @param eventType {@link #resolveEventType} 가 조달한 유형 — 판정에 쓸 유효 유형이 여기 담긴다
     */
    Long resolveQuestionSn(Long rawSn, Long requestedQstnSn, MarkingEventType eventType);

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
