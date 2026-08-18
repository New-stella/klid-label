package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiCallCancellationRegistry;
import kr.co.cudo.authoring.common.client.AiCallCancelledException;
import kr.co.cudo.authoring.common.client.AiCallScope;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.label.dto.Sam2TrackOutcome;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.Sam2TrackService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SAM2 추적의 <b>요청 단위 시간 예산</b>과 <b>부분 결과 계약</b> (순수 Mockito).
 *
 * <h3>무엇을 고정하는가</h3>
 * <ul>
 *   <li>예산이 다해도 <b>그때까지의 결과가 살아 있다</b> — 조용히 버리지 않는다.</li>
 *   <li>응답이 <b>어디까지 했는지</b>를 밝히고, 이어 보낼 값을 그대로 준다.</li>
 *   <li><b>예산 초과와 사용자 취소는 다른 것</b>이다 — 섞이면 취소하지 않았는데 취소로 보인다.</li>
 * </ul>
 *
 * <h3>왜 시간을 «인코딩» 으로 흘려보내는가</h3>
 * <p>루프는 데드라인을 <b>반복 진입 직전</b>에 본다. 프레임 이미지 인코딩은 그 검사 뒤에 오는 실제
 * 부대 작업이라, 여기서 시간을 쓰면 «다음 반복 진입 시 예산이 다한» 상황이 <b>제한시간 경합 없이</b>
 * 결정적으로 재현된다(스텁이 잠든 시간은 줄어들 수 없으므로 부등호가 뒤집히지 않는다).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Sam2TrackBudgetTest {

    @Mock private AiServerClient aiServerClient;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private SystemConfigService systemConfigService;
    @Mock private FrameImageEncoder frameImageEncoder;

    private Sam2TrackService service;

    private static final Long RAW_SN = 9001L;
    private static final Long SRC0 = 5000L;
    private static final Long SRC1 = 5001L;
    private static final Long SRC2 = 5002L;
    private static final Long SRC3 = 5003L;

    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        service = new Sam2TrackService(aiServerClient, srcRepository, accessGuard,
                systemConfigService, frameImageEncoder);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));

        when(srcRepository.findById(any())).thenAnswer(inv -> Optional.of(frame((Long) inv.getArgument(0))));
        when(frameImageEncoder.encodeFrame(any())).thenReturn("b64");
        when(systemConfigService.getDouble(any())).thenReturn(1.0);
    }

    private static LsDataSrc frame(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, srcSn, srcSn + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    private static List<List<Double>> square(double x1, double y1, double x2, double y2) {
        return List.of(List.of(x1, y1), List.of(x2, y1), List.of(x2, y2), List.of(x1, y2));
    }

    private Sam2TrackRequest request(List<Long> next) {
        return new Sam2TrackRequest(SRC0, "track-A", square(10, 10, 30, 30), "person",
                next, AutolabelShape.POLYGON);
    }

    /** ai-server 가 매번 같은 폴리곤을 즉시 돌려준다. */
    private void stubTrackOk() {
        when(aiServerClient.track(any())).thenAnswer(inv ->
                Mono.just(new Sam2TrackResponse("track-A", square(11, 11, 31, 31), 0.9)));
    }

    /** 프레임 인코딩이 매번 {@code millis} 만큼 걸리게 한다 — 예산을 결정적으로 소진시키는 손잡이. */
    private void encodeTakes(long millis) {
        when(frameImageEncoder.encodeFrame(any())).thenAnswer(inv -> {
            Thread.sleep(millis);
            return "b64";
        });
    }

    // ── 부분 결과 ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(20)
    @DisplayName("예산이_다해도_그때까지_추적한_프레임_결과는_살아_있다")
    void keepsResultsProcessedBeforeBudgetRanOut() {
        // given: 예산 500ms · 프레임마다 부대 작업 600ms → 첫 프레임만 예산 안에 들어간다.
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubTrackOk();

        // when
        Sam2TrackResponseDto res = service.track(request(List.of(SRC1, SRC2, SRC3)), reviewer).response();

        // then: 처리한 1건은 그대로 남는다(전량 폐기 아님).
        assertThat(res.tracked()).hasSize(1);
        assertThat(res.tracked().get(0).srcSn()).isEqualTo(SRC1);
        // 남은 프레임은 추론 서버로 나가지 않았다.
        verify(aiServerClient, times(1)).track(any());
    }

    @Test
    @Timeout(20)
    @DisplayName("예산이_다하면_어디까지_했는지와_이어_보낼_값을_응답에_담는다")
    void reportsResumePointWhenBudgetRanOut() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubTrackOk();

        Sam2TrackResponseDto res = service.track(request(List.of(SRC1, SRC2, SRC3)), reviewer).response();

        assertThat(res.truncated()).isTrue();
        assertThat(res.resume()).isNotNull();
        // 다음 요청의 srcSn = 마지막으로 처리한 프레임(그 프레임 이미지가 prev 가 된다).
        assertThat(res.resume().srcSn()).isEqualTo(SRC1);
        // 다음 요청의 prevPolygon = 그 프레임까지 전파된 폴리곤(결과 목록에서 역산할 수 없는 값).
        assertThat(res.resume().prevPolygon()).isEqualTo(square(11, 11, 31, 31));
        // 다음 요청의 nextSrcSns = 아직 처리하지 않은 프레임(요청 순서 유지).
        assertThat(res.resume().nextSrcSns()).containsExactly(SRC2, SRC3);
    }

    /**
     * ★ 구 동작 폐기 — 예산 소진을 <b>안내 문구</b>로도 알리던 것을 걷어냈다.
     *
     * <p>문구 통로({@code ApiResponse.message})는 mock 안내와 공유인데, 그 문구는 «요청 하나» 기준이라
     * 실행 전체의 남은 수와 어긋나 화면이 쓰지 않는다. 그래서 화면은 «절단을 보고한 응답의 문구는
     * 버린다» 는 필터를 갖게 됐고, 절단이면서 mock 이기도 한 응답에서 <b>mock 안내가 함께 버려졌다</b>.
     * 절단은 아래처럼 구조화된 값으로만 말한다(문구 통로는 {@link TrackNoticeChannelTest} 소관).
     */
    @Test
    @Timeout(20)
    @DisplayName("예산_소진은_안내_문구가_아니라_구조화된_값으로만_알린다")
    void truncationIsReportedByStructuredValuesNotByNotice() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubTrackOk();

        Sam2TrackOutcome outcome = service.track(request(List.of(SRC1, SRC2, SRC3)), reviewer);

        assertThat(outcome.response().truncated()).isTrue();
        assertThat(outcome.response().resume()).isNotNull();
        assertThat(outcome.message()).isNull();
    }

    @Test
    @Timeout(20)
    @DisplayName("예산이_남으면_잘리지_않고_이어_보낼_값도_없다")
    void notTruncatedWhenBudgetSuffices() {
        // 운영 기본 예산 그대로(240s) — 인코딩·추론이 즉시 끝나므로 전량 처리된다.
        stubTrackOk();

        Sam2TrackResponseDto res = service.track(request(List.of(SRC1, SRC2, SRC3)), reviewer).response();

        assertThat(res.tracked()).hasSize(3);
        assertThat(res.truncated()).isFalse();
        assertThat(res.resume()).isNull();
        verify(aiServerClient, times(3)).track(any());
    }

    @Test
    @Timeout(20)
    @DisplayName("한_프레임도_들어가지_않는_예산이면_아무_것도_추론하지_않고_잘림을_알린다")
    void truncatesBeforeFirstFrameWhenBudgetIsZero() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ZERO);
        stubTrackOk();

        Sam2TrackResponseDto res = service.track(request(List.of(SRC1, SRC2)), reviewer).response();

        assertThat(res.tracked()).isEmpty();
        assertThat(res.truncated()).isTrue();
        // 아무 것도 처리하지 못했으므로 이어 보낼 값은 요청 그대로다(진행 0 — 화면이 확인해야 한다).
        assertThat(res.resume().srcSn()).isEqualTo(SRC0);
        assertThat(res.resume().nextSrcSns()).containsExactly(SRC1, SRC2);
        verify(aiServerClient, never()).track(any());
    }

    @Test
    @Timeout(30)
    @DisplayName("예산이_다한_순간의_추론_실패는_502가_아니라_부분_결과로_끝난다")
    void slowCallAtDeadlineBecomesPartialResultNotFailure() {
        // given: 첫 프레임은 즉시 성공, 두 번째 프레임은 예산보다 오래 걸린다.
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(700));
        when(aiServerClient.track(any()))
                .thenReturn(Mono.just(new Sam2TrackResponse("track-A", square(11, 11, 31, 31), 0.9)))
                .thenReturn(Mono.fromCallable(() -> {
                    Thread.sleep(5_000);
                    return new Sam2TrackResponse("track-A", square(12, 12, 32, 32), 0.9);
                }).subscribeOn(Schedulers.boundedElastic()));

        // when
        Sam2TrackResponseDto res = service.track(request(List.of(SRC1, SRC2)), reviewer).response();

        // then: 502 로 올리면 성공한 1프레임까지 통째로 버려진다.
        assertThat(res.tracked()).hasSize(1);
        assertThat(res.truncated()).isTrue();
        assertThat(res.resume().nextSrcSns()).containsExactly(SRC2);
    }

    @Test
    @Timeout(30)
    @DisplayName("첫_프레임부터_예산_안에_못_끝내면_부분_결과가_아니라_502다")
    void noProgressAtDeadlineIsFailureNotPartialResult() {
        // 진행이 0 이면 «어디까지 했다» 가 없다. 그것을 부분 결과로 200 을 주면 화면은 같은 지점을
        // 무한히 다시 요청하게 된다 — 진행이 없을 때만 종전대로 실패를 올린다.
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(300));
        when(aiServerClient.track(any())).thenReturn(Mono.fromCallable(() -> {
            Thread.sleep(5_000);
            return new Sam2TrackResponse("track-A", square(11, 11, 31, 31), 0.9);
        }).subscribeOn(Schedulers.boundedElastic()));

        assertThatThrownBy(() -> service.track(request(List.of(SRC1, SRC2)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @Timeout(20)
    @DisplayName("예산이_남아있는데_추론이_실패하면_종전대로_502다")
    void genuineFailureWithBudgetLeftIsStillError() {
        stubFailure();

        assertThatThrownBy(() -> service.track(request(List.of(SRC1, SRC2)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    private void stubFailure() {
        when(aiServerClient.track(any()))
                .thenReturn(Mono.error(new IllegalStateException("ai down")));
    }

    // ── 취소와의 구분 ─────────────────────────────────────────────────────────────

    @Test
    @Timeout(20)
    @DisplayName("예산_소진은_취소가_아니다_취소_예외로_끝나지_않는다")
    void budgetExhaustionIsNotCancellation() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubTrackOk();

        // 취소 예외로 끝나면 화면은 «사용자가 취소했다» 로 안내한다 — 아무도 취소하지 않았는데.
        Sam2TrackOutcome outcome = service.track(request(List.of(SRC1, SRC2)), reviewer);
        assertThat(outcome.response().truncated()).isTrue();
    }

    @Test
    @Timeout(20)
    @DisplayName("취소는_예산_소진으로_흡수되지_않고_취소로_끝난다")
    void cancellationIsNotAbsorbedIntoTruncation() {
        AiCallCancellationRegistry registry = new AiCallCancellationRegistry();
        // 예산은 넉넉하지만 사용자가 첫 프레임 도중 취소한다.
        try (AiCallScope ignored = registry.open("cancel-1", "1")) {
            when(aiServerClient.track(any())).thenAnswer(inv -> {
                registry.cancel("cancel-1", "1");
                return Mono.just(new Sam2TrackResponse("track-A", square(11, 11, 31, 31), 0.9));
            });

            assertThatThrownBy(() -> service.track(request(List.of(SRC1, SRC2, SRC3)), reviewer))
                    .isInstanceOf(AiCallCancelledException.class);
        } finally {
            registry.unbind();
        }
    }
}
