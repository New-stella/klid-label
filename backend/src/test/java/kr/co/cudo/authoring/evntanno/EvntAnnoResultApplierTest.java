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
 * {@code caption.c1.cot["1단계"]} · 질문 → 검증 이벤트 유형별 질문 문구 카탈로그.
 * {@code answer}·{@code evidence}·2단계 이후는 <b>사람이 채울 공란</b>이다.
 */
class EvntAnnoResultApplierTest {

    private static final long RAW_SN = 4200L;
    private static final long EVNT_ANNO_SN = 77L;
    private static final String VRFC_EVNT_TYPE = "fire";
    private static final String EVENT_CLASS = "fire";
    private static final long SELECTED_QSTN_SN = 512L;
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
    private ReviewApprovalGate approvalGate;
    private VerificationEventQuestionResolver questionResolver;
    private MarkingSelectedQuestionReader selectedQuestionReader;

    private EvntAnnoResultApplier applier;

    @BeforeEach
    void setUp() {
        annoRepository = mock(LsEvntAnnoRepository.class);
        reviewRepository = mock(LsEvntAnnoReviewRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        approvalGate = mock(ReviewApprovalGate.class);
        questionResolver = mock(VerificationEventQuestionResolver.class);
        selectedQuestionReader = mock(MarkingSelectedQuestionReader.class);
        applier = new EvntAnnoResultApplier(annoRepository, reviewRepository,
                ingestSourceRepository, approvalGate, questionResolver, selectedQuestionReader);

        stubIngestType(VRFC_EVNT_TYPE);
        // 기본 형상 = 마킹이 질문을 고르지 않았다 — 조달 판정기가 「첫 번째」로 되돌린다.
        // ⚠ null 을 명시적으로 지정해야 한다. Mockito 는 래퍼 반환 타입에 null 이 아니라 기본값(0L)을
        //   돌려주므로, 이 줄이 없으면 선택값 0 이 흘러가 아래 조달 스텁이 조용히 빗나간다.
        when(selectedQuestionReader.findSelectedQuestionSn(RAW_SN)).thenReturn(null);
        when(questionResolver.resolveQuestionText(null, VRFC_EVNT_TYPE)).thenReturn(Optional.of(QUESTION));
    }

    // ------------------------------------------------------------------ 수용기준 1·2

    @Test
    @DisplayName("추가질문_수신시_캡션본문이_채워지고_답변은_비어있다")
    void subDescriptionFillsCaptionTextAndLeavesAnswerEmpty() {
        boolean drafted = applier.applySubDescription(RAW_SN, SUB_TEXT);

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
        applier.applySubDescription(RAW_SN, SUB_TEXT);
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
        boolean drafted = applier.applySubDescription(RAW_SN, SUB_TEXT);

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

        applier.applySubDescription(RAW_SN, SUB_TEXT);

        Map<String, CaptionCandidate> result = captureUpdated(anno).caption();
        assertThat(result.get("c2")).isEqualTo(c2);
        assertThat(result.get(CANDIDATE).captionText()).isEqualTo(SUB_TEXT);
    }

    // ------------------------------------------------------------------ 수용기준 6 (질문 조달)

    @Test
    @DisplayName("질문은_검증이벤트유형별_질문문구_카탈로그에서_조달된다")
    void questionComesFromCatalog() {
        applier.applySubDescription(RAW_SN, SUB_TEXT);

        assertThat(captureCreated().question()).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("질문이_이미_있으면_덮지_않는다")
    void doesNotOverwriteExistingQuestion() {
        String humanQuestion = "사람이 고쳐 쓴 질문";
        LsEvntAnno anno = stubExisting(new EventAnnotationPayload(
                EVENT_CLASS, humanQuestion, null, null, null));

        applier.applySubDescription(RAW_SN, SUB_TEXT);

        assertThat(captureUpdated(anno).question()).isEqualTo(humanQuestion);
    }

    @Test
    @DisplayName("유형이나_질문이_카탈로그에_없으면_질문은_비어있고_지어내지_않는다")
    void leavesQuestionEmptyWhenCatalogHasNone() {
        when(questionResolver.resolveQuestionText(null, VRFC_EVNT_TYPE)).thenReturn(Optional.empty());

        applier.applySubDescription(RAW_SN, SUB_TEXT);

        EventAnnotationPayload payload = captureCreated();
        assertThat(payload.question()).isNull();
        assertThat(payload.caption().get(CANDIDATE).captionText()).isEqualTo(SUB_TEXT);
    }

    // ------------------------------------------------------- 수용기준 1·5·7 (마킹 선택 질문 배선)

    @Test
    @DisplayName("★마킹이_고른_질문이_있으면_그_질문이_실린다")
    void usesQuestionSelectedByMarking() {
        String selectedText = "연기가 관측됩니까? 근거를 서술하십시오.";
        when(selectedQuestionReader.findSelectedQuestionSn(RAW_SN)).thenReturn(SELECTED_QSTN_SN);
        when(questionResolver.resolveQuestionText(SELECTED_QSTN_SN, VRFC_EVNT_TYPE))
                .thenReturn(Optional.of(selectedText));

        applier.applySubDescription(RAW_SN, SUB_TEXT);

        assertThat(captureCreated().question())
                .as("1순위 조달값은 마킹이 고른 질문이다")
                .isEqualTo(selectedText);
    }

    @Test
    @DisplayName("★묘사_축에서도_마킹이_고른_질문이_실린다_두_창구_동일")
    void usesQuestionSelectedByMarkingOnDescriptionAxisToo() {
        String selectedText = "연기가 관측됩니까? 근거를 서술하십시오.";
        when(selectedQuestionReader.findSelectedQuestionSn(RAW_SN)).thenReturn(SELECTED_QSTN_SN);
        when(questionResolver.resolveQuestionText(SELECTED_QSTN_SN, VRFC_EVNT_TYPE))
                .thenReturn(Optional.of(selectedText));

        applier.applyDescription(RAW_SN, DESCRIPTION);

        assertThat(captureCreated().question()).isEqualTo(selectedText);
    }

    @Test
    @DisplayName("★소속_판정과_첫번째_폴백은_조달_판정기의_몫이라_선택값을_그대로_넘긴다")
    void passesSelectedValueThroughWithoutRejudging() {
        // 그 유형에 속하지 않는 선택값이어도 여기서 거르지 않는다 — 판정기가 첫 번째로 교정한다.
        long strayQstnSn = 999_999L;
        when(selectedQuestionReader.findSelectedQuestionSn(RAW_SN)).thenReturn(strayQstnSn);
        when(questionResolver.resolveQuestionText(strayQstnSn, VRFC_EVNT_TYPE))
                .thenReturn(Optional.of(QUESTION));

        applier.applySubDescription(RAW_SN, SUB_TEXT);

        verify(questionResolver).resolveQuestionText(strayQstnSn, VRFC_EVNT_TYPE);
        assertThat(captureCreated().question())
                .as("판정기가 되돌린 첫 번째 질문이 그대로 실린다")
                .isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("★질문이_이미_있으면_마킹을_읽지도_않는다")
    void doesNotReadMarkingWhenQuestionAlreadyPresent() {
        stubExisting(new EventAnnotationPayload(EVENT_CLASS, "사람이 고쳐 쓴 질문", null, null, null));

        applier.applySubDescription(RAW_SN, SUB_TEXT);

        verify(selectedQuestionReader, never()).findSelectedQuestionSn(any());
    }

    // ------------------------------------------------------------------ 수용기준 8 (사고단계 상한)

    @Test
    @DisplayName("상황값이_사고단계_상한을_넘으면_그_칸만_건너뛰고_나머지_적재는_성공한다")
    void overLimitSituationSkipsOnlyTheCotStep() {
        String tooLong = "가".repeat(EventAnnotationPayload.MAX_COT_STEP + 1);

        boolean drafted = applier.applyDescription(RAW_SN, "- 상황: " + tooLong);

        assertThat(drafted).as("자르지도 던지지도 않고 나머지 적재는 진행한다").isTrue();
        EventAnnotationPayload payload = captureCreated();
        assertThat(payload.question()).isEqualTo(QUESTION);
        assertThat(payload.caption()).as("상한을 넘는 값을 잘라 넣지 않는다").isNullOrEmpty();
    }

    @Test
    @DisplayName("상한을_넘고_질문도_없으면_분류만_든_껍데기_행을_만들지_않는다")
    void overLimitWithNoQuestionCreatesNothing() {
        when(questionResolver.resolveQuestionText(null, VRFC_EVNT_TYPE)).thenReturn(Optional.empty());
        String tooLong = "가".repeat(EventAnnotationPayload.MAX_COT_STEP + 1);

        boolean drafted = applier.applyDescription(RAW_SN, "- 상황: " + tooLong);

        assertThat(drafted).isFalse();
        verify(annoRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ 수용기준 7 (보호 경계)

    @Test
    @DisplayName("승인_이력이_있으면_현재_상태와_무관하게_손대지_않는다")
    void skipsWhenEverApproved() {
        when(approvalGate.hasEverApproved(RAW_SN)).thenReturn(true);

        assertThat(applier.applySubDescription(RAW_SN, SUB_TEXT)).isFalse();
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

        assertThat(applier.applySubDescription(RAW_SN, SUB_TEXT)).isFalse();
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

        assertThat(applier.applySubDescription(RAW_SN, SUB_TEXT)).isTrue();
        assertThat(captureUpdated(anno).caption().get(CANDIDATE).captionText()).isEqualTo(SUB_TEXT);
    }

    @Test
    @DisplayName("같은_결과를_다시_받아도_두_번째부터는_아무것도_하지_않는다_멱등")
    void isIdempotentOnDuplicateCallback() {
        applier.applySubDescription(RAW_SN, SUB_TEXT);
        EventAnnotationPayload first = captureCreated();

        LsEvntAnno anno = stubExisting(first);
        boolean second = applier.applySubDescription(RAW_SN, SUB_TEXT);

        assertThat(second).isFalse();
        verify(anno, never()).updatePayload(anyString(), anyString());
    }

    @Test
    @DisplayName("검증이벤트유형이_없으면_분류를_지어내지_않고_행을_만들지_않는다")
    void skipsCreateWhenEventClassUnavailable() {
        stubIngestType(null);

        assertThat(applier.applySubDescription(RAW_SN, SUB_TEXT)).isFalse();
        assertThat(applier.applyDescription(RAW_SN, DESCRIPTION)).isFalse();
        verify(annoRepository, never()).save(any());
    }

    @Test
    @DisplayName("인입_행_자체가_없어도_예외로_터지지_않고_행을_만들지_않는다")
    void skipsCreateWhenIngestRowMissing() {
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);

        assertThat(applier.applySubDescription(RAW_SN, SUB_TEXT)).isFalse();
        verify(annoRepository, never()).save(any());
    }

    @Test
    @DisplayName("본문을_읽지_못하면_예외를_던지지_않고_건너뛴다_주축_롤백_방지")
    void skipsWhenPayloadNotReadable() {
        LsEvntAnno anno = mock(LsEvntAnno.class);
        when(anno.getAnnoCn()).thenReturn("{ not json");
        when(annoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(anno));

        assertThatCode(() -> {
            assertThat(applier.applySubDescription(RAW_SN, SUB_TEXT)).isFalse();
            assertThat(applier.applyDescription(RAW_SN, DESCRIPTION)).isFalse();
        }).doesNotThrowAnyException();
        verify(anno, never()).updatePayload(anyString(), anyString());
    }

    @Test
    @DisplayName("자동_채움의_등록자_표기는_SYSTEM_이다")
    void marksDraftActor() {
        LsEvntAnno anno = stubExisting(payload(null, null));

        applier.applySubDescription(RAW_SN, SUB_TEXT);

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
        assertThat(applier.applySubDescription(null, SUB_TEXT)).isFalse();
        assertThat(applier.applySubDescription(RAW_SN, "   ")).isFalse();
        assertThat(applier.applySubDescription(RAW_SN, null)).isFalse();
        assertThat(applier.applyDescription(null, DESCRIPTION)).isFalse();
        assertThat(applier.applyDescription(RAW_SN, "  ")).isFalse();
        assertThat(applier.applyDescription(RAW_SN, null)).isFalse();
        verifyNoInteractions(annoRepository, approvalGate);
    }

    // ------------------------------------------------------------------ helpers

    private void assertSkippedForReviewStatus(String status) {
        LsEvntAnno anno = stubExisting(payload(null, null));
        stubReviewStatus(status);

        assertThat(applier.applySubDescription(RAW_SN, SUB_TEXT)).isFalse();
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
