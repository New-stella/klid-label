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

    @Override
    public Long resolveQuestionSn(Long rawSn, Long requestedQstnSn) {
        IngestSourceRow source = ingestSourceRepository.findSourceMeta(rawSn);
        String vrfcEvntTypeCd = LsDataIngest.normalizeVrfcEvntType(
                source == null ? null : source.getVrfcEvntTypeCd());
        if (vrfcEvntTypeCd == null) {
            // 유형이 없으면 고를 축이 없다 — 비워 둔다(지어내지 않는다). 마킹은 그대로 진행한다.
            log.info("[Marking] verification event type missing — question left empty rawSn={}", rawSn);
            return null;
        }
        return questionResolver.resolve(requestedQstnSn, vrfcEvntTypeCd)
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
