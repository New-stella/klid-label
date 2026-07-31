package kr.co.cudo.authoring.augment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
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

    private AugmentRequestService service;
    private TokenClaims reviewer;

    private static final Long RAW_SN = 4001L;
    private static final Long SRC_SN = 5001L;

    /** 프롬프트 자체가 관심사가 아닌 케이스에서 계약(5필드 필수)을 채우는 고정값. */
    private static final AugmentRequestRequest.PromptFields PROMPT =
            new AugmentRequestRequest.PromptFields("NIGHT", "WINTER", "RAIN", "ROAD", "HIGH");

    @BeforeEach
    void setUp() {
        service = new AugmentRequestService(statusRepository, srcRepository, augRepository,
                videoRepository, eventPublisher, deidentReportGate, callbackUrlResolver,
                new ObjectMapper());
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        when(callbackUrlResolver.resolve()).thenReturn("http://authoring/v1/genai/callback");
        when(deidentReportGate.isUnderDeidentReport(anyLong())).thenReturn(false);
        // 파생 영상 가드(원본만 증강 요청 가능) — 정상 시드는 ORGNL_RAW_SN 이 null 인 원본이다.
        when(videoRepository.findById(anyLong())).thenReturn(java.util.Optional.of(originalVideo()));
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
        return new AugmentRequestRequest(List.of(RAW_SN), List.of(AugmentTypeCode.WINTER), PROMPT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsOf(Throwable t) {
        return (Map<String, Object>) ((CustomException) t).getDetails();
    }

    // ─── E-ISSUE-08: 단건 계약 고정 ───────────────────────────

    @Test
    @DisplayName("단건_계약을_초과하면_400")
    void multiSelectionIsRejected() {
        withFrame();

        assertThatThrownBy(() -> service.request(
                new AugmentRequestRequest(List.of(RAW_SN, 4002L), List.of(AugmentTypeCode.WINTER), PROMPT), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.request(
                new AugmentRequestRequest(List.of(RAW_SN),
                        List.of(AugmentTypeCode.WINTER, AugmentTypeCode.NIGHT), PROMPT), reviewer))
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

    // ─── 구조화 프롬프트 계약 (2026-07-31) ──────────────────

    /** 5필드가 이벤트(→ 외부 위탁)로 <b>가공 없이</b> 전달되는지. 서버 고정 문구 시절로의 회귀 가드. */
    @Test
    @DisplayName("프롬프트_5필드를_입력하면_외부전송_prompt_객체에_그대로_담긴다")
    void promptIsCarriedToSubmitEvent() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        service.request(new AugmentRequestRequest(List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields("DAWN", "SUMMER", "FOG", "TUNNEL", "LOW")),
                reviewer);

        ArgumentCaptor<AugmentRequestedItemEvent> captor =
                ArgumentCaptor.forClass(AugmentRequestedItemEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().prompt()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "time", "DAWN", "season", "SUMMER", "weather", "FOG",
                "terrain", "TUNNEL", "severity", "LOW"));
    }

    /**
     * 저장본(PROMPT_CN)과 전송본이 <b>같은 값</b>이어야 한다. 둘을 따로 만들면 "이 파생본은 어떤
     * 조건으로 만들었나" 라는 역추적이 조용히 거짓이 된다.
     */
    @Test
    @DisplayName("프롬프트가_DB에_보관되어_결과에서_역추적된다")
    void promptIsPersistedOnAugRow() {
        withFrame();
        ArgumentCaptor<LsDataAug> saved = ArgumentCaptor.forClass(LsDataAug.class);
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        service.request(new AugmentRequestRequest(List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields("DAWN", "SUMMER", "FOG", "TUNNEL", "LOW")),
                reviewer);

        verify(augRepository).save(saved.capture());
        assertThat(saved.getValue().getPromptCn())
                .isNotNull()
                .contains("DAWN").contains("SUMMER").contains("FOG")
                .contains("TUNNEL").contains("LOW");
    }

    /**
     * DTO 의 {@code @NotBlank}/{@code @Size} 는 <b>컨트롤러 진입에만</b> 적용된다. 서비스를 직접 부르는
     * 경로가 그 상한을 우회하면 PROMPT_CN 적재 오류·무제한 외부 중계로 이어지므로 여기서도 막는다
     * (컨트롤러 400 은 {@code AugmentRequestControllerTest} 가 별도로 고정한다).
     */
    @Test
    @DisplayName("프롬프트_필드가_하나라도_비면_400")
    void blankPromptFieldRejectedAtServiceLayer() {
        withFrame();

        assertThatThrownBy(() -> service.request(new AugmentRequestRequest(
                List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields(null, "WINTER", "RAIN", "ROAD", "HIGH")),
                reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.request(new AugmentRequestRequest(
                List.of(RAW_SN), List.of(AugmentTypeCode.WINTER), null), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(augRepository, never()).save(any(LsDataAug.class));
        verify(eventPublisher, never()).publishEvent(any(AugmentRequestedItemEvent.class));
    }

    /** 공백·제어문자만 남는 값은 "입력하지 않은 것" 과 동치다 — 빈 조건이 외부로 나가면 결과가 비결정적이 된다. */
    @Test
    @DisplayName("공백만_입력한_필드는_400")
    void whitespaceOnlyPromptFieldRejected() {
        withFrame();

        assertThatThrownBy(() -> service.request(new AugmentRequestRequest(
                List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields("   ", "WINTER", "RAIN", "ROAD", "HIGH")),
                reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 제어문자만 있는 값도 정규화 후 남는 게 없으므로 동일하게 거부된다.
        assertThatThrownBy(() -> service.request(new AugmentRequestRequest(
                List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields(String.valueOf((char) 9), "WINTER", "RAIN",
                        "ROAD", "HIGH")),
                reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(eventPublisher, never()).publishEvent(any(AugmentRequestedItemEvent.class));
    }

    /** 상한이 없으면 저장 컬럼(VARCHAR(4000))을 넘겨 적재 500 이 되고, 무제한 입력이 외부로 중계된다(CWE-770). */
    @Test
    @DisplayName("프롬프트_필드_길이_상한_초과시_400")
    void oversizedPromptFieldRejected() {
        withFrame();
        String tooLong = "X".repeat(AugmentRequestRequest.PromptFields.MAX_FIELD_LENGTH + 1);

        assertThatThrownBy(() -> service.request(new AugmentRequestRequest(
                List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields("NIGHT", "WINTER", "RAIN", "ROAD", tooLong)),
                reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(ce.getMessage())
                            .as("입력값 원문을 되돌려주지 않는다 — 필드 이름만 알린다(CWE-359 반사 노출 차단)")
                            .doesNotContain(tooLong);
                });

        verify(augRepository, never()).save(any(LsDataAug.class));
    }

    /** 상한 경계(정확히 50자)는 통과해야 한다 — off-by-one 으로 정상 입력을 막지 않는지 고정. */
    @Test
    @DisplayName("프롬프트_필드_길이_상한_경계값은_허용된다")
    void promptFieldAtExactLimitAccepted() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        String atLimit = "X".repeat(AugmentRequestRequest.PromptFields.MAX_FIELD_LENGTH);

        AugmentRequestResponse resp = service.request(new AugmentRequestRequest(
                List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields("NIGHT", "WINTER", "RAIN", "ROAD", atLimit)),
                reviewer);

        assertThat(resp.createdCount()).isEqualTo(1);
    }

    /**
     * 증강 유형은 <b>prompt 에서 파생하지 않는다</b>. 자유 문자열이 AUG_TYPE_CD 로 흘러가면 파생 산출물
     * 경로({@code .../{augTypeCd}.mp4}) 순회(CWE-22)와 RESL_ 네임스페이스 침범(검수 우회)이 열린다.
     */
    @Test
    @DisplayName("증강종류는_prompt가_아니라_types_enum에서만_결정된다")
    void augTypeIsNeverDerivedFromPrompt() {
        withFrame();
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LsDataAug> saved = ArgumentCaptor.forClass(LsDataAug.class);

        // prompt 에 경로 순회·RESL_ 침범을 노린 값을 넣어도 유형은 types[] enum 값 그대로여야 한다.
        service.request(new AugmentRequestRequest(List.of(RAW_SN), List.of(AugmentTypeCode.WINTER),
                new AugmentRequestRequest.PromptFields("../../etc", "RESL_1080P", "RAIN",
                        "ROAD", "HIGH")),
                reviewer);

        verify(augRepository).save(saved.capture());
        assertThat(saved.getValue().getAugTypeCd()).isEqualTo(LsDataAug.AUG_WINTER);
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
