package kr.co.cudo.authoring.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /** 관제가 유형을 보내지 않은 영상에서 <b>작업자가 마킹 화면에서 고른</b> 유형. */
    private static final String WORKER_TYPE = "fall";

    /** 작업자 선택 유형의 첫 번째 질문 — 유형만 고르고 질문을 안 골랐을 때의 착지점. */
    private static final long WORKER_FIRST_QSTN_SN = 21L;

    /** 작업자 선택 유형의 두 번째 질문 — 작업자가 직접 고를 수 있는 값. */
    private static final long WORKER_QSTN_SN = 22L;

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
    @DisplayName("★유형이_미수신이어도_작업자가_고른_질문이_있으면_그_질문이_저장된다")
    void missingEventTypeStillPersistsWorkerSelectedQuestion() {
        // ★ 이 단정은 뒤집힌 것이다. 구 동작은 「유형이 미수신이면 선택값이 있어도 버리고 비워 둔다」였고,
        //   그 근거는 「유형을 모르면 그 선택값이 어느 유형에 속하는지 판정할 축이 없다」였다. 질문이 우리
        //   기록일 뿐이던 동안에는 옳았다. 지금은 그 문구가 <b>위탁 요청 본문에 그대로 실려 나가는 값</b>이라
        //   비우면 그 영상은 추가 질문 축 위탁 자체가 못 나간다. 판정 근거는 조달 판정기 주석이 소유한다.
        //
        // ★★ 이 시험이 <b>판정 사본의 회귀 가드</b>다. 채널이 「유형이 비면 판정기를 부르지 않는다」로
        //    되돌아가면 여기서 죽는다 — 실제로 그 사본 때문에 판정기의 반전이 마킹 경로에서만 발동하지
        //    않았고, 그때 「깨졌어야 할 시험이 안 깨졌다」가 유일한 발견 단서였다.
        stubEventType(null);

        MarkingResponse response = createManualMarking(SECOND_QSTN_SN);

        LsMarking saved = savedMarking();
        assertThat(saved.getVrfcEvntQstnSn()).isEqualTo(SECOND_QSTN_SN);
        // 질문 부재가 아니어도 마킹 계약은 그대로다 — 저장되고 잔여 배치를 여는 이벤트도 나간다.
        assertThat(response.markingSn()).isEqualTo(saved.getMarkingSn());
        assertThat(saved.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
        verify(eventPublisher).publishEvent(any(MarkingCompletedEvent.class));
        // 유형이 없으면 「그 유형의 첫 번째」로 되돌릴 대상 자체가 없다 — 그 조회는 하지 않는다.
        verify(questionRepository, never()).findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(any());
    }

    @Test
    @DisplayName("유형도_선택도_없으면_질문없이_저장되고_마킹도_배치트리거도_막히지_않는다")
    void missingEventTypeAndNoSelectionLeavesQuestionEmptyWithoutBlocking() {
        // 위 시험과 <b>갈래를 나눠 둔다</b> — 한 시험에 섞으면 어느 쪽이 깨졌는지 알 수 없다.
        // 여기는 「지어내지 않는다」가 그대로 유효한 자리다.
        stubEventType(null);

        MarkingResponse response = createManualMarking(null);

        LsMarking saved = savedMarking();
        assertThat(saved.getVrfcEvntQstnSn()).isNull();
        assertThat(response.markingSn()).isEqualTo(saved.getMarkingSn());
        assertThat(saved.getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
        verify(eventPublisher).publishEvent(any(MarkingCompletedEvent.class));
        verify(questionRepository, never()).findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(any());
    }

    // ==================================================================
    // 작업자가 고른 검증 이벤트 유형 (API-047 vrfcEvntTypeCd)
    // ==================================================================

    @Test
    @DisplayName("관제유형_없음_작업자가_유형과_질문을_고르면_그_질문과_유형이_저장된다")
    void workerSelectedTypeAndQuestionArePersisted() {
        stubEventType(null);
        stubWorkerType(WORKER_TYPE);

        createMarking(WORKER_TYPE, WORKER_QSTN_SN);

        LsMarking saved = savedMarking();
        assertThat(saved.getVrfcEvntQstnSn()).isEqualTo(WORKER_QSTN_SN);
        // 저장된 선택 유형은 그 마킹 행에서 다시 읽힌다 — 「저장은 되는데 아무도 못 읽는」 상태 방지.
        assertThat(saved.getVrfcEvntTypeCd()).isEqualTo(WORKER_TYPE);
    }

    @Test
    @DisplayName("관제유형_없음_유형만_고르고_질문_미선택이면_그_유형의_첫번째_질문이_저장된다")
    void workerSelectedTypeFallsBackToItsFirstQuestion() {
        stubEventType(null);
        stubWorkerType(WORKER_TYPE);

        createMarking(WORKER_TYPE, null);

        LsMarking saved = savedMarking();
        assertThat(saved.getVrfcEvntQstnSn()).isEqualTo(WORKER_FIRST_QSTN_SN);
        assertThat(saved.getVrfcEvntTypeCd()).isEqualTo(WORKER_TYPE);
        // 「첫 번째」의 조달 축이 작업자 선택 유형으로 옮겨 갔음을 구조로 고정한다.
        verify(questionRepository).findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(WORKER_TYPE);
    }

    @Test
    @DisplayName("★관제유형이_있으면_요청에_실린_유형은_무시되고_저장되지도_않는다")
    void controlIngestTypeWinsAndRequestTypeIsNotPersisted() {
        // 관제 인입이 진실원이다. 화면은 그 경우 유형 선택을 아예 노출하지 않으므로 실제로는 이 필드가
        // 오지 않는다 — 이 시험은 그 전제가 깨져도 인입이 이기는지를 본다(400 을 내지도 않는다).
        stubWorkerType(WORKER_TYPE);

        assertThatCode(() -> createMarking(WORKER_TYPE, SECOND_QSTN_SN)).doesNotThrowAnyException();

        LsMarking saved = savedMarking();
        // 관제 유형('fire')의 질문이 그대로 조달된다 — 종전과 완전히 동일하다.
        assertThat(saved.getVrfcEvntQstnSn()).isEqualTo(SECOND_QSTN_SN);
        // 관제 값을 마킹 행에 베끼지 않는다(두 곳에 같은 값이 생기면 조용히 갈라진다).
        assertThat(saved.getVrfcEvntTypeCd()).isNull();
        verify(questionRepository, never()).findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(WORKER_TYPE);
    }

    @Test
    @DisplayName("컬럼폭을_넘는_21자_유형은_400이다_DB오류로_새지_않는다")
    void oversizedEventTypeIsRejected() {
        stubEventType(null);

        assertThatThrownBy(() -> createMarking("a".repeat(21), null))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));

        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("공백_개행_제어문자가_섞인_유형은_400이다_로그인젝션_차단")
    void controlCharactersInEventTypeAreRejected() {
        // 이 값은 외부 벤더 요청 본문과 로그에 그대로 실린다(CWE-117).
        stubEventType(null);

        for (String bad : List.of("fi re", "fire\ninjected", "fire\ttab", "fire;drop")) {
            assertThatThrownBy(() -> createMarking(bad, null))
                    .as("형식 위반 유형: %s", bad)
                    .isInstanceOf(CustomException.class);
        }
        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("유형_표기는_정규화되어_통과한다_거부가_아니다")
    void eventTypeIsNormalizedNotRejected() {
        // ⚠ 판정의 단일 진실원은 <b>정규화 결과</b>에 대한 형식 검사다(인입 원장과 같은 값 공간).
        //   표기 변형을 400 으로 거부하면 관제 인입이 실어 보낸 표기가 우리 통로에서만 막혀 비대칭이
        //   생긴다. 저장되는 값은 정규화된 소문자라 컬럼·벤더 어디에도 원문이 새지 않는다.
        stubEventType(null);
        stubWorkerType(WORKER_TYPE);

        assertThatCode(() -> createMarking("  FALL  ", null)).doesNotThrowAnyException();

        assertThat(savedMarking().getVrfcEvntTypeCd()).isEqualTo(WORKER_TYPE);
    }

    @Test
    @DisplayName("유형을_보내지_않는_기존_요청은_그대로_동작한다_하위호환")
    void legacyRequestWithoutTypeIsUnaffected() {
        createManualMarking(SECOND_QSTN_SN);

        LsMarking saved = savedMarking();
        assertThat(saved.getVrfcEvntQstnSn()).isEqualTo(SECOND_QSTN_SN);
        assertThat(saved.getVrfcEvntTypeCd()).isNull();
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
        // 유형도 같은 성질이다 — 옛 요청에는 없고, 없어도 400 이 아니다(스키마 축에서는 선택 필드).
        assertThat(legacy.vrfcEvntTypeCdOrNull()).isNull();
        assertThat(legacy.isVrfcEvntTypeAllowed()).isTrue();

        MarkingRequest withType = mapper.readValue(
                "{\"mode\":\"MANUAL\",\"marks\":[{\"frameIndex\":3,\"timestamp\":\"00:01\"}],"
                        + "\"vrfcEvntTypeCd\":\"fall\",\"vrfcEvntQstnSn\":22}", MarkingRequest.class);
        assertThat(withType.vrfcEvntTypeCdOrNull()).isEqualTo(WORKER_TYPE);
        assertThat(withType.vrfcEvntQstnSn()).isEqualTo(WORKER_QSTN_SN);
        assertThat(withType.isVrfcEvntTypeAllowed()).isTrue();
        // 형식 위반은 @Valid 단계에서 잡힌다 — 서비스 2단 방어와 <b>같은 함수</b>를 부른다.
        assertThat(new MarkingRequest("MANUAL", null, null, null, "a".repeat(21))
                .isVrfcEvntTypeAllowed()).isFalse();
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

    /** 요청에 검증 이벤트 유형을 실어 보내는 수동 마킹. */
    private MarkingResponse createMarking(String vrfcEvntTypeCd, Long vrfcEvntQstnSn) {
        MarkingRequest req = new MarkingRequest("MANUAL", null,
                List.of(new MarkItem(10, "00:05"), new MarkItem(50, "00:10")),
                vrfcEvntQstnSn, vrfcEvntTypeCd);
        return markingService.create(RAW_SN, req, reviewer(), null);
    }

    /** 작업자 선택 유형('fall')에 딸린 질문 2건 — 정렬순서 1 이 「첫 번째」다. */
    private void stubWorkerType(String typeCd) {
        when(questionRepository.findFirstByVrfcEvntTypeCdOrderBySortSeqAsc(typeCd))
                .thenReturn(Optional.of(question(WORKER_FIRST_QSTN_SN, typeCd, 1, "선택 유형 첫 질문")));
        when(questionRepository.findById(WORKER_FIRST_QSTN_SN))
                .thenReturn(Optional.of(question(WORKER_FIRST_QSTN_SN, typeCd, 1, "선택 유형 첫 질문")));
        when(questionRepository.findById(WORKER_QSTN_SN))
                .thenReturn(Optional.of(question(WORKER_QSTN_SN, typeCd, 2, "선택 유형 둘째 질문")));
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
