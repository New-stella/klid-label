package kr.co.cudo.authoring.augment.service;

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
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private AugmentRequestService service;
    private TokenClaims reviewer;

    private static final Long RAW_SN = 4001L;
    private static final Long SRC_SN = 5001L;

    @BeforeEach
    void setUp() {
        service = new AugmentRequestService(statusRepository, srcRepository, augRepository,
                eventPublisher, deidentReportGate, callbackUrlResolver);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        when(callbackUrlResolver.resolve()).thenReturn("http://authoring/v1/genai/callback");
        when(deidentReportGate.isUnderDeidentReport(anyLong())).thenReturn(false);
        approved(RAW_SN);
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
        return new AugmentRequestRequest(List.of(RAW_SN), List.of(AugmentTypeCode.WINTER));
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
                new AugmentRequestRequest(List.of(RAW_SN, 4002L), List.of(AugmentTypeCode.WINTER)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.request(
                new AugmentRequestRequest(List.of(RAW_SN),
                        List.of(AugmentTypeCode.WINTER, AugmentTypeCode.NIGHT)), reviewer))
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
