package kr.co.cudo.authoring.evntanno.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload.CaptionCandidate;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
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
 *   <tr><td>{@code event.question}</td><td><b>위탁 시점에 실제로 보낸 질문 문구</b> — 콜백 수신부가
 *       위탁 상관키 원장에서 읽어 넘겨 준다. <b>여기서 다시 조달하지 않는다</b></td></tr>
 *   <tr><td>{@code event.caption.c1.caption_text}</td><td>추가 질문 응답 서술</td></tr>
 *   <tr><td>{@code event.caption.c1.cot["1단계"]}</td><td>묘사 전문의 <b>「상황」 라벨 줄</b> 값</td></tr>
 * </table>
 *
 * <p><b>경위(두 번 뒤집혔다 — 세 번째로 되돌리지 말 것)</b>. 2026-08-24 확정은 추가 질문 서술을
 * <b>답변({@code answer}) 축</b>에 놓았고, 그 근거는 "추가 질문이 돌려주는 것은 서술 한 줄뿐이므로
 * 그것이 곧 답"이었다. 2026-08-25 사업 담당 회신이 그 근거를 폐기하고 <b>캡션·사고 단계 축</b>으로
 * 되돌렸다. 같은 회신이 {@code question} 의 조달원도 정했다 — 구 서술은 "질문 문장은 서버가 관리하며
 * 응답에 실려 오지 않으니 채우지 않는다"였으나, 이제 <b>우리가 그 문구를 보관</b>하므로 채운다.
 *
 * <h3>★ 질문 칸은 「보관한 값」이 아니라 「보낸 값」이다 — 재조달 금지</h3>
 * <p>추가 질문 축의 위탁이 이벤트 유형 대신 <b>질문 문구를 요청 본문에 직접</b> 싣게 되면서, 위탁
 * 시점의 그 문구가 곧 <b>사업자가 실제로 받은 질문</b>이 됐다. 그 값은 위탁 상관키 원장에 함께 보관되고
 * 콜백 수신부가 <b>같은 상관키로 그 행을 역조회</b>해 여기로 넘긴다(위탁 1건 = 원장 1행이라 재위탁·도착
 * 순서가 구조적으로 풀린다).
 *
 * <p><b>그래서 이 클래스는 조달 판정기도 마킹도 읽지 않는다.</b> 질문 목록은 <b>전체 교체</b>로 저장되어
 * 가리키던 행이 사라지는 것이 <b>정상 동선</b>이다(조달 판정기가 그렇게 못 박는다). 콜백 시점에 다시
 * 조달하면 그 사이의 교체로 <b>보낸 질문과 기록된 질문이 갈리고</b>, 사업자가 받지 않은 질문이 산출물에
 * 남는다. 이 전환의 핵심 이득이 그 자리에서 무너진다.
 *
 * <p><b>넘어온 값이 없으면 비워 둔다</b>(지어내지 않는다). 두 경우가 여기 해당한다 — ①조달이 비어 질문
 * 없이 위탁된 건 ②<b>보관이 도입되기 전에 발급된 과거 행</b>. 특히 ②는 그때 무엇을 보냈는지 알 수 없으므로
 * <b>소급해 채우지 않는다</b>. 이 도메인이 {@code event_class}·질문 카탈로그에 대해 이미 지켜 온
 * "없으면 지어내지 않고 비워 둔다"와 같은 규칙이며, 비워 두면 사람이 채울 수 있으나 잘못 채우면
 * 사람이 그것을 사실로 읽는다.
 *
 * <p>⚠ <b>묘사 축은 질문을 보내지 않는다</b> — 그 축의 원장 행에는 보낸 질문이 없으므로 묘사 콜백이
 * 질문 칸을 채우지 않는다. 두 축은 같은 위탁 사이클에서 함께 나가고 질문 칸은 비어 있을 때만 채워지므로,
 * 어느 쪽이 먼저 도착하든 최종 결과는 같다.
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
 *   <li><b>{@code event_class} 는 조달값만 쓴다</b> — 조달 순서는 <b>관제 인입의 검증 이벤트 유형 →
 *       마킹에서 작업자가 고른 유형 → 없음</b>이며, 그 순서는 {@link VerificationEventTypeResolver}
 *       한 곳이 소유한다(여기에 복제하지 않는다). 어느 쪽에서도 얻지 못하면 새 행을 만들지 않고
 *       건너뛴다(필수 항목을 지어내지 않는다).</li>
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
    private final VerificationEventTypeResolver verificationEventTypeResolver;
    private final ReviewApprovalGate approvalGate;

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
            return apply(rawSn, "cot", null, UnaryOperator.identity());
        }
        // 묘사 축은 질문을 보내지 않는다 — 보낸 적 없는 값을 여기서 만들어 내지 않는다.
        return apply(rawSn, "cot", null, payload -> withCotFirstStep(payload, value));
    }

    /**
     * {@inheritDoc}
     * [design: UC-022] [design: DFEAT-050] [design: INTSPEC-002]
     *
     * <p>서술은 캡션 본문 칸으로 간다 — <b>답변 칸은 비워 둔다</b>(위 클래스 javadoc 의 경위 참조).
     *
     * <p>질문 칸은 <b>넘어온 값</b>(위탁 시점에 실제로 보낸 문구)으로만 채운다. 없으면 비워 둔다 —
     * 여기서 조달 판정기를 부르면 그 사이의 질문 목록 교체로 보낸 값과 기록된 값이 갈린다.
     */
    @Override
    @Transactional("controlTransactionManager")
    public boolean applySubDescription(Long rawSn, String description, String sentQuestion) {
        if (rawSn == null || description == null || description.isBlank()) {
            return false;
        }
        return apply(rawSn, "caption", sentQuestion, payload -> withCaptionText(payload, description));
    }

    /**
     * 두 창구가 공유하는 채움 절차 — 보호 경계 → 병합 → 변화가 있을 때만 영속.
     *
     * <p>{@code merge} 는 채울 칸이 이미 차 있으면 <b>받은 payload 를 그대로</b> 돌려준다. 질문 칸은
     * 여기서 덧댄다 — 주 칸이 이미 차 있어도 질문만 비어 있으면 그 하나를 채우는 것이 맞다.
     *
     * @param sentQuestion 위탁 시점에 <b>실제로 보낸</b> 질문 문구. 보낸 적이 없으면(묘사 축·조달이 빈
     *                     위탁·보관 이전 과거 행) {@code null} 이고, 그때 질문 칸은 비워 둔다
     */
    private boolean apply(Long rawSn, String axis, String sentQuestion,
                          UnaryOperator<EventAnnotationPayload> merge) {
        // 승인 이력이 있으면 그 내용은 이미 산출물로 나갔다 — 자동 채움이 뒤늦게 바꾸지 않는다.
        if (approvalGate.hasEverApproved(rawSn)) {
            log.info("[EvntAnno] {} draft skipped — already approved once rawSn={}", axis, rawSn);
            return false;
        }
        Optional<LsEvntAnno> existing = annoRepository.findByRawSn(rawSn);
        if (existing.isPresent()) {
            return fillExisting(existing.get(), rawSn, axis, sentQuestion, merge);
        }
        return createDraft(rawSn, axis, sentQuestion, merge);
    }

    /**
     * 기존 행에서 <b>비어 있는 칸만</b> 채운다 — 그 외 필드는 건드리지 않는다.
     *
     * <p>역직렬화가 실패하면(과거 비규격 본문 등) <b>건너뛴다</b>. 여기서 예외를 던지면 콜백 처리
     * 전체가 롤백돼 시계열 서술까지 함께 잃는다 — 초안 하나 때문에 주 축을 잃는 것이 더 나쁘다.
     */
    private boolean fillExisting(LsEvntAnno anno, Long rawSn, String axis, String sentQuestion,
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
        EventAnnotationPayload drafted = withQuestion(merge.apply(payload), sentQuestion);
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
     * [design: ERD-013] [design: UC-019] [design: AC-1013]
     *
     * <p>{@code event_class} 는 필수라 조달값이 있어야 행을 만든다. 조달은 <b>확정된 순서</b>를 그대로
     * 따르며({@link VerificationEventTypeResolver}) 그 순서를 여기 복제하지 않는다 — 관제 인입 값이
     * 1순위이고, 관제가 그 유형을 보내지 않은 영상에서는 <b>작업자가 마킹 화면에서 고른 유형</b>이
     * 2순위다. 그 2순위가 없으면 관제 미수신 영상은 어노테이션 초안을 <b>영영 받지 못한다</b>.
     *
     * <p>어느 쪽에서도 얻지 못하면 <b>행을 만들지 않는다</b> — 지어낸 분류로 행이 생기면 사람이 그것을
     * 사실로 읽는다. 분류만 얻고 채울 다른 값이 하나도 없을 때도 마찬가지다(껍데기 행 금지).
     */
    private boolean createDraft(Long rawSn, String axis, String sentQuestion,
                                UnaryOperator<EventAnnotationPayload> merge) {
        String eventClass = verificationEventTypeResolver.resolve(rawSn);
        if (eventClass == null) {
            log.info("[EvntAnno] {} draft skipped — no verification event type to use as event_class rawSn={}",
                    axis, rawSn);
            return false;
        }
        EventAnnotationPayload empty = new EventAnnotationPayload(eventClass, null, null, null, null);
        EventAnnotationPayload drafted = withQuestion(merge.apply(empty), sentQuestion);
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
     * 질문 칸 — <b>위탁 시점에 실제로 보낸 문구</b>를 그대로 싣는다. 이미 값이 있으면 덮지 않고,
     * 보낸 값이 없으면 <b>비워 둔다</b>(지어내지 않는다).
     * [design: ADR-036] [design: INTSPEC-004] [design: ERD-021] [design: AC-1036]
     *
     * <p>★ <b>여기서 조달하지 않는다.</b> 질문 목록은 <b>전체 교체</b>로 저장되어 가리키던 행이 사라지는
     * 것이 <b>정상 동선</b>이므로, 콜백 시점에 다시 조달하면 그 사이의 교체로 <b>보낸 질문과 기록된
     * 질문이 갈린다</b> — 그러면 사업자가 실제로 받지 않은 질문이 산출물에 남는다. 조달 판정기와
     * 마킹 선택값을 읽는 일은 <b>위탁 시점</b>의 책임이고, 이 클래스는 그 결과를 받아 적기만 한다.
     *
     * <p>넘어온 값이 {@code null} 인 경우가 둘이며 <b>둘 다 비워 두는 것이 맞다</b> — ①조달이 비어
     * 질문 없이 위탁된 건(보낸 질문이 없다) ②보관 도입 이전에 발급된 과거 행(무엇을 보냈는지 알 수 없다).
     */
    private static EventAnnotationPayload withQuestion(EventAnnotationPayload payload, String sentQuestion) {
        if (payload.question() != null && !payload.question().isBlank()) {
            return payload;
        }
        if (sentQuestion == null || sentQuestion.isBlank()) {
            return payload;
        }
        return new EventAnnotationPayload(
                payload.eventClass(), sentQuestion, payload.caption(), payload.answer(), payload.evidence());
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
}
