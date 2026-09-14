package kr.co.cudo.authoring.evntanno;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload.CaptionCandidate;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoResultApplier;
import kr.co.cudo.authoring.evntanno.service.MarkingSelectedQuestionReader;
import kr.co.cudo.authoring.evntanno.service.VerificationEventTypeResolver;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.support.VlmKlidLiveFixtures;
import kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link EvntAnnoResultApplier} 단위 시험 — 시계열 위탁 두 창구의 결과를 이벤트 어노테이션의
 * <b>확정 칸</b>에 적재하는 규칙.
 *
 * <p><b>왜 이 파일이 생겼나</b> — 이 구현체를 참조하는 시험이 <b>한 건도 없었다</b>. 승인 이력 가드,
 * 기존 값 미덮음, 검토 종결 가드, {@code event_class} 조달 실패 시 행 미생성 같은 <b>보호 경계가
 * 아무 시험으로도 고정돼 있지 않았다</b>. 그래서 수용기준뿐 아니라 그 경계들을 함께 못박는다.
 *
 * <p>적재 축(2026-08-25 확정): 추가 질문 → {@code caption.c1.caption_text} · 묘사의 「상황」 →
 * {@code caption.c1.cot["1단계"]} · 질문 → <b>위탁 시점에 실제로 보낸 문구</b>.
 * {@code answer}·{@code evidence}·2단계 이후는 <b>사람이 채울 공란</b>이다.
 *
 * <p>★ <b>질문 축이 「보관한 값」에서 「보낸 값」으로 바뀌었다.</b> 구 판은 콜백 시점에 조달 판정기와
 * 마킹 선택값을 <b>다시 읽어</b> 채웠다. 그 사이에 질문 목록이 전체 교체되면(그것이 정상 동선이다)
 * <b>보낸 질문과 기록된 질문이 갈린다</b>. 이제 위탁 시점에 보낸 문구가 상관키 원장에 보관되고
 * 콜백 수신부가 그 값을 넘겨 주므로, 이 클래스는 <b>받아 적기만</b> 한다.
 */
class EvntAnnoResultApplierTest {

    private static final long RAW_SN = 4200L;
    private static final long EVNT_ANNO_SN = 77L;
    private static final String VRFC_EVNT_TYPE = "fire";
    private static final String EVENT_CLASS = "fire";
    private static final String QUESTION = "화재가 발생했습니까? 근거를 서술하십시오.";
    private static final String SUB_TEXT = "네, 연기와 불꽃이 함께 관측됩니다.";
    private static final String SITUATION = "불꽃은 확인되지 않음";
    private static final String DESCRIPTION =
            "- 장소: 주택가 골목\n- 날씨: 흐림\n- 상황: " + SITUATION + "\n- 인원: 2명";

    private static final String CANDIDATE = "c1";
    private static final String FIRST_STEP = "1단계";
    private static final String DRAFT_ACTOR = "SYSTEM";

    private LsEvntAnnoRepository annoRepository;
    private LsEvntAnnoReviewRepository reviewRepository;
    private IngestSourceRepository ingestSourceRepository;
    private LsMarkingRepository markingRepository;
    private ReviewApprovalGate approvalGate;

    private EvntAnnoResultApplier applier;

    @BeforeEach
    void setUp() {
        annoRepository = mock(LsEvntAnnoRepository.class);
        reviewRepository = mock(LsEvntAnnoReviewRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        approvalGate = mock(ReviewApprovalGate.class);
        // ★ 조달 협력자는 목이 아니라 <b>실제 객체</b>로 조립한다 — 이 시험이 고정하려는 것이
        //  「초안 적재가 확정된 조달 순서를 실제로 탄다」이기 때문이다. 목으로 두면 적재가 관제 인입만
        //  보도록 되돌아가도 이 시험은 초록으로 남는다(그 상태가 바로 이번 변경 이전의 결함이었다).
        applier = new EvntAnnoResultApplier(annoRepository, reviewRepository,
                new VerificationEventTypeResolver(
                        ingestSourceRepository, new MarkingSelectedQuestionReader(markingRepository)),
                approvalGate);

        when(markingRepository.findByRawSnAndSttsCdIn(anyLong(), any())).thenReturn(List.of());
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(anyLong())).thenReturn(List.of());
        stubIngestType(VRFC_EVNT_TYPE);
    }

    /** 추가 질문 축의 정상 형상 — 위탁 시점에 보낸 문구가 콜백 수신부에서 함께 넘어온다. */
    private boolean applySub(String description) {
        return applier.applySubDescription(RAW_SN, description, QUESTION);
    }

    // ------------------------------------------------------------------ 수용기준 1·2

    @Test
    @DisplayName("추가질문_수신시_캡션본문이_채워지고_답변은_비어있다")
    void subDescriptionFillsCaptionTextAndLeavesAnswerEmpty() {
        boolean drafted = applySub(SUB_TEXT);

        assertThat(drafted).isTrue();
        EventAnnotationPayload payload = captureCreated();
        assertThat(payload.caption().get(CANDIDATE).captionText()).isEqualTo(SUB_TEXT);
        assertThat(payload.answer()).as("답변 칸은 사람이 확정할 공란이다").isNull();
        assertThat(payload.evidence()).as("근거 칸은 자동으로 채우지 않는다").isNull();
    }

    @Test
    @DisplayName("묘사_수신시_상황값이_사고단계_1단계에_들어가고_2단계_이후_키는_생기지_않는다")
    void descriptionFillsOnlyFirstCotStep() {
        boolean drafted = applier.applyDescription(RAW_SN, DESCRIPTION);

        assertThat(drafted).isTrue();
        EventAnnotationPayload payload = captureCreated();
        Map<String, String> cot = payload.caption().get(CANDIDATE).cot();
        assertThat(cot).containsOnlyKeys(FIRST_STEP).containsEntry(FIRST_STEP, SITUATION);
        assertThat(payload.answer()).isNull();
    }

    // ------------------------------------------------------------------ 수용기준 3·4

    @Test
    @DisplayName("상황_줄이_없으면_1단계_키가_생기지_않고_어노테이션에_손대지_않는다")
    void noSituationLineLeavesAnnotationUntouched() {
        boolean drafted = applier.applyDescription(RAW_SN, "- 장소: 주택가 골목\n- 날씨: 흐림");

        assertThat(drafted).isFalse();
        verifyNoInteractions(annoRepository);
    }

    @Test
    @DisplayName("상황_값은_그_줄까지만_담기고_라벨이_여러_번이면_첫_번째가_실린다")
    void situationValueStopsAtLineBreakAndUsesFirstOccurrence() {
        applier.applyDescription(RAW_SN,
                "- 상황: 첫 번째 상황\n- 날씨: 흐림\n- 상황: 두 번째 상황");

        Map<String, String> cot = captureCreated().caption().get(CANDIDATE).cot();
        assertThat(cot).containsEntry(FIRST_STEP, "첫 번째 상황");
        assertThat(cot.get(FIRST_STEP)).doesNotContain("날씨").doesNotContain("\n");
    }

    // ------------------------------------------------------------------ 수용기준 5 (도착 순서 무관)

    @Test
    @DisplayName("추가질문이_먼저_와도_뒤에_온_묘사가_같은_후보에_사고단계를_덧붙인다")
    void subThenDescriptionKeepsBothFields() {
        applySub(SUB_TEXT);
        EventAnnotationPayload afterSub = captureCreated();

        LsEvntAnno anno = stubExisting(afterSub);
        boolean drafted = applier.applyDescription(RAW_SN, DESCRIPTION);

        assertThat(drafted).isTrue();
        CaptionCandidate merged = captureUpdated(anno).caption().get(CANDIDATE);
        assertThat(merged.captionText()).isEqualTo(SUB_TEXT);
        assertThat(merged.cot()).containsEntry(FIRST_STEP, SITUATION);
    }

    @Test
    @DisplayName("묘사가_먼저_와도_뒤에_온_추가질문이_같은_후보에_캡션본문을_덧붙인다")
    void descriptionThenSubKeepsBothFields() {
        applier.applyDescription(RAW_SN, DESCRIPTION);
        EventAnnotationPayload afterDescription = captureCreated();

        LsEvntAnno anno = stubExisting(afterDescription);
        boolean drafted = applySub(SUB_TEXT);

        assertThat(drafted).isTrue();
        CaptionCandidate merged = captureUpdated(anno).caption().get(CANDIDATE);
        assertThat(merged.captionText()).isEqualTo(SUB_TEXT);
        assertThat(merged.cot()).containsEntry(FIRST_STEP, SITUATION);
    }

    @Test
    @DisplayName("다른_후보가_있어도_c1_만_다루고_그_후보는_건드리지_않는다")
    void otherCandidatesAreLeftIntact() {
        CaptionCandidate c2 = new CaptionCandidate("사람이 쓴 두 번째 후보", null);
        Map<String, CaptionCandidate> caption = new LinkedHashMap<>();
        caption.put("c2", c2);
        LsEvntAnno anno = stubExisting(payload(caption, null));

        applySub(SUB_TEXT);

        Map<String, CaptionCandidate> result = captureUpdated(anno).caption();
        assertThat(result.get("c2")).isEqualTo(c2);
        assertThat(result.get(CANDIDATE).captionText()).isEqualTo(SUB_TEXT);
    }

    // ------------------------------------------- 수용기준 6 (질문 칸 = 실제로 보낸 값)

    @Test
    @DisplayName("★질문은_위탁_시점에_실제로_보낸_문구가_그대로_실린다")
    void questionIsTheTextActuallySent() {
        String sent = "연기가 관측됩니까? 근거를 서술하십시오.";

        applier.applySubDescription(RAW_SN, SUB_TEXT, sent);

        assertThat(captureCreated().question())
                .as("보낸 질문 = 기록된 질문 — 콜백 시점에 다시 조달하지 않는다")
                .isEqualTo(sent);
    }

    @Test
    @DisplayName("질문이_이미_있으면_덮지_않는다")
    void doesNotOverwriteExistingQuestion() {
        String humanQuestion = "사람이 고쳐 쓴 질문";
        LsEvntAnno anno = stubExisting(new EventAnnotationPayload(
                EVENT_CLASS, humanQuestion, null, null, null));

        applySub(SUB_TEXT);

        assertThat(captureUpdated(anno).question()).isEqualTo(humanQuestion);
    }

    @Test
    @DisplayName("★보낸_질문이_없으면_질문은_비어있고_지어내지_않는다")
    void leavesQuestionEmptyWhenNothingWasSent() {
        // 조달이 비어 질문 없이 위탁된 건 — 사업자가 받은 질문이 없으므로 기록할 질문도 없다.
        applier.applySubDescription(RAW_SN, SUB_TEXT, null);

        EventAnnotationPayload payload = captureCreated();
        assertThat(payload.question()).isNull();
        assertThat(payload.caption().get(CANDIDATE).captionText()).isEqualTo(SUB_TEXT);
    }

    @Test
    @DisplayName("★보관값이_없는_과거_행은_소급해_채우지_않는다_공백도_마찬가지")
    void doesNotBackfillLegacyRowsWithoutStoredQuestion() {
        // 보관 도입 이전 행은 null 이고, 그때 무엇을 보냈는지 알 수 없다.
        // 공백 문자열도 「보낸 질문이 있다」로 읽지 않는다.
        applier.applySubDescription(RAW_SN, SUB_TEXT, "   ");

        assertThat(captureCreated().question()).isNull();
    }

    // ------------------------------------- 수용기준 1·5·7 (재조달이 사라졌다 — 구조로 고정)

    @Test
    @DisplayName("★묘사_축은_질문을_보내지_않으므로_질문_칸을_채우지_않는다")
    void descriptionAxisDoesNotFillQuestion() {
        applier.applyDescription(RAW_SN, DESCRIPTION);

        EventAnnotationPayload payload = captureCreated();
        assertThat(payload.question())
                .as("묘사 축 위탁 본문에는 질문이 없다 — 보낸 적 없는 값을 지어내지 않는다")
                .isNull();
        assertThat(payload.caption().get(CANDIDATE).cot()).containsEntry(FIRST_STEP, SITUATION);
    }

    @Test
    @DisplayName("★추가질문이_질문을_채운_뒤_묘사가_와도_그_질문을_덮거나_지우지_않는다")
    void laterDescriptionKeepsTheSentQuestion() {
        applySub(SUB_TEXT);
        LsEvntAnno anno = stubExisting(captureCreated());

        applier.applyDescription(RAW_SN, DESCRIPTION);

        assertThat(captureUpdated(anno).question()).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("★★조달_판정기도_마킹_선택값_읽기도_협력자로_갖지_않는다_재조달_경로_소멸")
    void hasNoRequeryCollaboratorsAtAll() {
        List<Class<?>> types = Arrays.stream(EvntAnnoResultApplier.class.getDeclaredFields())
                .map(Field::getType)
                .collect(java.util.stream.Collectors.toList());

        assertThat(types)
                .as("질문 목록은 전체 교체로 저장되어 가리키던 행이 사라지는 것이 정상 동선이다. "
                        + "콜백 시점에 다시 조달하면 보낸 질문과 기록된 질문이 갈린다 — "
                        + "그래서 조달 경로를 협력자 수준에서 없앤다(호출을 지우는 것만으로는 되살아난다)")
                .doesNotContain(VerificationEventQuestionResolver.class)
                .doesNotContain(MarkingSelectedQuestionReader.class);
    }

    // ------------------------------------------------------------------ 수용기준 8 (사고단계 상한)

    @Test
    @DisplayName("상황값이_사고단계_상한을_넘으면_그_칸만_건너뛰고_먼저_채워진_값은_그대로_둔다")
    void overLimitSituationSkipsOnlyTheCotStep() {
        // 추가 질문 축이 먼저 도착해 캡션 본문과 질문을 채워 둔 상태
        applySub(SUB_TEXT);
        LsEvntAnno anno = stubExisting(captureCreated());
        String tooLong = "가".repeat(EventAnnotationPayload.MAX_COT_STEP + 1);

        boolean drafted = applier.applyDescription(RAW_SN, "- 상황: " + tooLong);

        assertThat(drafted).as("채울 칸이 없으므로 아무것도 바꾸지 않는다").isFalse();
        verify(anno, never()).updatePayload(anyString(), anyString());
        EventAnnotationPayload kept = EventAnnotationPayload.fromJson(anno.getAnnoCn());
        assertThat(kept.question()).as("먼저 채워진 값은 그대로다").isEqualTo(QUESTION);
        assertThat(kept.caption().get(CANDIDATE).captionText()).isEqualTo(SUB_TEXT);
        assertThat(kept.caption().get(CANDIDATE).cot())
                .as("상한을 넘는 값을 잘라 넣지 않는다")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("상한을_넘으면_분류만_든_껍데기_행을_만들지_않는다_묘사축은_질문도_안_채운다")
    void overLimitCreatesNothing() {
        String tooLong = "가".repeat(EventAnnotationPayload.MAX_COT_STEP + 1);

        boolean drafted = applier.applyDescription(RAW_SN, "- 상황: " + tooLong);

        assertThat(drafted).as("자르지도 던지지도 않는다").isFalse();
        verify(annoRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ 수용기준 7 (보호 경계)

    @Test
    @DisplayName("승인_이력이_있으면_현재_상태와_무관하게_손대지_않는다")
    void skipsWhenEverApproved() {
        when(approvalGate.hasEverApproved(RAW_SN)).thenReturn(true);

        assertThat(applySub(SUB_TEXT)).isFalse();
        assertThat(applier.applyDescription(RAW_SN, DESCRIPTION)).isFalse();
        verifyNoInteractions(annoRepository);
    }

    @Test
    @DisplayName("채울_칸에_이미_값이_있으면_덮지_않는다")
    void doesNotOverwriteFilledFields() {
        Map<String, CaptionCandidate> caption = new LinkedHashMap<>();
        Map<String, String> cot = new LinkedHashMap<>();
        cot.put(FIRST_STEP, "사람이 쓴 사고 단계");
        caption.put(CANDIDATE, new CaptionCandidate("사람이 쓴 캡션", cot));
        LsEvntAnno anno = stubExisting(payload(caption, QUESTION));

        assertThat(applySub(SUB_TEXT)).isFalse();
        assertThat(applier.applyDescription(RAW_SN, DESCRIPTION)).isFalse();
        verify(anno, never()).updatePayload(anyString(), anyString());
    }

    @Test
    @DisplayName("어노테이션_검토가_승인으로_종결됐으면_손대지_않는다")
    void skipsWhenReviewApproved() {
        assertSkippedForReviewStatus(LsEvntAnnoReview.STTS_APPROVED);
    }

    @Test
    @DisplayName("어노테이션_검토가_반려로_종결됐으면_손대지_않는다")
    void skipsWhenReviewRejected() {
        assertSkippedForReviewStatus(LsEvntAnnoReview.STTS_REJECTED);
    }

    @Test
    @DisplayName("검토가_아직_대기중이면_채운다")
    void fillsWhenReviewStillPending() {
        LsEvntAnno anno = stubExisting(payload(null, null));
        stubReviewStatus(LsEvntAnnoReview.STTS_PENDING);

        assertThat(applySub(SUB_TEXT)).isTrue();
        assertThat(captureUpdated(anno).caption().get(CANDIDATE).captionText()).isEqualTo(SUB_TEXT);
    }

    @Test
    @DisplayName("같은_결과를_다시_받아도_두_번째부터는_아무것도_하지_않는다_멱등")
    void isIdempotentOnDuplicateCallback() {
        applySub(SUB_TEXT);
        EventAnnotationPayload first = captureCreated();

        LsEvntAnno anno = stubExisting(first);
        boolean second = applySub(SUB_TEXT);

        assertThat(second).isFalse();
        verify(anno, never()).updatePayload(anyString(), anyString());
    }

    @Test
    @DisplayName("검증이벤트유형이_어느_쪽에도_없으면_분류를_지어내지_않고_행을_만들지_않는다")
    void skipsCreateWhenEventClassUnavailable() {
        stubIngestType(null);

        assertThat(applySub(SUB_TEXT)).isFalse();
        assertThat(applier.applyDescription(RAW_SN, DESCRIPTION)).isFalse();
        verify(annoRepository, never()).save(any());
    }

    @Test
    @DisplayName("★관제가_유형을_보내지_않아도_마킹에서_고른_유형이_있으면_그_값으로_초안이_만들어진다")
    void createsDraftFromMarkingSelectedTypeWhenIngestHasNone() {
        stubIngestType(null);
        stubMarkingSelectedType("flooding");

        assertThat(applySub(SUB_TEXT))
                .as("관제 미수신 영상이 어노테이션 초안을 영영 못 받던 자리다 — 2순위가 그것을 연다")
                .isTrue();
        assertThat(captureCreated().eventClass()).isEqualTo("flooding");
    }

    @Test
    @DisplayName("★관제_유형이_있으면_1순위_그대로다_마킹_선택값이_있어도_밀리지_않는다")
    void ingestTypeStaysFirstEvenWhenMarkingAlsoHasOne() {
        stubMarkingSelectedType("flooding");

        assertThat(applySub(SUB_TEXT)).isTrue();
        assertThat(captureCreated().eventClass())
                .as("관제 값이 있으면 그것이 진실원이다")
                .isEqualTo(EVENT_CLASS);
    }

    @Test
    @DisplayName("★마킹에서_분류만_얻고_채울_다른_값이_없으면_껍데기_행을_만들지_않는다")
    void doesNotCreateShellRowWhenOnlyEventClassIsAvailable() {
        stubIngestType(null);
        stubMarkingSelectedType("flooding");
        // 「상황」 줄은 있으나 값이 사고 단계 상한을 넘어 그 칸만 건너뛴다 — 분류는 얻었는데
        //  채울 칸이 하나도 없는 형상이며, 2순위가 열리면서 새로 도달 가능해진 자리다.
        String tooLong = "가".repeat(EventAnnotationPayload.MAX_COT_STEP + 1);

        assertThat(applier.applyDescription(RAW_SN, "- 상황: " + tooLong)).isFalse();
        verify(annoRepository, never()).save(any());
    }

    @Test
    @DisplayName("인입_행_자체가_없어도_예외로_터지지_않고_행을_만들지_않는다")
    void skipsCreateWhenIngestRowMissing() {
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);

        assertThat(applySub(SUB_TEXT)).isFalse();
        verify(annoRepository, never()).save(any());
    }

    @Test
    @DisplayName("본문을_읽지_못하면_예외를_던지지_않고_건너뛴다_주축_롤백_방지")
    void skipsWhenPayloadNotReadable() {
        LsEvntAnno anno = mock(LsEvntAnno.class);
        when(anno.getAnnoCn()).thenReturn("{ not json");
        when(annoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(anno));

        assertThatCode(() -> {
            assertThat(applySub(SUB_TEXT)).isFalse();
            assertThat(applier.applyDescription(RAW_SN, DESCRIPTION)).isFalse();
        }).doesNotThrowAnyException();
        verify(anno, never()).updatePayload(anyString(), anyString());
    }

    @Test
    @DisplayName("자동_채움의_등록자_표기는_SYSTEM_이다")
    void marksDraftActor() {
        LsEvntAnno anno = stubExisting(payload(null, null));

        applySub(SUB_TEXT);

        verify(anno).updatePayload(anyString(), eq(DRAFT_ACTOR));
    }

    @Test
    @DisplayName("재검수를_발화시키지_않는다_이벤트_발행_협력자를_아예_갖지_않는다")
    void doesNotPublishAnyEvent() {
        boolean hasPublisher = Arrays.stream(EvntAnnoResultApplier.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(ApplicationEventPublisher.class::isAssignableFrom);

        assertThat(hasPublisher)
                .as("자동 채움은 사람이 고치는 축이 아니라 재검수를 발화시키면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("rawSn_이나_서술이_비어있으면_아무것도_하지_않는다")
    void ignoresBlankInput() {
        assertThat(applier.applySubDescription(null, SUB_TEXT, QUESTION)).isFalse();
        assertThat(applier.applySubDescription(RAW_SN, "   ", QUESTION)).isFalse();
        assertThat(applier.applySubDescription(RAW_SN, null, QUESTION)).isFalse();
        assertThat(applier.applyDescription(null, DESCRIPTION)).isFalse();
        assertThat(applier.applyDescription(RAW_SN, "  ")).isFalse();
        assertThat(applier.applyDescription(RAW_SN, null)).isFalse();
        verifyNoInteractions(annoRepository, approvalGate);
    }

    // ------------------------------------------------------- 사업자 실응답 원문 (2026-09-14)
    // 원문 출처·보존 규칙은 fixtures/vlm-klid-live-20260914/README.md. [design: CDIAG-014] [design: ADR-051]

    @Test
    @DisplayName("실응답_교통사고_묘사를_받으면_상황값이_사고단계_1단계에_원문_그대로_실린다")
    void liveCarAccidentDescriptionFillsFirstCotStep() {
        stubIngestType("car_accident");

        boolean drafted = applier.applyDescription(RAW_SN,
                VlmKlidLiveFixtures.description(VlmKlidLiveFixtures.DESCRIBE_CAR_ACCIDENT));

        assertThat(drafted).isTrue();
        EventAnnotationPayload payload = captureCreated();
        assertThat(payload.eventClass()).isEqualTo("car_accident");
        assertThat(payload.caption().get(CANDIDATE).cot())
                .containsOnlyKeys(FIRST_STEP)
                .containsEntry(FIRST_STEP, VlmKlidLiveFixtures.DESCRIBE_CAR_ACCIDENT_SITUATION);
        assertThat(payload.question()).as("묘사 축은 질문을 보내지 않는다").isNull();
    }

    @Test
    @DisplayName("실응답_화재_묘사를_받으면_대시_접두_형식에서도_상황값이_사고단계_1단계에_원문_그대로_실린다")
    void liveFireDescriptionFillsFirstCotStep() {
        boolean drafted = applier.applyDescription(RAW_SN,
                VlmKlidLiveFixtures.description(VlmKlidLiveFixtures.DESCRIBE_FIRE));

        assertThat(drafted).isTrue();
        assertThat(captureCreated().caption().get(CANDIDATE).cot())
                .containsOnlyKeys(FIRST_STEP)
                .containsEntry(FIRST_STEP, VlmKlidLiveFixtures.DESCRIBE_FIRE_SITUATION);
    }

    @Test
    @DisplayName("실응답_교통사고_추가질문을_받으면_마크다운_원문이_캡션본문에_그대로_실리고_질문은_보낸_문구다")
    void liveCarAccidentCustomFillsCaptionTextVerbatim() {
        stubIngestType("car_accident");
        String original = VlmKlidLiveFixtures.description(VlmKlidLiveFixtures.CUSTOM_CAR_ACCIDENT);

        boolean drafted = applier.applySubDescription(RAW_SN, original,
                VlmKlidLiveFixtures.CUSTOM_CAR_ACCIDENT_SENT_QUESTION);

        assertThat(drafted).isTrue();
        EventAnnotationPayload payload = captureCreated();
        String captionText = payload.caption().get(CANDIDATE).captionText();
        assertThat(captionText).as("캡션 본문은 원문과 한 글자도 달라지면 안 된다").isEqualTo(original);
        // 원문의 형식 요소가 실제로 남아 있는지 리터럴로 한 번 더 고정한다 — 원문이 다듬어져 들어오면 여기가 깨진다.
        assertThat(captionText)
                .contains("### 근거:\n1. **모든 차량이 정상적으로 주행**:  \n")
                .contains("\n\n---\n\n");
        assertThat(payload.question()).isEqualTo(VlmKlidLiveFixtures.CUSTOM_CAR_ACCIDENT_SENT_QUESTION);
        assertThat(payload.answer()).as("답변 칸은 사람이 확정할 공란이다").isNull();
    }

    @Test
    @DisplayName("실응답_화재_추가질문을_받으면_글머리표_원문이_캡션본문에_그대로_실리고_질문은_보낸_문구다")
    void liveFireCustomFillsCaptionTextVerbatim() {
        String original = VlmKlidLiveFixtures.description(VlmKlidLiveFixtures.CUSTOM_FIRE);

        boolean drafted = applier.applySubDescription(RAW_SN, original,
                VlmKlidLiveFixtures.CUSTOM_FIRE_SENT_QUESTION);

        assertThat(drafted).isTrue();
        EventAnnotationPayload payload = captureCreated();
        assertThat(payload.caption().get(CANDIDATE).captionText())
                .isEqualTo(original)
                .contains("### 근거:\n- 영상 전체를 검토한 결과");
        assertThat(payload.question()).isEqualTo(VlmKlidLiveFixtures.CUSTOM_FIRE_SENT_QUESTION);
    }

    @Test
    @DisplayName("실응답_두_창구가_모두_오면_같은_후보에_상황값과_캡션본문이_함께_남는다")
    void liveBothWindowsLandOnSameCandidate() {
        stubIngestType("car_accident");
        String custom = VlmKlidLiveFixtures.description(VlmKlidLiveFixtures.CUSTOM_CAR_ACCIDENT);
        applier.applyDescription(RAW_SN,
                VlmKlidLiveFixtures.description(VlmKlidLiveFixtures.DESCRIBE_CAR_ACCIDENT));
        LsEvntAnno anno = stubExisting(captureCreated());

        boolean drafted = applier.applySubDescription(RAW_SN, custom,
                VlmKlidLiveFixtures.CUSTOM_CAR_ACCIDENT_SENT_QUESTION);

        assertThat(drafted).isTrue();
        EventAnnotationPayload merged = captureUpdated(anno);
        assertThat(merged.caption().get(CANDIDATE).captionText()).isEqualTo(custom);
        assertThat(merged.caption().get(CANDIDATE).cot())
                .containsEntry(FIRST_STEP, VlmKlidLiveFixtures.DESCRIBE_CAR_ACCIDENT_SITUATION);
        assertThat(merged.question()).isEqualTo(VlmKlidLiveFixtures.CUSTOM_CAR_ACCIDENT_SENT_QUESTION);
    }

    // ------------------------------------------------------------------ helpers

    private void assertSkippedForReviewStatus(String status) {
        LsEvntAnno anno = stubExisting(payload(null, null));
        stubReviewStatus(status);

        assertThat(applySub(SUB_TEXT)).isFalse();
        assertThat(applier.applyDescription(RAW_SN, DESCRIPTION)).isFalse();
        verify(anno, never()).updatePayload(anyString(), anyString());
    }

    private void stubReviewStatus(String status) {
        LsEvntAnnoReview review = mock(LsEvntAnnoReview.class);
        when(review.getRvwSttsCd()).thenReturn(status);
        when(reviewRepository.findByEvntAnnoSn(EVNT_ANNO_SN)).thenReturn(List.of(review));
    }

    private void stubIngestType(String vrfcEvntTypeCd) {
        IngestSourceRow row = mock(IngestSourceRow.class);
        when(row.getVrfcEvntTypeCd()).thenReturn(vrfcEvntTypeCd);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(row);
    }

    /** 관제가 유형을 보내지 않은 영상에서 작업자가 마킹 화면에서 고른 유형. */
    private void stubMarkingSelectedType(String vrfcEvntTypeCd) {
        LsMarking marking = mock(LsMarking.class);
        when(marking.getVrfcEvntTypeCd()).thenReturn(vrfcEvntTypeCd);
        when(markingRepository.findByRawSnAndSttsCdIn(eq(RAW_SN), any())).thenReturn(List.of(marking));
    }

    private static EventAnnotationPayload payload(Map<String, CaptionCandidate> caption, String question) {
        return new EventAnnotationPayload(EVENT_CLASS, question, caption, null, null);
    }

    private LsEvntAnno stubExisting(EventAnnotationPayload payload) {
        LsEvntAnno anno = mock(LsEvntAnno.class);
        when(anno.getAnnoCn()).thenReturn(payload.toJson());
        when(anno.getEvntAnnoSn()).thenReturn(EVNT_ANNO_SN);
        when(annoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(anno));
        return anno;
    }

    private EventAnnotationPayload captureCreated() {
        ArgumentCaptor<LsEvntAnno> captor = ArgumentCaptor.forClass(LsEvntAnno.class);
        verify(annoRepository).save(captor.capture());
        return EventAnnotationPayload.fromJson(captor.getValue().getAnnoCn());
    }

    private EventAnnotationPayload captureUpdated(LsEvntAnno anno) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(anno).updatePayload(captor.capture(), eq(DRAFT_ACTOR));
        return EventAnnotationPayload.fromJson(captor.getValue());
    }
}
