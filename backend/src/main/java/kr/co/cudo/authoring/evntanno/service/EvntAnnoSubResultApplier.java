package kr.co.cudo.authoring.evntanno.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.webhook.service.TimeseriesSubResultApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 추가 질문(describe-sub) 결과를 이벤트 어노테이션의 답변 축 <b>초안</b>으로 반영한다.
 *
 * <p>이 도메인이 어노테이션의 구조·동결·재검수 시맨틱을 소유하므로, 콜백 수신부는 "어느 창구의
 * 결과인지 되짚어 넘기는 일"까지만 하고 채우는 규칙은 여기 있다.
 *
 * <h3>무엇을 채우고 무엇을 채우지 않는가</h3>
 * <p>추가 질문이 돌려주는 것은 <b>서술 한 줄</b>뿐이다(규격 §3.3 — 판정 항목이 없다). 그 서술은
 * "이벤트가 있었는지와 그 근거"에 대한 답이므로 {@code answer} 축에 놓는다.
 * <ul>
 *   <li><b>{@code question} 은 채우지 않는다</b> — 질문 문장은 이벤트별로 <b>서버가 관리</b>하며
 *       응답에 실려 오지 않는다. 우리가 지어내면 실제로 물어본 질문과 다른 문장이 남는다.</li>
 *   <li><b>{@code evidence} 는 채우지 않는다</b> — 근거 서술은 답변 본문 안에 자연어로 섞여 있고,
 *       프레임·객체 지시로 쪼개려면 파싱이 필요하다. 규격 §5.3 이 서술은 형식이 고정돼 있지 않으니
 *       문자열 파싱에 의존하지 말라고 못 박는다.</li>
 *   <li><b>{@code event_class} 는 조달값만 쓴다</b> — 관제 인입의 검증 이벤트 유형이다. 그 값이
 *       없으면 새 행을 만들지 않고 건너뛴다(필수 항목을 지어내지 않는다).</li>
 * </ul>
 *
 * <h3>덮지 않는 경계</h3>
 * <ul>
 *   <li><b>승인 이력이 있으면 손대지 않는다</b> — 그 내용은 이미 산출물로 나간 것이라, 자동 채움이
 *       뒤늦게 바꾸면 내보낸 회차와 어긋난다.</li>
 *   <li><b>답변이 이미 있으면 손대지 않는다</b> — 사람이 쓴 값인지 앞선 자동 채움인지 구분할 수단이
 *       없으므로, 값이 있으면 사람의 것으로 본다(fail-secure).</li>
 *   <li><b>어노테이션 검토가 이미 종결됐으면 손대지 않는다</b> — 검토행이 승인·반려로 결론난 뒤에
 *       늦은 콜백이 답변을 채우면, 검수자가 한 번도 보지 않은 문장이 승인 시점의 동결본에 실린다.
 *       영상 승인 이력만 보면 <b>어노테이션은 먼저 승인됐는데 영상은 아직인 구간</b>이 뚫린다.</li>
 *   <li>따라서 같은 결과를 여러 번 받아도 두 번째부터는 아무것도 하지 않는다(멱등). 규격 §5.1 상
 *       콜백은 중복 수신될 수 있다.</li>
 * </ul>
 *
 * <p><b>재검수를 발화시키지 않는다</b> — 이 클래스는 수정 이벤트를 발행하지 않는다. 재검수는
 * 사람이 내용을 고쳤을 때의 축이며, 초안이 처음 채워지는 것은 그 축이 아니다.
 *
 * <p>길이는 따로 자르지 않는다 — 콜백 수신부가 서술을
 * {@link kr.co.cudo.authoring.webhook.dto.VlmResultRequest.Results#MAX_DESCRIPTION_LENGTH} 로 이미
 * 막아 두었고 그 값이 답변 필드의 상한 안에 든다. 수신부의 상한을 올릴 때 이 전제를 함께 확인할 것.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvntAnnoSubResultApplier implements TimeseriesSubResultApplier {

    /** 자동 채움의 등록자 표기 — 사람이 쓴 값과 구분되도록 남긴다. */
    private static final String DRAFT_ACTOR = "SYSTEM";

    private final LsEvntAnnoRepository annoRepository;
    private final LsEvntAnnoReviewRepository reviewRepository;
    private final IngestSourceRepository ingestSourceRepository;
    private final ReviewApprovalGate approvalGate;

    @Override
    @Transactional("controlTransactionManager")
    public boolean applySubDescription(Long rawSn, String description) {
        if (rawSn == null || description == null || description.isBlank()) {
            return false;
        }

        // 승인 이력이 있으면 그 내용은 이미 산출물로 나갔다 — 자동 채움이 뒤늦게 바꾸지 않는다.
        // ★ 현재 상태가 아니라 이력으로 판정한다: 승인 후 재검수로 되돌아온 구간에도 이미 내보낸
        //   회차가 존재하므로, 현재 상태만 보면 그 구간에 자동 채움이 통과한다.
        if (approvalGate.hasEverApproved(rawSn)) {
            log.info("[EvntAnno] sub draft skipped — already approved once rawSn={}", rawSn);
            return false;
        }

        Optional<LsEvntAnno> existing = annoRepository.findByRawSn(rawSn);
        if (existing.isPresent()) {
            return fillAnswerIfBlank(existing.get(), rawSn, description);
        }
        return createDraft(rawSn, description);
    }

    /**
     * 기존 행의 답변이 비어 있을 때만 채운다 — 그 외 필드는 건드리지 않는다.
     *
     * <p>역직렬화가 실패하면(과거 비규격 본문 등) <b>건너뛴다</b>. 여기서 예외를 던지면 콜백 처리
     * 전체가 롤백돼 시계열 서술까지 함께 잃는다 — 초안 하나 때문에 주 축을 잃는 것이 더 나쁘다.
     */
    private boolean fillAnswerIfBlank(LsEvntAnno anno, Long rawSn, String description) {
        EventAnnotationPayload payload;
        try {
            payload = EventAnnotationPayload.fromJson(anno.getAnnoCn());
        } catch (RuntimeException e) {
            log.warn("[EvntAnno] sub draft skipped — payload not readable rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return false;
        }
        if (payload.answer() != null && !payload.answer().isBlank()) {
            log.info("[EvntAnno] sub draft skipped — answer already present rawSn={}", rawSn);
            return false;
        }
        if (isReviewSettled(anno.getEvntAnnoSn())) {
            log.info("[EvntAnno] sub draft skipped — annotation review already settled rawSn={}", rawSn);
            return false;
        }
        EventAnnotationPayload drafted = new EventAnnotationPayload(
                payload.eventClass(), payload.question(), payload.caption(),
                description, payload.evidence());
        anno.updatePayload(drafted.toJson(), DRAFT_ACTOR);
        log.info("[EvntAnno] sub draft filled into existing annotation rawSn={}", rawSn);
        return true;
    }

    /**
     * 어노테이션 행이 아직 없을 때 초안 행을 만든다.
     *
     * <p>{@code event_class} 는 필수인데 우리가 가진 유일한 조달처가 관제 인입의 검증 이벤트 유형이다.
     * 그 값이 없으면 <b>행을 만들지 않는다</b> — 지어낸 분류로 행이 생기면 사람이 그것을 사실로 읽는다.
     */
    private boolean createDraft(Long rawSn, String description) {
        String eventClass = resolveEventClass(rawSn);
        if (eventClass == null) {
            log.info("[EvntAnno] sub draft skipped — no verification event type to use as event_class rawSn={}", rawSn);
            return false;
        }
        EventAnnotationPayload drafted = new EventAnnotationPayload(
                eventClass, null, null, description, null);
        annoRepository.save(LsEvntAnno.create(rawSn, drafted.toJson(), DRAFT_ACTOR));
        log.info("[EvntAnno] sub draft created rawSn={}", rawSn);
        return true;
    }

    /**
     * 어노테이션 검토가 이미 결론난 상태인가 — 승인·반려 중 하나면 자동 채움 대상이 아니다.
     *
     * <p>반려도 포함한다 — 반려는 <b>그 시점 본문</b>에 대한 판단이라, 자동 채움이 본문을 바꾸면
     * 그 판단이 무엇에 대한 것이었는지 알 수 없게 된다.
     */
    private boolean isReviewSettled(Long evntAnnoSn) {
        if (evntAnnoSn == null) {
            return false;
        }
        return reviewRepository.findByEvntAnnoSn(evntAnnoSn).stream()
                .map(LsEvntAnnoReview::getRvwSttsCd)
                .anyMatch(st -> LsEvntAnnoReview.STTS_APPROVED.equals(st)
                        || LsEvntAnnoReview.STTS_REJECTED.equals(st));
    }

    /** 검증 이벤트 유형 조달 — 정규화는 인입 엔티티의 것을 재사용한다(리터럴 복제 금지). */
    private String resolveEventClass(Long rawSn) {
        IngestSourceRow source = ingestSourceRepository.findSourceMeta(rawSn);
        String raw = source == null ? null : source.getVrfcEvntTypeCd();
        return LsDataIngest.normalizeVrfcEvntType(raw);
    }
}
