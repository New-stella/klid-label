package kr.co.cudo.authoring.portal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 9 — 포털 SAM2 인터랙티브 추론 서비스 단위 테스트 (순수 Mockito).
 *
 * <p><b>구조적 no-persist</b>: {@link kr.co.cudo.authoring.portal.service.PortalSam2Service} 는
 * LsDataLblRepository 의존이 아예 없다 — 내부 LS_DATA_LBL 저장이 구조적으로 불가능하다(CRITICAL #1).
 * 실제 DB row 불변은 {@link PortalSam2NoPersistIntegrationTest} 가 검증한다.
 *
 * <p>HIGH 시나리오: bulkhead 429(#4), IDOR APPROVED 재검증 403(#5).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalSam2ServiceTest {

    private static final Long SRC_SN = 300L;
    private static final Long NEXT_SN = 301L;
    private static final Long RAW_SN = 400L;

    @Mock AiServerClient aiServerClient;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsRawDataStatusRepository rawDataStatusRepository;
    @Mock FrameImageEncoder frameImageEncoder;

    private kr.co.cudo.authoring.portal.service.PortalSam2Service service;

    private final TokenClaims portalUser =
            new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));

    private Bulkhead roomyBulkhead() {
        return Bulkhead.of("portalSam2Test", BulkheadConfig.custom()
                .maxConcurrentCalls(25).maxWaitDuration(Duration.ZERO).build());
    }

    /** 넉넉한 per-user rate limiter (일반 케이스는 제한에 걸리지 않음). config 이름 = portalSam2. */
    private RateLimiterRegistry roomyRateLimiter() {
        return RateLimiterRegistry.of(Map.of("portalSam2", RateLimiterConfig.custom()
                .limitForPeriod(1000).limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO).build()));
    }

    private void seedApprovedFrame(Long srcSn) {
        LsDataSrc frame = LsDataSrc.create(RAW_SN, 0, srcSn + ".jpg", null);
        when(srcRepository.findById(srcSn)).thenReturn(Optional.of(frame));
        LsRawDataStatus approved = LsRawDataStatus.initial(RAW_SN);
        approved.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(RAW_SN)).thenReturn(Optional.of(approved));
        when(frameImageEncoder.encodeToBase64(srcSn + ".jpg")).thenReturn("b64");
    }

    @BeforeEach
    void setUp() {
        service = new kr.co.cudo.authoring.portal.service.PortalSam2Service(
                aiServerClient, srcRepository, rawDataStatusRepository, frameImageEncoder,
                roomyBulkhead(), roomyRateLimiter());
    }

    private Sam2SegmentRequest segReq() {
        return new Sam2SegmentRequest(SRC_SN, List.of(List.of(10.0, 10.0)), null);
    }

    private Sam2TrackRequest trackReq() {
        return new Sam2TrackRequest(SRC_SN, "track-1",
                List.of(List.of(10.0, 10.0), List.of(30.0, 30.0), List.of(10.0, 30.0)),
                "person", List.of(NEXT_SN));
    }

    // ── segment ──

    @Test
    @DisplayName("포털_SAM2세그_APPROVED영상_좌표만_반환")
    void segment_approved_returnsPolygon() {
        seedApprovedFrame(SRC_SN);
        when(aiServerClient.segment(any())).thenReturn(Mono.just(
                new Sam2Response(List.of(List.of(1.0, 1.0), List.of(2.0, 2.0), List.of(1.0, 2.0)), 0.9)));

        Sam2SegmentResponse res = service.segment(segReq(), portalUser);

        assertThat(res.polygon()).hasSize(3);
        assertThat(res.score()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("포털_비APPROVED_srcSn_SAM2_거부")
    void segment_notApproved_forbidden() {
        LsDataSrc frame = LsDataSrc.create(RAW_SN, 0, "300.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(frame));
        LsRawDataStatus pending = LsRawDataStatus.initial(RAW_SN); // PENDING (비APPROVED)
        when(rawDataStatusRepository.findById(RAW_SN)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.segment(segReq(), portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("포털_존재하지않는_srcSn_SAM2_404")
    void segment_notFound() {
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.segment(segReq(), portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("포털_SAM2_토큰없으면_401")
    void segment_noToken_unauthorized() {
        assertThatThrownBy(() -> service.segment(segReq(), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    // ── track (no persist) ──

    @Test
    @DisplayName("포털_SAM2추적_APPROVED영상_좌표만_반환_persist없음")
    void track_approved_returnsCoords() {
        seedApprovedFrame(SRC_SN);
        seedApprovedFrame(NEXT_SN);
        when(aiServerClient.track(any())).thenReturn(Mono.just(
                new Sam2TrackResponse("track-1",
                        List.of(List.of(20.0, 20.0), List.of(40.0, 40.0), List.of(20.0, 40.0)), 0.85)));

        Sam2TrackResponseDto res = service.track(trackReq(), portalUser);

        assertThat(res.tracked()).hasSize(1);
        assertThat(res.tracked().get(0).srcSn()).isEqualTo(NEXT_SN);
        assertThat(res.tracked().get(0).points()).hasSize(3);
    }

    @Test
    @DisplayName("포털_SAM2추적_후속프레임_비APPROVED_거부_IDOR")
    void track_nextFrameNotApproved_forbidden() {
        seedApprovedFrame(SRC_SN);
        // 후속 프레임은 다른 (비APPROVED) 영상 소속
        LsDataSrc nextFrame = LsDataSrc.create(999L, 1, "301.jpg", null);
        when(srcRepository.findById(NEXT_SN)).thenReturn(Optional.of(nextFrame));
        when(rawDataStatusRepository.findById(999L)).thenReturn(Optional.empty());
        when(aiServerClient.track(any())).thenReturn(Mono.just(
                new Sam2TrackResponse("track-1", List.of(List.of(1.0, 1.0)), 0.5)));

        assertThatThrownBy(() -> service.track(trackReq(), portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── HIGH #4: bulkhead 429 ──

    @Test
    @DisplayName("포털_SAM2_동시호출_bulkhead_초과_429")
    void bulkheadRejectsExcessConcurrent() throws Exception {
        Bulkhead bulkhead = Bulkhead.of("portalSam2Test1", BulkheadConfig.custom()
                .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        service = new kr.co.cudo.authoring.portal.service.PortalSam2Service(
                aiServerClient, srcRepository, rawDataStatusRepository, frameImageEncoder,
                bulkhead, roomyRateLimiter());
        seedApprovedFrame(SRC_SN);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 첫 요청이 AI 구독 상태에서 대기 → bulkhead permit(1개) 점유 유지.
        when(aiServerClient.segment(any())).thenReturn(Mono.fromCallable(() -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new Sam2Response(List.of(List.of(1.0, 1.0), List.of(2.0, 2.0), List.of(1.0, 2.0)), 0.9);
        }));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Sam2SegmentResponse> first = pool.submit(() -> service.segment(segReq(), portalUser));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            Future<?> second = pool.submit(() -> service.segment(segReq(), portalUser));
            assertThatThrownBy(second::get)
                    .cause()
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

            release.countDown();
            first.get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── 이슈3 (CWE-770): per-user rate limit 429 ──

    @Test
    @DisplayName("포털_SAM2_사용자별_rate_limit_초과_429")
    void perUserRateLimitExceeded() {
        // limit-for-period=1 → 같은 사용자의 두 번째 요청은 permit 획득 실패로 429.
        RateLimiterRegistry strict = RateLimiterRegistry.of(Map.of("portalSam2",
                RateLimiterConfig.custom()
                        .limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1))
                        .timeoutDuration(Duration.ZERO).build()));
        service = new kr.co.cudo.authoring.portal.service.PortalSam2Service(
                aiServerClient, srcRepository, rawDataStatusRepository, frameImageEncoder,
                roomyBulkhead(), strict);
        seedApprovedFrame(SRC_SN);
        when(aiServerClient.segment(any())).thenReturn(Mono.just(
                new Sam2Response(List.of(List.of(1.0, 1.0), List.of(2.0, 2.0), List.of(1.0, 2.0)), 0.9)));

        // 첫 요청은 permit 획득 → 정상.
        service.segment(segReq(), portalUser);

        // 두 번째 요청(같은 사용자)은 permit 소진 → 429.
        assertThatThrownBy(() -> service.segment(segReq(), portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    // ── 이슈2 (CWE-117): trackId 로그 정제 ──

    @Test
    @DisplayName("trackId에_CRLF_포함시_로그_정제")
    void trackIdCrlfSanitizedInLog() {
        seedApprovedFrame(SRC_SN);
        seedApprovedFrame(NEXT_SN);
        when(aiServerClient.track(any())).thenReturn(Mono.just(
                new Sam2TrackResponse("track-1",
                        List.of(List.of(20.0, 20.0), List.of(40.0, 40.0), List.of(20.0, 40.0)), 0.85)));

        Logger logger = (Logger) LoggerFactory.getLogger(
                kr.co.cudo.authoring.portal.service.PortalSam2Service.class);
        Level prev = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Sam2TrackRequest req = new Sam2TrackRequest(SRC_SN, "evil\r\ninjected",
                    List.of(List.of(10.0, 10.0), List.of(30.0, 30.0), List.of(10.0, 30.0)),
                    "person", List.of(NEXT_SN));
            service.track(req, portalUser);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(prev);
        }

        // 완료 로그의 trackId 는 CRLF 제거되어 'evilinjected' 로 정제 출력(원문 개행 미유입).
        boolean sanitized = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("Sam2Track"))
                .anyMatch(m -> m.contains("evilinjected")
                        && !m.contains("\r") && !m.contains("\n"));
        assertThat(sanitized).isTrue();
    }
}
