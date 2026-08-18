package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiCallCancellationRegistry;
import kr.co.cudo.authoring.common.client.AiCallCancelledException;
import kr.co.cudo.authoring.common.client.AiCallScope;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.label.service.YoloTrackService;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 자동 추적의 <b>요청 단위 시간 예산</b>과 <b>부분 결과 계약</b> (순수 Mockito).
 *
 * <p>고정하는 것은 SAM2 추적과 같다 — 예산이 다해도 <b>그때까지의 결과가 살아 있고</b>, 응답이
 * <b>어디까지 했는지</b>를 밝히며, <b>예산 초과와 사용자 취소가 구분</b>된다.
 *
 * <p>시간은 프레임 인코딩으로 흘려보낸다(루프가 데드라인을 «반복 진입 직전» 에 보므로 제한시간
 * 경합 없이 결정적으로 재현된다).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YoloTrackBudgetTest {

    @Mock private AiServerClient aiServerClient;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private SystemConfigService systemConfigService;
    @Mock private FrameImageEncoder frameImageEncoder;
    @Mock private FrameBoundsResolver frameBoundsResolver;
    @Mock private LabelMasterService labelMasterService;

    private YoloTrackService service;

    private static final Long RAW_SN = 9101L;
    private static final Long SRC0 = 6000L;
    private static final Long SRC1 = 6001L;
    private static final Long SRC2 = 6002L;
    private static final Long SRC3 = 6003L;

    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        service = new YoloTrackService(aiServerClient, accessGuard,
                systemConfigService, frameImageEncoder, frameBoundsResolver, labelMasterService);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));

        // 프레임 조회는 인가 검사(verifyAndGet)가 함께 수행한다 — 서비스는 저장소를 직접 읽지 않는다.
        when(accessGuard.verifyAndGet(any(), any())).thenAnswer(inv -> frame((Long) inv.getArgument(0)));
        when(frameImageEncoder.encodeFrame(any())).thenReturn("b64");
        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.empty());
        when(labelMasterService.findLabelIdByDtctType(any())).thenReturn(Optional.empty());
        when(systemConfigService.getInt(any())).thenReturn(null);
    }

    private static LsDataSrc frame(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, srcSn, srcSn + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    private void stubDetect() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 7)))));
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
    @DisplayName("예산이_다해도_그때까지_검출한_프레임_결과는_살아_있다")
    void keepsFramesProcessedBeforeBudgetRanOut() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubDetect();

        YoloTrackResponseDto res = service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2, SRC3)), reviewer);

        assertThat(res.frames()).hasSize(1);
        assertThat(res.frames().get(0).srcSn()).isEqualTo(SRC0);
        assertThat(res.frames().get(0).detections()).hasSize(1);
        verify(aiServerClient, times(1)).predictYoloTrack(any());
    }

    @Test
    @Timeout(20)
    @DisplayName("예산이_다하면_어디까지_했는지와_이어_보낼_값을_응답에_담는다")
    void reportsResumePointWhenBudgetRanOut() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubDetect();

        YoloTrackResponseDto res = service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2, SRC3)), reviewer);

        assertThat(res.truncated()).isTrue();
        assertThat(res.resume()).isNotNull();
        // 아직 처리하지 않은 첫 프레임이 다음 요청의 시작(=트래커 리셋) 프레임이 된다.
        assertThat(res.resume().srcSn()).isEqualTo(SRC1);
        assertThat(res.resume().nextSrcSns()).containsExactly(SRC2, SRC3);
    }

    @Test
    @Timeout(20)
    @DisplayName("예산이_남으면_잘리지_않고_이어_보낼_값도_없다")
    void notTruncatedWhenBudgetSuffices() {
        stubDetect();

        YoloTrackResponseDto res = service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);

        assertThat(res.frames()).hasSize(3);
        assertThat(res.truncated()).isFalse();
        assertThat(res.resume()).isNull();
        verify(aiServerClient, times(3)).predictYoloTrack(any());
    }

    @Test
    @Timeout(20)
    @DisplayName("한_프레임도_들어가지_않는_예산이면_아무_것도_추론하지_않고_잘림을_알린다")
    void truncatesBeforeFirstFrameWhenBudgetIsZero() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ZERO);
        stubDetect();

        YoloTrackResponseDto res = service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);

        assertThat(res.frames()).isEmpty();
        assertThat(res.truncated()).isTrue();
        assertThat(res.resume().srcSn()).isEqualTo(SRC0);
        assertThat(res.resume().nextSrcSns()).containsExactly(SRC1, SRC2);
        verify(aiServerClient, never()).predictYoloTrack(any());
    }

    @Test
    @Timeout(30)
    @DisplayName("예산이_다한_순간의_추론_실패는_502가_아니라_부분_결과로_끝난다")
    void slowCallAtDeadlineBecomesPartialResultNotFailure() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(700));
        when(aiServerClient.predictYoloTrack(any()))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 7)))))
                .thenReturn(Mono.fromCallable(() -> {
                    Thread.sleep(5_000);
                    return new YoloResponse(List.of());
                }).subscribeOn(Schedulers.boundedElastic()));

        YoloTrackResponseDto res = service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);

        // 502 로 올리면 이미 끝난 시작 프레임 결과까지 통째로 버려진다.
        assertThat(res.frames()).hasSize(1);
        assertThat(res.truncated()).isTrue();
        assertThat(res.resume().srcSn()).isEqualTo(SRC1);
        assertThat(res.resume().nextSrcSns()).containsExactly(SRC2);
    }

    @Test
    @Timeout(30)
    @DisplayName("첫_프레임부터_예산_안에_못_끝내면_부분_결과가_아니라_502다")
    void noProgressAtDeadlineIsFailureNotPartialResult() {
        // 진행이 0 이면 «어디까지 했다» 가 없다. 부분 결과로 200 을 주면 화면이 같은 지점을 무한히
        // 다시 요청한다 — 진행이 없을 때만 종전대로 실패를 올린다.
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(300));
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.fromCallable(() -> {
            Thread.sleep(5_000);
            return new YoloResponse(List.of());
        }).subscribeOn(Schedulers.boundedElastic()));

        assertThatThrownBy(() ->
                service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @Timeout(20)
    @DisplayName("예산이_남아있는데_추론이_실패하면_종전대로_502다")
    void genuineFailureWithBudgetLeftIsStillError() {
        when(aiServerClient.predictYoloTrack(any()))
                .thenReturn(Mono.error(new IllegalStateException("ai down")));

        assertThatThrownBy(() -> service.track(new YoloTrackRequest(SRC0, List.of(SRC1)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @Timeout(20)
    @DisplayName("프레임_수에_비례하는_접근_검증도_예산_시계_안에서_돈다")
    void perFrameAccessVerificationIsInsideTheBudgetClock() {
        // 예산 정책은 «요청 하나가 언제 끝나는지는 프레임 수와 무관하다» 를 근거로 가산분을 0 으로
        // 두고 그 값을 화면의 대기 상한으로 내려준다. 그런데 프레임마다 도는 일이 하나라도 예산
        // 시계 밖에 있으면 그 전제가 깨져, DB 가 느려질수록 실제 소요가 상한을 넘고 화면이 정상
        // 요청을 끊는다. 접근 검증은 프레임마다 DB 를 한 번씩 읽으므로 시계 안에 있어야 한다.
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        // 이미 스텁된 메서드를 다시 스텁하므로 doAnswer 를 쓴다 — when(...) 형태는 스텁하는 그 순간
        // 앞선 Answer 를 null 인자로 실제 호출해 버린다.
        doAnswer(inv -> {
            Thread.sleep(300);
            return frame((Long) inv.getArgument(0));
        }).when(accessGuard).verifyAndGet(any(), any());
        stubDetect();

        YoloTrackResponseDto res = service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);

        // 검증만으로 예산(500ms)이 다했다 — 시계 밖이었다면 예산이 고스란히 남아 세 프레임을 전부
        // 추론했을 것이다(그때는 요청 소요가 «검증 900ms + 추론» 이 되어 상한을 넘는다).
        verify(aiServerClient, never()).predictYoloTrack(any());
        assertThat(res.truncated()).isEqualTo(true);
        assertThat(res.frames()).isEmpty();
        // 진행이 0 이어도 이어 보낼 지점은 준다 — 조용히 버리지 않는다.
        assertThat(res.resume().srcSn()).isEqualTo(SRC0);
    }

    @Test
    @Timeout(20)
    @DisplayName("같은_프레임_행을_두_번_읽지_않는다")
    void doesNotReadTheSameFrameRowTwice() {
        // 인가 검사가 이미 조회한 행을 루프에서 다시 읽으면 프레임마다 조회가 두 번씩 나가고,
        // 그 중복이 그대로 위 예산을 깎는다. 검사 호출은 시퀀스의 «서로 다른 프레임 수» 만큼이다.
        stubDetect();

        service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);

        verify(accessGuard, times(1)).verifyAndGet(SRC0, reviewer);
        verify(accessGuard, times(1)).verifyAndGet(SRC1, reviewer);
        verify(accessGuard, times(1)).verifyAndGet(SRC2, reviewer);
    }

    // ── 취소와의 구분 ─────────────────────────────────────────────────────────────

    @Test
    @Timeout(20)
    @DisplayName("예산_소진은_취소가_아니다_취소_예외로_끝나지_않는다")
    void budgetExhaustionIsNotCancellation() {
        ReflectionTestUtils.setField(service, "trackTotalBudget", Duration.ofMillis(500));
        encodeTakes(600);
        stubDetect();

        YoloTrackResponseDto res = service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer);
        assertThat(res.truncated()).isTrue();
    }

    @Test
    @Timeout(20)
    @DisplayName("취소는_예산_소진으로_흡수되지_않고_취소로_끝난다")
    void cancellationIsNotAbsorbedIntoTruncation() {
        AiCallCancellationRegistry registry = new AiCallCancellationRegistry();
        try (AiCallScope ignored = registry.open("cancel-2", "1")) {
            when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> {
                registry.cancel("cancel-2", "1");
                return Mono.just(new YoloResponse(List.of()));
            });

            assertThatThrownBy(() ->
                    service.track(new YoloTrackRequest(SRC0, List.of(SRC1, SRC2)), reviewer))
                    .isInstanceOf(AiCallCancelledException.class);
        } finally {
            registry.unbind();
        }
    }
}
