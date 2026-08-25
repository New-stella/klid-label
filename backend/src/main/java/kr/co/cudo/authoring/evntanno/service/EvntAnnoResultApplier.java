package kr.co.cudo.authoring.evntanno.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload.CaptionCandidate;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.webhook.service.TimeseriesResultApplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 외부 시계열 분석 두 창구의 결과를 이벤트 어노테이션의 <b>확정 칸 초안</b>으로 반영한다.
 * [design: ADR-051] [design: CDIAG-014]
 *
 * <p>이 도메인이 어노테이션의 구조·동결·재검수 시맨틱을 소유하므로, 콜백 수신부는 "어느 창구의
 * 결과인지 되짚어 넘기는 일"까지만 하고 채우는 규칙은 여기 있다.
 *
 * <h3>어느 칸을 채우는가 — ★2026-08-25 사업 담당 회신으로 축이 바뀌었다</h3>
 * <table border="1">
 *   <caption>창구별 적재 축</caption>
 *   <tr><th>칸</th><th>조달처</th></tr>
 *   <tr><td>{@code video.vd_description}</td><td>묘사 전문 — <b>이 클래스 밖</b>(시계열 메타 축, 종전과 같다)</td></tr>
 *   <tr><td>{@code event.question}</td><td>저작도구가 보관하는 <b>검증 이벤트 유형별 질문 문구</b> —
 *       <b>마킹에서 고른 질문</b>이 1순위이고, 없으면 그 유형의 첫 번째로 되돌린다</td></tr>
 *   <tr><td>{@code event.caption.c1.caption_text}</td><td>추가 질문(describe-sub) 응답 서술</td></tr>
 *   <tr><td>{@code event.caption.c1.cot["1단계"]}</td><td>묘사 전문의 <b>「상황」 라벨 줄</b> 값</td></tr>
 * </table>
 *
 * <p><b>경위(두 번 뒤집혔다 — 세 번째로 되돌리지 말 것)</b>. 2026-08-24 확정은 추가 질문 서술을
 * <b>답변({@code answer}) 축</b>에 놓았고, 그 근거는 "추가 질문이 돌려주는 것은 서술 한 줄뿐이므로
 * 그것이 곧 답"이었다. 2026-08-25 사업 담당 회신이 그 근거를 폐기하고 <b>캡션·사고 단계 축</b>으로
 * 되돌렸다. 같은 회신이 {@code question} 의 조달원도 정했다 — 구 서술은 "질문 문장은 서버가 관리하며
 * 응답에 실려 오지 않으니 채우지 않는다"였으나, 이제 <b>우리가 그 문구를 보관</b>하므로 채운다.
 *
 * <p>{@code answer} · {@code evidence} · 사고 단계 2단계 이후는 <b>자동으로 채우지 않는다</b> —
 * 사람이 확정할 공란이다. 특히 {@code evidence} 는 근거 서술이 답변 본문에 자연어로 섞여 있고 규격이
 * 그 서술의 형식이 고정돼 있지 않으니 문자열 파싱에 의존하지 말라고 못 박는다. ⚠ 「상황」 파싱은 그
 * 금지의 <b>예외가 아니라 별개</b>다 — 규격 콜백 <b>예시에 형식이 명시된</b> 묘사 축이다.
 *
 * <h3>두 축이 같은 후보 {@code c1} 을 공유한다</h3>
 * <p>두 창구의 <b>도착 순서는 보장되지 않는다</b>. 어느 쪽이 먼저 와도 {@code c1} 을 만들고 나중 것이
 * <b>자기 칸만</b> 채운다. 후보를 통째로 새 객체로 갈아끼우면 먼저 도착한 축의 값이 지워지므로,
 * 기존 후보를 읽어 다른 칸을 그대로 실어 나른다.
 *
 * <h3>덮지 않는 경계</h3>
 * <ul>
 *   <li><b>승인 이력이 있으면 손대지 않는다</b> — 그 내용은 이미 산출물로 나간 것이라, 자동 채움이
 *       뒤늦게 바꾸면 내보낸 회차와 어긋난다. ★현재 상태가 아니라 <b>이력</b>으로 판정한다: 승인 후
 *       재검수로 되돌아온 구간에도 이미 내보낸 회차가 있어, 현재 상태만 보면 그 구간이 뚫린다.</li>
 *   <li><b>값이 이미 있는 칸은 손대지 않는다</b> — 사람이 쓴 값인지 앞선 자동 채움인지 구분할 수단이
 *       없으므로, 값이 있으면 사람의 것으로 본다(fail-secure).</li>
 *   <li><b>어노테이션 검토가 이미 종결됐으면 손대지 않는다</b> — 검토행이 승인·반려로 결론난 뒤에
 *       늦은 콜백이 본문을 채우면, 검수자가 한 번도 보지 않은 문장이 승인 시점의 동결본에 실린다.
 *       영상 승인 이력만 보면 <b>어노테이션은 먼저 승인됐는데 영상은 아직인 구간</b>이 뚫린다.</li>
 *   <li>따라서 같은 결과를 여러 번 받아도 두 번째부터는 아무것도 하지 않는다(멱등). 규격 §5.1 상
 *       콜백은 중복 수신될 수 있다.</li>
 *   <li><b>{@code event_class} 는 조달값만 쓴다</b> — 관제 인입의 검증 이벤트 유형이다. 그 값이
 *       없으면 새 행을 만들지 않고 건너뛴다(필수 항목을 지어내지 않는다).</li>
 * </ul>
 *
 * <p><b>재검수를 발화시키지 않는다</b> — 이 클래스는 수정 이벤트를 발행하지 않는다. 재검수는
 * 사람이 내용을 고쳤을 때의 축이며, 초안이 처음 채워지는 것은 그 축이 아니다.
 *
 * <p>캡션 본문 길이는 따로 자르지 않는다 — 콜백 수신부가 서술을
 * {@link kr.co.cudo.authoring.webhook.dto.VlmResultRequest.Results#MAX_DESCRIPTION_LENGTH} 로 이미
 * 막아 두었고 그 값이 캡션 본문 상한 안에 든다. 수신부의 상한을 올릴 때 이 전제를 함께 확인할 것.
 * 반면 <b>사고 단계 칸은 상한이 더 낮아</b>({@link EventAnnotationPayload#MAX_COT_STEP}) 별도로 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvntAnnoResultApplier implements TimeseriesResultApplier {

    /** 자동 채움의 등록자 표기 — 사람이 쓴 값과 구분되도록 남긴다. */
    private static final String DRAFT_ACTOR = "SYSTEM";

    /** 두 창구가 공유하는 캡션 후보 키. 다른 후보(c2…)가 있어도 이 하나만 다룬다. */
    static final String CAPTION_CANDIDATE_KEY = "c1";

    /** 사고 단계 첫 칸 키 — 접미사 리터럴을 복제하지 않고 payload 의 것을 조립한다. */
    static final String COT_FIRST_STEP_KEY = 1 + EventAnnotationPayload.COT_STEP_SUFFIX;

    private final LsEvntAnnoRepository annoRepository;
    private final LsEvntAnnoReviewRepository reviewRepository;
    private final IngestSourceRepository ingestSourceRepository;
    private final ReviewApprovalGate approvalGate;
    /**
     * 질문 문구 조달 판정기 — <b>단일 진실원을 주입해 쓴다</b>. 「첫 번째 질문」의 해석을 여기 복제하면
     * 화면이 보여준 질문과 산출물에 실린 질문이 조용히 어긋난다.
     */
    private final VerificationEventQuestionResolver questionResolver;
    /**
     * 질문 칸 <b>1순위 조달값</b>(마킹이 고른 질문)의 읽기 담당 — 「어느 마킹 행에서 읽는가」의 규칙은
     * 그쪽이 소유한다. 여기서 마킹을 직접 뒤지지 않는다.
     */
    private final MarkingSelectedQuestionReader selectedQuestionReader;

    /**
     * {@inheritDoc}
     *
     * <h3>못 채우는 두 경우는 결과가 다르다 — 붙이지 말 것</h3>
     * <ul>
     *   <li><b>「상황」 라벨 줄이 아예 없다</b> → 이 창구가 나를 부를 이유가 없었던 것이므로
     *       <b>아무것도 하지 않는다</b>(빈 문자열도 넣지 않고 행도 만들지 않는다). 그러지 않으면
     *       그 줄을 담지 않은 묘사가 올 때마다 알맹이 없는 행이 생긴다.</li>
     *   <li><b>줄은 있는데 값이 사고 단계 상한을 넘는다</b> → <b>그 칸만 건너뛰고 나머지는 그대로
     *       진행</b>한다. 자르면 원문과 다른 값이 사람의 확인 없이 확정되고, 넘겨 넣으면 사람이 그
     *       본문을 되돌려 저장할 때 <b>저장이 통째로 400</b> 이 된다.</li>
     * </ul>
     */
    @Override
    @Transactional("controlTransactionManager")
    public boolean applyDescription(Long rawSn, String description) {
        if (rawSn == null || description == null || description.isBlank()) {
            return false;
        }
        Optional<String> situation = DescriptionSituationExtractor.extract(description);
        if (situation.isEmpty()) {
            log.info("[EvntAnno] cot draft skipped — no situation line in description rawSn={}", rawSn);
            return false;
        }
        String value = situation.get();
        if (value.length() > EventAnnotationPayload.MAX_COT_STEP) {
            log.warn("[EvntAnno] cot step skipped — situation exceeds step limit rawSn={} length={}",
                    rawSn, value.length());
            return apply(rawSn, "cot", UnaryOperator.identity());
        }
        return apply(rawSn, "cot", payload -> withCotFirstStep(payload, value));
    }

    /**
     * {@inheritDoc}
     *
     * <p>서술은 캡션 본문 칸으로 간다 — <b>답변 칸은 비워 둔다</b>(위 클래스 javadoc 의 경위 참조).
     */
    @Override
    @Transactional("controlTransactionManager")
    public boolean applySubDescription(Long rawSn, String description) {
        if (rawSn == null || description == null || description.isBlank()) {
            return false;
        }
        return apply(rawSn, "caption", payload -> withCaptionText(payload, description));
    }

    /**
     * 두 창구가 공유하는 채움 절차 — 보호 경계 → 병합 → 변화가 있을 때만 영속.
     *
     * <p>{@code merge} 는 채울 칸이 이미 차 있으면 <b>받은 payload 를 그대로</b> 돌려준다. 질문 칸은
     * 창구와 무관하게 함께 조달하므로 여기서 덧댄다 — 주 칸이 이미 차 있어도 질문만 비어 있으면
     * 그 하나를 채우는 것이 맞다.
     */
    private boolean apply(Long rawSn, String axis, UnaryOperator<EventAnnotationPayload> merge) {
        // 승인 이력이 있으면 그 내용은 이미 산출물로 나갔다 — 자동 채움이 뒤늦게 바꾸지 않는다.
        if (approvalGate.hasEverApproved(rawSn)) {
            log.info("[EvntAnno] {} draft skipped — already approved once rawSn={}", axis, rawSn);
            return false;
        }
        String vrfcEvntTypeCd = resolveVrfcEvntType(rawSn);
        Optional<LsEvntAnno> existing = annoRepository.findByRawSn(rawSn);
        if (existing.isPresent()) {
            return fillExisting(existing.get(), rawSn, axis, vrfcEvntTypeCd, merge);
        }
        return createDraft(rawSn, axis, vrfcEvntTypeCd, merge);
    }

    /**
     * 기존 행에서 <b>비어 있는 칸만</b> 채운다 — 그 외 필드는 건드리지 않는다.
     *
     * <p>역직렬화가 실패하면(과거 비규격 본문 등) <b>건너뛴다</b>. 여기서 예외를 던지면 콜백 처리
     * 전체가 롤백돼 시계열 서술까지 함께 잃는다 — 초안 하나 때문에 주 축을 잃는 것이 더 나쁘다.
     */
    private boolean fillExisting(LsEvntAnno anno, Long rawSn, String axis, String vrfcEvntTypeCd,
                                 UnaryOperator<EventAnnotationPayload> merge) {
        EventAnnotationPayload payload;
        try {
            payload = EventAnnotationPayload.fromJson(anno.getAnnoCn());
        } catch (RuntimeException e) {
            log.warn("[EvntAnno] {} draft skipped — payload not readable rawSn={} cause={}",
                    axis, rawSn, e.getClass().getSimpleName());
            return false;
        }
        if (isReviewSettled(anno.getEvntAnnoSn())) {
            log.info("[EvntAnno] {} draft skipped — annotation review already settled rawSn={}", axis, rawSn);
            return false;
        }
        EventAnnotationPayload drafted = withQuestion(rawSn, merge.apply(payload), vrfcEvntTypeCd);
        if (drafted.equals(payload)) {
            log.info("[EvntAnno] {} draft skipped — target fields already present rawSn={}", axis, rawSn);
            return false;
        }
        anno.updatePayload(drafted.toJson(), DRAFT_ACTOR);
        log.info("[EvntAnno] {} draft filled into existing annotation rawSn={}", axis, rawSn);
        return true;
    }

    /**
     * 어노테이션 행이 아직 없을 때 초안 행을 만든다.
     *
     * <p>{@code event_class} 는 필수인데 우리가 가진 유일한 조달처가 관제 인입의 검증 이벤트 유형이다.
     * 그 값이 없으면 <b>행을 만들지 않는다</b> — 지어낸 분류로 행이 생기면 사람이 그것을 사실로 읽는다.
     */
    private boolean createDraft(Long rawSn, String axis, String vrfcEvntTypeCd,
                                UnaryOperator<EventAnnotationPayload> merge) {
        String eventClass = LsDataIngest.normalizeVrfcEvntType(vrfcEvntTypeCd);
        if (eventClass == null) {
            log.info("[EvntAnno] {} draft skipped — no verification event type to use as event_class rawSn={}",
                    axis, rawSn);
            return false;
        }
        EventAnnotationPayload empty = new EventAnnotationPayload(eventClass, null, null, null, null);
        EventAnnotationPayload drafted = withQuestion(rawSn, merge.apply(empty), vrfcEvntTypeCd);
        if (drafted.equals(empty)) {
            // 채울 값이 하나도 없었다 — 분류만 든 껍데기 행을 남기지 않는다(사람이 그것을 작업물로 읽는다).
            log.info("[EvntAnno] {} draft skipped — nothing to draft rawSn={}", axis, rawSn);
            return false;
        }
        annoRepository.save(LsEvntAnno.create(rawSn, drafted.toJson(), DRAFT_ACTOR));
        log.info("[EvntAnno] {} draft created rawSn={}", axis, rawSn);
        return true;
    }

    /**
     * 질문 칸 조달 — 이미 값이 있으면 덮지 않고, 카탈로그에 없으면 <b>비워 둔다</b>(지어내지 않는다).
     * [design: ADR-036] [design: AC-024]
     *
     * <p><b>1순위는 마킹에서 고른 질문</b>이다({@code LS_MARKING.VRFC_EVNT_QSTN_SN} — 어느 마킹 행에서
     * 읽는지는 {@link MarkingSelectedQuestionReader} 가 정한다). 그 값을 조달 판정기에 <b>그대로 넘기고
     * 여기서 다시 판정하지 않는다</b> — 그 유형에 속하는지, 속하지 않으면 무엇으로 되돌릴지는 판정기가
     * 단일 진실원으로 소유한다. 규칙을 복제하면 화면이 보여준 질문과 산출물에 실린 질문이 조용히 어긋난다.
     *
     * <p>이미 값이 있으면 <b>마킹을 읽기도 전에</b> 빠져나간다 — 덮지 않을 값을 위해 조회하지 않는다.
     */
    private EventAnnotationPayload withQuestion(Long rawSn, EventAnnotationPayload payload, String vrfcEvntTypeCd) {
        if (payload.question() != null && !payload.question().isBlank()) {
            return payload;
        }
        Long selectedQstnSn = selectedQuestionReader.findSelectedQuestionSn(rawSn);
        String text = questionResolver.resolveQuestionText(selectedQstnSn, vrfcEvntTypeCd).orElse(null);
        if (text == null || text.isBlank()) {
            return payload;
        }
        return new EventAnnotationPayload(
                payload.eventClass(), text, payload.caption(), payload.answer(), payload.evidence());
    }

    /**
     * 캡션 본문 칸을 채운다 — <b>같은 후보의 사고 단계는 그대로 실어 나른다</b>(먼저 도착한 축 보존).
     */
    private static EventAnnotationPayload withCaptionText(EventAnnotationPayload payload, String text) {
        Map<String, CaptionCandidate> caption = copyCaption(payload.caption());
        CaptionCandidate candidate = caption.get(CAPTION_CANDIDATE_KEY);
        if (candidate != null && candidate.captionText() != null && !candidate.captionText().isBlank()) {
            return payload;
        }
        caption.put(CAPTION_CANDIDATE_KEY,
                new CaptionCandidate(text, candidate == null ? null : candidate.cot()));
        return replaceCaption(payload, caption);
    }

    /**
     * 사고 단계 <b>1단계 칸만</b> 채운다 — 2단계 이후 키는 만들지 않는다(사람이 채울 공란).
     *
     * <p>기존 단계 키가 있으면 함께 실어 나른다. 캡션 본문 칸도 그대로 둔다(먼저 도착한 축 보존).
     */
    private static EventAnnotationPayload withCotFirstStep(EventAnnotationPayload payload, String situation) {
        Map<String, CaptionCandidate> caption = copyCaption(payload.caption());
        CaptionCandidate candidate = caption.get(CAPTION_CANDIDATE_KEY);
        Map<String, String> cot = (candidate == null || candidate.cot() == null)
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(candidate.cot());
        String present = cot.get(COT_FIRST_STEP_KEY);
        if (present != null && !present.isBlank()) {
            return payload;
        }
        cot.put(COT_FIRST_STEP_KEY, situation);
        caption.put(CAPTION_CANDIDATE_KEY,
                new CaptionCandidate(candidate == null ? null : candidate.captionText(), cot));
        return replaceCaption(payload, caption);
    }

    private static Map<String, CaptionCandidate> copyCaption(Map<String, CaptionCandidate> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static EventAnnotationPayload replaceCaption(EventAnnotationPayload payload,
                                                        Map<String, CaptionCandidate> caption) {
        return new EventAnnotationPayload(
                payload.eventClass(), payload.question(), caption, payload.answer(), payload.evidence());
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

    /**
     * 검증 이벤트 유형 조달(정규화 전 원문) — {@code event_class} 와 질문 조달이 함께 쓴다.
     *
     * <p>정규화는 소비 지점에서 각자 <b>인입 엔티티의 함수</b>를 재사용한다(리터럴·규칙 복제 금지).
     */
    private String resolveVrfcEvntType(Long rawSn) {
        IngestSourceRow source = ingestSourceRepository.findSourceMeta(rawSn);
        return source == null ? null : source.getVrfcEvntTypeCd();
    }
}
