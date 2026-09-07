package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.sysconfig.dto.VerificationEventQuestionResponse;
import kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoDurationResolver;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 관제(내부) 채널의 마킹 판정기 — <b>기존 동작을 그대로</b> 담는다.
 *
 * <p>길이 조달(자동은 프로브까지·수동은 프로브 없이), fps pin, 검증 이벤트 질문 해석, 완료 이벤트가
 * 모두 종전과 같다. 지점 상한은 이 채널에 없다(요청 본문 원소 상한이 유일한 방어선이라 자르지 않는다).
 *
 * @design ADR-058
 */
@Slf4j
class ControlMarkingChannel implements MarkingChannel {

    /** AUTO 마킹 모드 식별자 — 길이 조달 방식을 가른다. */
    private static final String MODE_AUTO = "AUTO";
    /** MANUAL 마킹 모드 식별자 — 상한 검증에도 길이가 필요하나 프로브는 태우지 않는다. */
    private static final String MODE_MANUAL = "MANUAL";

    private final ControlMarkingGuard guard;
    private final VideoFpsResolver fpsResolver;
    private final VideoDurationResolver durationResolver;
    private final IngestSourceRepository ingestSourceRepository;
    private final VerificationEventQuestionResolver questionResolver;

    ControlMarkingChannel(VideoRepository videoRepository,
                          LsTaskAssignmentRepository assignmentRepository,
                          VideoFpsResolver fpsResolver,
                          VideoDurationResolver durationResolver,
                          IngestSourceRepository ingestSourceRepository,
                          VerificationEventQuestionResolver questionResolver) {
        this.guard = new ControlMarkingGuard(videoRepository, assignmentRepository);
        this.fpsResolver = fpsResolver;
        this.durationResolver = durationResolver;
        this.ingestSourceRepository = ingestSourceRepository;
        this.questionResolver = questionResolver;
    }

    @Override
    public void requireAccess(Long rawSn, TokenClaims actor) {
        guard.requireAccess(rawSn, actor);
    }

    @Override
    public MarkingTarget requireMarkable(Long rawSn, TokenClaims actor) {
        return guard.requireMarkable(rawSn, actor);
    }

    @Override
    public Integer resolveDurationSec(Long rawSn, String mode) {
        if (MODE_AUTO.equals(mode)) {
            return durationResolver.resolveDurationSec(rawSn);
        }
        if (MODE_MANUAL.equals(mode)) {
            return durationResolver.resolveDurationSecWithoutProbe(rawSn);
        }
        // 잘못된/누락 mode 는 null 로 두고 persist 가 INVALID_INPUT 으로 거부한다(기존 계약 보존).
        return null;
    }

    @Override
    public double resolveFps(Long rawSn) {
        return fpsResolver.resolveFps(rawSn);
    }

    /**
     * 검증 이벤트 유형 조달 — <b>관제 인입값 → 작업자 선택값 → 없음</b> 순서. [design: API-047]
     *
     * <p>관제 인입이 진실원이라 값이 있으면 요청이 실어 온 유형은 <b>쓰지도 저장하지도 않는다</b>.
     * 화면이 그 경우 유형 선택을 아예 노출하지 않으므로 실제로는 필드가 오지 않으며, 이 자리는 그
     * 전제가 깨져도 인입이 이기게 하는 방어다(요청이 실어 왔다고 400 을 내지는 않는다 — 그 값을 무시하는
     * 것으로 충분하고, 거부하면 화면이 잠깐 낡은 목록을 들고 있을 때 정상 마킹이 막힌다).
     *
     * <p>⚠ 이 우선순위는 {@code API-047} 본문이 명시하지 않은 <b>구현 판단</b>이다(CO-20260907 §6-A 의
     * 「관제 값이 있으면 유형 선택을 노출하지 않는다」에서 따온 것이며, 설계 반영은 별도 판단 대상).
     */
    @Override
    public MarkingEventType resolveEventType(Long rawSn, String requestedTypeCd) {
        IngestSourceRow source = ingestSourceRepository.findSourceMeta(rawSn);
        String fromControl = LsDataIngest.normalizeVrfcEvntType(
                source == null ? null : source.getVrfcEvntTypeCd());
        if (fromControl != null) {
            return MarkingEventType.fromControl(fromControl);
        }
        // 관제 미수신 — 작업자가 마킹 화면에서 고른 값이 유일한 조달처다. 정규화는 인입 엔티티의 함수
        // 하나를 재사용한다(복제 금지 — 복제하면 인입이 실어 보낸 표기가 이쪽에서만 조달에 실패한다).
        String selected = LsDataIngest.normalizeVrfcEvntType(requestedTypeCd);
        if (selected == null) {
            log.info("[Marking] verification event type missing and not selected rawSn={}", rawSn);
            return MarkingEventType.NONE;
        }
        return MarkingEventType.selectedByWorker(selected);
    }

    /**
     * 질문 조달 — <b>판정을 하지 않고 그대로 위임</b>한다. [design: ERD-033] [design: AC-1013]
     *
     * <h3>★ 여기에 판정 사본이 있었다 — 되살리지 말 것</h3>
     * <p>종전 구현은 인입 유형이 비면 <b>판정기를 호출하지 않고 {@code null} 을 조기 반환</b>했다. 그래서
     * 조달 판정기의 계약이 「유형이 미수신이어도 작업자가 고른 질문이 있으면 그 질문을 쓴다」로 뒤집힌
     * 뒤에도 <b>마킹 저장 경로에서만 그 반전이 발동하지 않았다</b> — 작업자가 질문을 골라도 원장에 빈
     * 값이 저장됐다.
     *
     * <p><b>발견 단서가 「깨졌어야 할 시험이 안 깨졌다」였다.</b> 판정기 계약을 뒤집었는데 소비자 시험이
     * 하나도 죽지 않으면 그것은 안전 신호가 아니라 <b>사본 존재 신호</b>다. 이 도메인은 「유형이 비면
     * 어떻게 되는가」를 <b>스스로 판정하지 않는다</b> — 그 판정의 단일 진실원은 조달 판정기 하나다.
     */
    @Override
    public Long resolveQuestionSn(Long rawSn, Long requestedQstnSn, MarkingEventType eventType) {
        return questionResolver.resolve(requestedQstnSn, eventType.typeCd())
                .map(VerificationEventQuestionResponse::vrfcEvntQstnSn)
                .orElse(null);
    }

    @Override
    public MarkPlan capMarks(String mode, List<MarkItem> marks) {
        // 관제 채널에는 추출 장수 상한이 없다 — 자르지 않는다.
        return MarkPlan.unchanged(marks);
    }

    @Override
    public void onSaved(Long rawSn, Long markingSn) {
        // 관제 채널은 마킹 저장이 상태를 옮기지 않는다 — 배치 브리지가 커밋 이후에 판단한다.
    }

    @Override
    public Object completionEvent(Long rawSn, Long markingSn) {
        return new MarkingCompletedEvent(rawSn, markingSn);
    }
}
