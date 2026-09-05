package kr.co.cudo.authoring.augment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentExternalLinkPolicy;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 증강 요청 <b>API 계약</b> 테스트 — Phase 8-C (E-ISSUE-08 / E-ISSUE-09).
 *
 * <h3>고정하는 계약</h3>
 * <ul>
 *   <li><b>단건 계약(E-ISSUE-08)</b>: 한 요청 = 영상 1건 × 종류 1개. DTO({@code @Size(max=1)})가 정본이며
 *       서비스도 같은 규칙으로 fail-closed 방어한다(초과분을 조용히 잘라 처리하지 않는다).</li>
 *   <li><b>생성 0건은 성공이 아니다(E-ISSUE-09)</b>: 프레임 부재 등으로 PENDING 행이 하나도 생기지
 *       않았는데 200 을 주면 REVIEWER 는 요청이 접수된 줄 안다(silent no-op). 응답은 요청 개수가 아니라
 *       <b>실제 생성 수</b>를 담고, 0건이면 4xx 로 구분한다.</li>
 *   <li><b>실패 격리는 유지</b>: 단건이어도 실패는 삼키지 않고 사유를 남긴다(로그 + 오류 응답).
 *       내부 예외 원문은 응답으로 새지 않는다(CWE-209).</li>
 * </ul>
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentRequestContractTest {

    @Mock private LsRawDataStatusRepository statusRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataAugRepository augRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private DeidentReportGate deidentReportGate;
    @Mock private AugmentCallbackUrlResolver callbackUrlResolver;
    @Mock private VideoRepository videoRepository;
    @Mock private AugmentExternalLinkPolicy externalLinkPolicy;

    private AugmentRequestService service;
    private TokenClaims reviewer;

    private static final Long RAW_SN = 4001L;
    private static final Long SRC_SN = 5001L;

    /** 생성 조건 자체가 관심사가 아닌 케이스에서 계약(5항목 필수)을 채우는 고정값. */
    private static final AugmentRequestRequest.Mtdt MTDT = new AugmentRequestRequest.Mtdt(
            AugmentPrompts.Time.NIGHT, AugmentPrompts.Season.WINTER, AugmentPrompts.Weather.RAIN,
            AugmentPrompts.Terrain.ROAD, AugmentPrompts.Severity.HIGH);

    @BeforeEach
    void setUp() {
        service = new AugmentRequestService(statusRepository, srcRepository, augRepository,
                videoRepository, eventPublisher, deidentReportGate, callbackUrlResolver,
                new ObjectMapper(), externalLinkPolicy);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        when(callbackUrlResolver.resolve()).thenReturn("http://authoring/v1/genai/callback");
        when(deidentReportGate.isUnderDeidentReport(anyLong())).thenReturn(false);
        // 파생 영상 가드(원본만 증강 요청 가능) — 정상 시드는 ORGNL_RAW_SN 이 null 인 원본이다.
        when(videoRepository.findById(anyLong())).thenReturn(java.util.Optional.of(originalVideo()));
        // 외부 연동 기본 스텁 — 연동됨(http). 미연동 케이스만 개별 테스트에서 뒤집는다.
        when(externalLinkPolicy.isNotLinked()).thenReturn(false);
        approved(RAW_SN);
    }

    /** ORGNL_RAW_SN 이 null 인 원본 영상 스텁. */
    private static LsDataRaw originalVideo() {
        return LsDataRaw.createFromIngest("CLIP-" + RAW_SN, "CCTV-001", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/nas-storage/raw/x.mp4",
                java.time.LocalDateTime.now(), 30);
    }

    private void approved(Long rawSn) {
        LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(statusRepository.findByRawDataIdIn(anyCollection())).thenReturn(List.of(status));
    }

    private void withFrame() {
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{RAW_SN, SRC_SN});
        when(srcRepository.findFirstSrcSnGroupedByRawSn(anyCollection())).thenReturn(rows);
    }

    private void withoutFrame() {
        when(srcRepository.findFirstSrcSnGroupedByRawSn(anyCollection())).thenReturn(List.of());
    }

    private static AugmentRequestRequest single() {
        return request(MTDT, null);
    }

    /** v1.3 요청 본문 — 이벤트 유형은 요청자가 고른 값(관제 코드 변환 아님). */
    private static AugmentRequestRequest request(AugmentRequestRequest.Mtdt mtdt, String promptText) {
        return new AugmentRequestRequest(List.of(RAW_SN), List.of(AugmentTypeCode.AUGMENT), mtdt, promptText);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsOf(Throwable t) {
        return (Map<String, Object>) ((CustomException) t).getDetails();
    }

    // ─── R8 · @design API-060: 외부 미연동이면 접수하지 않는다 ─────

    /** 미연동 모드로 뒤집는다 — 이 스텁만이 게이트를 발동시킨다. */
    private void notLinked() {
        when(externalLinkPolicy.isNotLinked()).thenReturn(true);
    }

    @Test
    @DisplayName("외부_연동이_미연동이면_요청_접수를_503으로_거부한다")
    void 미연동이면_503() {
        // given — 위탁 주소 미주입 (위탁도 콜백도 없다)
        notLinked();
        withFrame();

        // when / then — 접수 자체가 거부된다
        assertThatThrownBy(() -> service.request(single(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        // 그리고 고착될 PENDING 행을 애초에 만들지 않는다.
        verify(augRepository, never()).save(any(LsDataAug.class));
        verify(eventPublisher, never()).publishEvent(any(AugmentRequestedItemEvent.class));
    }

    @Test
    @DisplayName("미연동_거부_응답은_배선_상세를_노출하지_않는다")
    void 미연동_거부는_배선상세를_숨긴다() {
        notLinked();
        withFrame();

        assertThatThrownBy(() -> service.request(single(), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    String message = e.getMessage();
                    // 모드 값·프로퍼티 키·구현 클래스명은 운영 정보다(CWE-209).
                    assertThat(message).doesNotContain("noop");
                    assertThat(message).doesNotContain(AugmentExternalLinkPolicy.KEY_BASE_URL);
                    assertThat(message).doesNotContain("Noop");
                    // 어떤 영상이 막혔는지는 다른 게이트와 동일하게 알린다.
                    List<?> skipped = (List<?>) detailsOf(e).get("skippedVideoIds");
                    assertThat(skipped).hasSize(1);
                    assertThat(skipped.get(0)).isEqualTo(RAW_SN);
                });
    }

    @Test
    @DisplayName("외부_위탁주소가_주입되면_기존_접수_경로가_그대로_동작한다")
    void 연동이면_종전대로_접수된다() {
        // given — 위탁 주소 주입 (기본 스텁: isNotLinked()=false)
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        AugmentRequestResponse resp = service.request(single(), reviewer);

        // then — 신규 게이트가 기존 판정을 바꾸지 않는다.
        assertThat(resp.createdCount()).isEqualTo(1);
        verify(eventPublisher).publishEvent(any(AugmentRequestedItemEvent.class));
    }

    @Test
    @DisplayName("미연동이어도_비권한_호출자는_인가_실패가_먼저_난다")
    void 미연동이어도_인가가_먼저다() {
        notLinked();
        withFrame();

        // 스텁 과정에서 남을 수 있는 호출 기록을 지우고 <실제 호출>만 관측한다.
        org.mockito.Mockito.clearInvocations(externalLinkPolicy);

        // WORKER — 연동 상태를 알려주면 그 자체가 정보 노출이다(CWE-209).
        TokenClaims worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> service.request(single(), worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // 미인증도 마찬가지다.
        assertThatThrownBy(() -> service.request(single(), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        // 인가 전에는 연동 판정 자체를 하지 않는다.
        verify(externalLinkPolicy, never()).isNotLinked();
    }

    @Test
    @DisplayName("미연동이어도_파생영상은_400이_먼저_난다")
    void 미연동이어도_파생차단이_먼저다() {
        notLinked();
        withFrame();
        // 파생본(ORGNL_RAW_SN != null) — 연동 여부와 무관한 <영구> 조건이라 그 사유가 먼저여야 한다.
        LsDataRaw derivative = originalVideo();
        org.springframework.test.util.ReflectionTestUtils.setField(derivative, "orgnlRawSn", 999L);
        when(videoRepository.findById(anyLong())).thenReturn(java.util.Optional.of(derivative));

        assertThatThrownBy(() -> service.request(single(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("미연동이면_DB_조회로_넘어가지_않는다")
    void 미연동이면_DB를_건드리지_않는다() {
        notLinked();
        withFrame();

        org.mockito.Mockito.clearInvocations(statusRepository, deidentReportGate, srcRepository);

        assertThatThrownBy(() -> service.request(single(), reviewer))
                .isInstanceOf(CustomException.class);

        // 어차피 거부할 요청에 검수상태·신고구간·프레임 조회를 태우지 않는다(CWE-770).
        verify(statusRepository, never()).findByRawDataIdIn(anyCollection());
        verify(deidentReportGate, never()).isUnderDeidentReport(anyLong());
        verify(srcRepository, never()).findFirstSrcSnGroupedByRawSn(anyCollection());
    }

    // ─── E-ISSUE-08: 단건 계약 고정 ───────────────────────────

    @Test
    @DisplayName("단건_계약을_초과하면_400")
    void multiSelectionIsRejected() {
        withFrame();

        assertThatThrownBy(() -> service.request(
                new AugmentRequestRequest(List.of(RAW_SN, 4002L), List.of(AugmentTypeCode.AUGMENT), MTDT, null), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.request(
                new AugmentRequestRequest(List.of(RAW_SN),
                        List.of(AugmentTypeCode.AUGMENT, AugmentTypeCode.AUGMENT), MTDT, null), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 초과 요청은 <일부만 조용히 처리>되지 않는다.
        verify(augRepository, never()).save(any(LsDataAug.class));
        verify(eventPublisher, never()).publishEvent(any(AugmentRequestedItemEvent.class));
    }

    // ─── E-ISSUE-09: 생성 0건 / 실제 생성 수 ─────────────────

    @Test
    @DisplayName("생성_0건이면_응답이_성공으로_보이지_않는다")
    void zeroCreatedIsNotSuccess() {
        withoutFrame(); // 프레임 미추출 영상 — 증강 위탁 입력이 없다

        assertThatThrownBy(() -> service.request(single(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verify(augRepository, never()).save(any(LsDataAug.class));
        verify(eventPublisher, never()).publishEvent(any(AugmentRequestedItemEvent.class));
    }

    @Test
    @DisplayName("스킵된_영상을_호출자가_식별할_수_있다")
    void skippedVideoIsIdentifiable() {
        withoutFrame();

        assertThatThrownBy(() -> service.request(single(), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    Map<String, Object> details = detailsOf(e);
                    assertThat(details).isNotNull();
                    List<?> skipped = (List<?>) details.get("skippedVideoIds");
                    assertThat(skipped).hasSize(1);
                    assertThat(skipped.get(0)).isEqualTo(RAW_SN);
                });
    }

    @Test
    @DisplayName("응답은_요청수가_아니라_실제_생성수를_반환한다")
    void responseCarriesActualCreatedCount() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        AugmentRequestResponse resp = service.request(single(), reviewer);

        assertThat(resp.createdCount())
                .as("요청 개수 echo 가 아니라 실제 적재된 PENDING 행 수여야 한다")
                .isEqualTo(1);
        assertThat(resp.videoCount()).isEqualTo(1);
        assertThat(resp.typeCount()).isEqualTo(1);
        verify(eventPublisher).publishEvent(any(AugmentRequestedItemEvent.class));
    }

    // ─── 구조화 생성 조건 계약 (2026-07-31 신설 · 2026-08-27 v1.3 정합) ──────────────────

    /**
     * 다섯 항목이 이벤트(→ 외부 위탁)로 <b>가공 없이</b> {@code mtdt} 로 전달되는지.
     * 서버 고정 문구 시절로의 회귀 가드이자, v1.3 에서 키가 {@code prompt} → {@code mtdt} 로 옮겨간
     * 것의 고정이다.
     */
    @Test
    @DisplayName("생성조건_5항목을_고르면_외부전송_mtdt_객체에_그대로_담긴다")
    void mtdtIsCarriedToSubmitEvent() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        service.request(request(new AugmentRequestRequest.Mtdt(
                AugmentPrompts.Time.DAWN, AugmentPrompts.Season.SUMMER, AugmentPrompts.Weather.FOG,
                AugmentPrompts.Terrain.UNDERPASS, AugmentPrompts.Severity.LOW), null), reviewer);

        ArgumentCaptor<AugmentRequestedItemEvent> captor =
                ArgumentCaptor.forClass(AugmentRequestedItemEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().mtdt()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "time", "DAWN", "season", "SUMMER", "weather", "FOG",
                "terrain", "UNDERPASS", "severity", "LOW"));
    }

    /**
     * 자유 지시문은 <b>별개 최상위 문자열</b>이다(v1.3). 구 계약처럼 조건 객체 안으로 되돌리면
     * 벤더가 {@code 400 INVALID_PARAMETER} 로 거부한다.
     */
    @Test
    @DisplayName("자유지시문은_mtdt와_분리된_문자열로_전달된다")
    void promptTextIsCarriedSeparately() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        service.request(request(MTDT, "  도로 구조를 유지해줘.  "), reviewer);

        ArgumentCaptor<AugmentRequestedItemEvent> captor =
                ArgumentCaptor.forClass(AugmentRequestedItemEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().promptText())
                .as("앞뒤 공백은 정규화되고 값 자체는 가공되지 않는다")
                .isEqualTo("도로 구조를 유지해줘.");
        assertThat(captor.getValue().mtdt()).doesNotContainKey("prompt");
    }

    /** 자유 지시문은 <b>선택</b>이라 없으면 null 로 남아 외부 바디에서 키 자체가 생략된다. */
    @Test
    @DisplayName("자유지시문이_없으면_null로_남아_전송되지_않는다")
    void absentPromptTextStaysNull() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        service.request(request(MTDT, "   "), reviewer);

        ArgumentCaptor<AugmentRequestedItemEvent> captor =
                ArgumentCaptor.forClass(AugmentRequestedItemEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().promptText()).isNull();
    }

    /**
     * 저장본(PROMPT_CN)과 전송본이 <b>같은 값</b>이어야 한다. 둘을 따로 만들면 "이 파생본은 어떤
     * 조건으로 만들었나" 라는 역추적이 조용히 거짓이 된다.
     *
     * <p>v1.3 부터 보관도 <b>분리 형태</b>({@code {"mtdt":{...},"prompt":"..."}})다 — 나간 바디와
     * 모양이 같아야 대조가 성립한다.
     */
    @Test
    @DisplayName("생성조건이_DB에_분리형태로_보관되어_결과에서_역추적된다")
    void mtdtIsPersistedOnAugRow() {
        withFrame();
        ArgumentCaptor<LsDataAug> saved = ArgumentCaptor.forClass(LsDataAug.class);
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        service.request(request(new AugmentRequestRequest.Mtdt(
                AugmentPrompts.Time.DAWN, AugmentPrompts.Season.SUMMER, AugmentPrompts.Weather.FOG,
                AugmentPrompts.Terrain.UNDERPASS, AugmentPrompts.Severity.LOW), "지시문"), reviewer);

        verify(augRepository).save(saved.capture());
        assertThat(saved.getValue().getPromptCn())
                .isNotNull()
                .contains("\"mtdt\"")
                .contains("DAWN").contains("SUMMER").contains("FOG")
                .contains("UNDERPASS").contains("LOW")
                .contains("\"prompt\"").contains("지시문");
    }

    /** 지시문이 없으면 보관 JSON 에도 그 키를 넣지 않는다 — 나간 바디와 모양을 맞춘다. */
    @Test
    @DisplayName("자유지시문이_없으면_보관JSON에도_그_키가_없다")
    void storedJsonOmitsAbsentPromptText() {
        withFrame();
        ArgumentCaptor<LsDataAug> saved = ArgumentCaptor.forClass(LsDataAug.class);
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        service.request(single(), reviewer);

        verify(augRepository).save(saved.capture());
        assertThat(saved.getValue().getPromptCn())
                .contains("\"mtdt\"")
                .doesNotContain("\"prompt\"");
    }

    /**
     * DTO 의 {@code @NotNull} 은 <b>컨트롤러 진입에만</b> 적용된다. 서비스를 직접 부르는 경로가 그
     * 규칙을 우회하면 빈 조건이 외부로 나가 결과가 비결정적이 되므로 여기서도 막는다
     * (컨트롤러 400 은 {@code AugmentRequestControllerTest} 가 별도로 고정한다).
     *
     * <p><b>다섯 항목 전부 필수는 우리 규칙</b>이다 — 벤더 계약은 "최소 1개" 지만 완화하지 않는다.
     */
    @Test
    @DisplayName("생성조건_항목이_하나라도_비면_400")
    void missingMtdtFieldRejectedAtServiceLayer() {
        withFrame();

        assertThatThrownBy(() -> service.request(request(new AugmentRequestRequest.Mtdt(
                null, AugmentPrompts.Season.WINTER, AugmentPrompts.Weather.RAIN,
                AugmentPrompts.Terrain.ROAD, AugmentPrompts.Severity.HIGH), null), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.request(request(null, null), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(augRepository, never()).save(any(LsDataAug.class));
        verify(eventPublisher, never()).publishEvent(any(AugmentRequestedItemEvent.class));
    }

    /**
     * ★ 이벤트 유형·침수 세부 유형은 <b>요청 본문 계약에서 사라졌다</b>({@code @design ADR-059}).
     *
     * <p>구 계약은 두 값을 요청자에게 필수로 물었다. 그 값은 벤더 창구가 <b>배경에 무슨 장면을
     * 만들지</b> 정하는 축인데 우리 증강은 이미 이벤트가 담긴 프레임을 변환할 뿐이라 지정할 자리가
     * 없고, 우리 이벤트 체계가 벤더 허용값보다 넓어 대응되지 않는 영상은 요청자가 <b>사실과 다른
     * 값</b>을 고를 수밖에 없었다. 지금은 위탁 시점에 서버가 중립값
     * ({@code GenAiJobSubmitRequest.EVENT_TYPE_ETC})을 고정 송신하고 세부 유형은 보내지 않는다.
     *
     * <p>구조로 고정하는 이유: 필드가 없으면 <b>요청자 입력이 위탁으로 흘러들 경로 자체가 없다</b>.
     * 이 시험이 그 되살림을 막는다.
     */
    @Test
    @DisplayName("요청본문_계약에_이벤트유형과_세부유형_필드가_없다")
    void requestContractHasNoEventTypeFields() {
        List<String> components = java.util.Arrays.stream(
                        AugmentRequestRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("이벤트 유형·세부 유형을 요청 본문으로 되살리지 말 것(ADR-059)")
                .doesNotContain("evntType", "evntSubtype")
                .containsExactly("videoIds", "types", "mtdt", "prompt");
    }

    /**
     * 증강 종류 계약값은 <b>단일값</b>이다 — 구 3종(WINTER/NIGHT/RAIN)은 생성 조건의 부분집합이라
     * 종류 카드와 조건이 어긋날 수 있었고, 무엇으로 바꿀지는 이제 {@code mtdt} 가 단독으로 정한다.
     * 이미 만들어진 파생본에는 구 값이 남아 있으나 그것은 <b>조회·표시 축</b>이지 요청 입구가 아니다.
     */
    @Test
    @DisplayName("요청_증강종류_enum은_AUGMENT_단일값이다")
    void augmentTypeCodeIsSingleValue() {
        assertThat(AugmentTypeCode.values())
                .as("구 3종을 요청 입구 계약으로 되살리지 말 것(ADR-059)")
                .containsExactly(AugmentTypeCode.AUGMENT);
        assertThat(AugmentTypeCode.AUGMENT.name()).isEqualTo(LsDataAug.AUG_AUGMENT);
        assertThat(LsDataAug.isContractAugType(LsDataAug.AUG_AUGMENT))
                .as("현행 값이 FE 계약 화이트리스트에서 빠지면 작업목록·배정목록의 augType 이 "
                        + "조용히 null 로 떨어져 종류 배지가 사라진다(오류가 아니라 값 실종이다)")
                .isTrue();
        assertThat(LsDataAug.CONTRACT_AUG_TYPES)
                .as("구 3종은 확장이지 교체가 아니다 — 빼면 기존 파생본이 목록에서 사라진다")
                .contains(LsDataAug.AUG_WINTER, LsDataAug.AUG_NIGHT, LsDataAug.AUG_RAIN);
    }

    /**
     * 자유 지시문 상한이 없으면 저장 컬럼(VARCHAR(4000))을 넘겨 적재 500 이 되고, 무제한 입력이
     * 외부로 중계된다(CWE-770).
     */
    @Test
    @DisplayName("자유지시문_길이_상한_초과시_400")
    void oversizedPromptTextRejected() {
        withFrame();
        String tooLong = "X".repeat(AugmentPrompts.MAX_PROMPT_LENGTH + 1);

        assertThatThrownBy(() -> service.request(request(MTDT, tooLong), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(ce.getMessage())
                            .as("입력값 원문을 되돌려주지 않는다(CWE-359 반사 노출 차단)")
                            .doesNotContain(tooLong);
                });

        verify(augRepository, never()).save(any(LsDataAug.class));
    }

    /** 상한 경계(정확히 1000자)는 통과해야 한다 — off-by-one 으로 정상 입력을 막지 않는지 고정. */
    @Test
    @DisplayName("자유지시문_길이_상한_경계값은_허용된다")
    void promptTextAtExactLimitAccepted() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        AugmentRequestResponse resp = service.request(
                request(MTDT, "X".repeat(AugmentPrompts.MAX_PROMPT_LENGTH)), reviewer);

        assertThat(resp.createdCount()).isEqualTo(1);
    }

    /**
     * 증강 유형은 <b>생성 조건에서 파생하지 않는다</b>. 그 값이 AUG_TYPE_CD 로 흘러가면 파생
     * 산출물 경로({@code .../{augTypeCd}.mp4}) 순회(CWE-22)와 RESL_ 네임스페이스 침범(검수 우회)이
     * 열린다.
     *
     * <p>단일값 {@code AUGMENT} 로 합쳐진 뒤에도 유효한 가드다 — <b>상수로 고정</b>하는 것이지
     * 조건에서 유도하는 것이 아니며, 코드 공간도 여전히 겹친다
     * ({@code Season.WINTER} ↔ 구 {@code AUG_WINTER}).
     */
    @Test
    @DisplayName("증강종류는_생성조건이_아니라_types_enum에서만_결정된다")
    void augTypeIsNeverDerivedFromMtdt() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LsDataAug> saved = ArgumentCaptor.forClass(LsDataAug.class);

        // 조건·지시문에 무엇을 넣어도 유형은 types[] enum 값 그대로여야 한다.
        service.request(new AugmentRequestRequest(List.of(RAW_SN), List.of(AugmentTypeCode.AUGMENT),
                new AugmentRequestRequest.Mtdt(
                        AugmentPrompts.Time.NIGHT, AugmentPrompts.Season.WINTER,
                        AugmentPrompts.Weather.RAIN, AugmentPrompts.Terrain.ROAD,
                        AugmentPrompts.Severity.HIGH),
                "../../etc RESL_1080P"), reviewer);

        verify(augRepository).save(saved.capture());
        assertThat(saved.getValue().getAugTypeCd()).isEqualTo(LsDataAug.AUG_AUGMENT);
    }

    @Test
    @DisplayName("단건이어도_실패는_격리되어_사유가_남는다")
    void singleItemFailureIsIsolatedAndReported() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class)))
                .thenThrow(new IllegalStateException("DB 적재 실패 (내부 상세)"));

        assertThatThrownBy(() -> service.request(single(), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(ce.getMessage())
                            .as("내부 예외 원문을 외부로 노출하지 않는다(CWE-209)")
                            .doesNotContain("내부 상세");
                });

        // 실패 건은 외부 위탁으로 이어지지 않는다(고아 위탁 방지).
        verify(eventPublisher, never()).publishEvent(any(AugmentRequestedItemEvent.class));
    }
}
