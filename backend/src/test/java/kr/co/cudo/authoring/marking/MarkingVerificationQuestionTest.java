package kr.co.cudo.authoring.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.dto.MarkingResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingService;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntQstn;
import kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntQstnRepository;
import kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 마킹이 <b>작업자가 고른 검증 이벤트 질문</b>을 보관하는 계약. [design: AC-028 · ERD-013 · API-047]
 *
 * <h3>왜 조달 판정기를 목킹하지 않는가</h3>
 * <p>수용기준의 핵심은 "어긋난 선택값을 <b>거부하지 않고 교정</b>한다"이므로, 판정기를 목킹하면 그 교정이
 * 실제로 일어났는지가 아니라 위임했는지만 검증하게 된다. 그래서 여기서는 <b>실제
 * {@link VerificationEventQuestionResolver}</b> 를 질문 리포지토리 목 위에 얹어, 마킹 저장 경로 전체를
 * 통과시킨 결과를 본다. 판정기 자체의 단독 계약은 {@code VerificationEventQuestionResolverIT} 가 맡는다.
 *
 * <h3>호출 지점</h3>
 * <p>persist 트랜잭션 진입점({@code create(rawSn, req, actor, durationSec)})을 직접 부른다 — 오케스트레이션
 * (프리체크·ffprobe)은 이 축과 무관하므로 그 협력자는 {@code null} 로 둔다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkingVerificationQuestionTest {

    private static final long RAW_SN = 4101L;

    /** 그 영상의 검증 이벤트 유형 — 관제 인입값. */
    private static final String EVENT_TYPE = "fire";

    /** 같은 유형의 첫 번째 질문(정렬순서 1) — 미선택·교정의 착지점. */
    private static final long FIRST_QSTN_SN = 11L;

    /** 같은 유형의 두 번째 질문(정렬순서 2) — 작업자가 고를 수 있는 값. */
    private static final long SECOND_QSTN_SN = 12L;

    /** <b>다른</b> 유형의 질문 — 그대로 저장되면 안 되고 첫 번째로 교정돼야 한다. */
    private static final long FOREIGN_QSTN_SN = 99L;

    @Mock private LsMarkingRepository markingRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private LsTaskAssignmentRepository assignmentRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private VideoFpsResolver fpsResolver;
    @Mock private IngestSourceRepository ingestSourceRepository;
    @Mock private LsVrfcEvntQstnRepository questionRepository;

    private MarkingService markingService;

    @BeforeEach
    void setUp() {
        VerificationEventQuestionResolver resolver =
                new VerificationEventQuestionResolver(questionRepository);
        markingService = new MarkingService(
                markingRepository, videoRepository, assignmentRepository, new ObjectMapper(),
                eventPublisher, fpsResolver,
                /* precheckReader */ null, /* durationResolver */ null,
                ingestSourceRepository, resolver);

        when(fpsResolver.resolveFps(anyLong())).thenReturn(VideoFpsResolver.DEFAULT_FPS);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(markableRaw()));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        stubEventType(EVENT_TYPE);

        // 유형 'fire' 의 질문 2건 — 정렬순서 1 이 「첫 번째」다.
        when(questionRepository.findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(EVENT_TYPE))
                .thenReturn(Optional.of(question(FIRST_QSTN_SN, EVENT_TYPE, 1, "첫 번째 질문")));
        when(questionRepository.findById(FIRST_QSTN_SN))
                .thenReturn(Optional.of(question(FIRST_QSTN_SN, EVENT_TYPE, 1, "첫 번째 질문")));
        when(questionRepository.findById(SECOND_QSTN_SN))
                .thenReturn(Optional.of(question(SECOND_QSTN_SN, EVENT_TYPE, 2, "두 번째 질문")));
        // 다른 유형('fall')에 딸린 질문 — 소속 검증에서 탈락해야 한다.
        when(questionRepository.findById(FOREIGN_QSTN_SN))
                .thenReturn(Optional.of(question(FOREIGN_QSTN_SN, "fall", 1, "다른 유형의 질문")));
    }

    @Test
    @DisplayName("작업자가_고른_질문이_그대로_저장된다")
    void selectedQuestionIsPersisted() {
        MarkingResponse response = createManualMarking(SECOND_QSTN_SN);

        assertThat(savedMarking().getVrfcEvntQstnSn()).isEqualTo(SECOND_QSTN_SN);
        assertThat(response.status()).isEqualTo(LsMarking.STATUS_PENDING);
    }

    @Test
    @DisplayName("질문을_고르지_않으면_그_유형의_첫번째_질문이_저장된다")
    void unselectedFallsBackToFirstQuestion() {
        createManualMarking(null);

        assertThat(savedMarking().getVrfcEvntQstnSn()).isEqualTo(FIRST_QSTN_SN);
    }

    @Test
    @DisplayName("다른_유형의_질문을_보내면_400이_아니라_첫번째_질문으로_교정된다")
    void foreignQuestionIsCorrectedNotRejected() {
        // 확정 정책: 화면 입력을 신뢰하지 않되 마킹을 막지도 않는다. 질문 목록이 전체 교체돼
        // 가리키던 행이 사라지는 것은 정상 동선이라, 그것이 사용자에겐 원인 불명의 실패로 보이면 안 된다.
        assertThatCode(() -> createManualMarking(FOREIGN_QSTN_SN)).doesNotThrowAnyException();

        assertThat(savedMarking().getVrfcEvntQstnSn()).isEqualTo(FIRST_QSTN_SN);
    }

    @Test
    @DisplayName("존재하지_않는_질문번호를_보내도_첫번째_질문으로_교정된다")
    void danglingQuestionIsCorrectedNotRejected() {
        // 물리 FK 가 없으므로(V17) 사라진 행을 가리키는 값이 실제로 온다.
        when(questionRepository.findById(777L)).thenReturn(Optional.empty());

        assertThatCode(() -> createManualMarking(777L)).doesNotThrowAnyException();

        assertThat(savedMarking().getVrfcEvntQstnSn()).isEqualTo(FIRST_QSTN_SN);
    }

    @Test
    @DisplayName("검증이벤트유형이_미수신이면_질문없이_저장되고_마킹도_배치트리거도_막히지_않는다")
    void missingEventTypeLeavesQuestionEmptyWithoutBlocking() {
        stubEventType(null);

        MarkingResponse response = createManualMarking(SECOND_QSTN_SN);

        LsMarking saved = savedMarking();
        // 유형을 모르면 소속을 판정할 축이 없다 — 선택값을 그대로 믿지도, 지어내지도 않는다.
        assertThat(saved.getVrfcEvntQstnSn()).isNull();
        // ★ 질문 부재는 거부 사유가 아니다. 마킹은 저장되고 잔여 배치를 여는 이벤트도 그대로 나간다.
        assertThat(response.markingSn()).isEqualTo(saved.getMarkingSn());
        assertThat(saved.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
        verify(eventPublisher).publishEvent(any(MarkingCompletedEvent.class));
        // 유형이 없으면 질문 조회 자체를 하지 않는다(빈 조회로 헛도는 것을 막는다).
        verify(questionRepository, never()).findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(any());
    }

    @Test
    @DisplayName("인입_행_자체가_없어도_질문없이_저장된다")
    void missingIngestRowLeavesQuestionEmpty() {
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);

        createManualMarking(null);

        assertThat(savedMarking().getVrfcEvntQstnSn()).isNull();
    }

    @Test
    @DisplayName("첫번째_질문은_조회순서가_아니라_정렬순서로_결정된다")
    void firstQuestionIsDecidedBySortOrder() {
        createManualMarking(null);

        // 「첫 번째」의 결정성은 정렬순서 오름차순 조회가 받친다. 정렬 없는 목록 조회로 고르면
        // 기본 질문이 실행마다 달라진다 — 그 통로를 쓰지 않음을 구조로 고정한다.
        verify(questionRepository).findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(EVENT_TYPE);
        verify(questionRepository, never()).findByVrfcEvntTypeCdOrderBySortSeqAsc(any());
    }

    @Test
    @DisplayName("검증이벤트유형은_표기가_달라도_정규화되어_조달된다")
    void eventTypeIsNormalizedBeforeLookup() {
        // 인입은 우리 코드를 거치지 않고 직접 INSERT 되므로 표기가 흔들릴 수 있다.
        // 정규화 규칙은 인입 엔티티의 함수 하나를 재사용한다(복제 금지).
        stubEventType("  FIRE  ");

        createManualMarking(null);

        assertThat(savedMarking().getVrfcEvntQstnSn()).isEqualTo(FIRST_QSTN_SN);
    }

    @Test
    @DisplayName("질문_선택값은_기존_마킹_계약을_바꾸지_않는다")
    void existingMarkingContractUnchanged() {
        MarkingResponse response = createManualMarking(SECOND_QSTN_SN);

        LsMarking saved = savedMarking();
        assertThat(saved.getMarkModeCd()).isEqualTo(LsMarking.MODE_MANUAL);
        // 수동 마킹은 프레임 간격을 쓰지 않는다(프레임 정책 도출 축 불변).
        assertThat(saved.getFrmeIntvNocs()).isNull();
        assertThat(saved.getFps()).isEqualTo(VideoFpsResolver.DEFAULT_FPS);
        assertThat(response.marks()).hasSize(2);
        assertThat(response.marks().get(0).frameIndex()).isEqualTo(10);
    }

    @Test
    @DisplayName("요청_JSON_의_질문선택값이_바인딩된다_그리고_없어도_바인딩된다")
    void requestJsonBindsOptionalQuestion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        MarkingRequest withQuestion = mapper.readValue(
                "{\"mode\":\"MANUAL\",\"marks\":[{\"frameIndex\":3,\"timestamp\":\"00:01\"}],"
                        + "\"vrfcEvntQstnSn\":12}", MarkingRequest.class);
        assertThat(withQuestion.vrfcEvntQstnSn()).isEqualTo(SECOND_QSTN_SN);
        assertThat(withQuestion.mode()).isEqualTo("MANUAL");

        // 기존 소비자 하위호환 — 질문 선택값이 없는 옛 요청도 그대로 바인딩된다(선택값).
        MarkingRequest legacy = mapper.readValue(
                "{\"mode\":\"AUTO\",\"intervalFrames\":300}", MarkingRequest.class);
        assertThat(legacy.vrfcEvntQstnSn()).isNull();
        assertThat(legacy.intervalFrames()).isEqualTo(300);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private MarkingResponse createManualMarking(Long vrfcEvntQstnSn) {
        MarkingRequest req = new MarkingRequest("MANUAL", null,
                List.of(new MarkItem(10, "00:05"), new MarkItem(50, "00:10")), vrfcEvntQstnSn);
        return markingService.create(RAW_SN, req, reviewer(), null);
    }

    private LsMarking savedMarking() {
        ArgumentCaptor<LsMarking> captor = ArgumentCaptor.forClass(LsMarking.class);
        verify(markingRepository).save(captor.capture());
        return captor.getValue();
    }

    private void stubEventType(String vrfcEvntTypeCd) {
        IngestSourceRow row = mock(IngestSourceRow.class);
        when(row.getVrfcEvntTypeCd()).thenReturn(vrfcEvntTypeCd);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(row);
    }

    /** PK 는 IDENTITY 라 팩토리가 채우지 않는다 — 조달 판정의 축이므로 픽스처에서 심는다. */
    private LsVrfcEvntQstn question(long sn, String typeCd, int sortSeq, String text) {
        LsVrfcEvntQstn q = LsVrfcEvntQstn.create(typeCd, sortSeq, text, "tester");
        ReflectionTestUtils.setField(q, "vrfcEvntQstnSn", sn);
        return q;
    }

    /** 비식별 완료 + 마킹 대기 — 마킹 가드를 통과하는 영상. */
    private LsDataRaw markableRaw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + RAW_SN, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip_" + RAW_SN + ".mp4",
                LocalDateTime.now(), 60);
        raw.markDeidentified("Y");
        raw.markMarkingReady();
        return raw;
    }

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }
}
