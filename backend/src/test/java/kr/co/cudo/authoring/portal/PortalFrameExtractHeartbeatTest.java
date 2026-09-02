package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.step.BrampFfmpegFrameWriter;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractRunner;
import kr.co.cudo.authoring.portal.service.PortalFrameExtractTxService;
import kr.co.cudo.authoring.portal.service.PortalVideoProbe;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「무갱신 경과」 판정이 <b>정상 추출을 죽이지 않는다</b>는 성질의 회귀 가드.
 *
 * <h3>이 가드가 없으면 무슨 일이 벌어지나</h3>
 * <p>고착 스윕({@code PortalUploadSweepJob#failStuckUploads})은 최종 변경 일시가 갱신되지 않은 채
 * 지난 시간으로 방치를 판정하고, 그 판정은 <b>삭제의 예고</b>다(FAILED → 실패 보존기간 경과 →
 * 부분 프레임과 <b>원본 영상 파일·행까지 비가역 삭제</b>). 그래서 추출 러너의 하트비트가 그 값을
 * 밀어내야 하는데, 하트비트 주기가 <b>프레임 개수</b> 기준이면 그 사이 간격에 상한이 없다 —
 * 프레임 정확 추출은 입력 seek 없이 파일 처음부터 디코딩해 1장의 비용이 프레임 위치에 비례해
 * 커지므로, 뒤쪽 몇 장만으로도 커트라인을 넘긴다.
 *
 * <p><b>이 결함의 성질은 아무도 실패하지 않는다는 것</b>이다 — 러너는 정상 동작하고 스윕도 정상
 * 동작하며, 둘의 시간 축이 어긋나 사용자 데이터만 조용히 사라진다. 그래서 가드가 없으면 재발한다.
 */
class PortalFrameExtractHeartbeatTest {

    private static final long ULD_SN = 909L;
    /** 고착 커트라인(분) — yml 기본값과 같은 값. */
    private static final long STUCK_TIMEOUT_MINUTES = 30L;
    /** 프레임 1장이 이만큼 걸린다고 가정(가짜 시계) — 프레임 개수 기준 하트비트로는 못 버티는 크기. */
    private static final long FRAME_COST_MINUTES = 5L;

    @TempDir
    Path storageDir;

    private PortalFrameExtractTxService txService;
    private FfmpegFrameExtractor.FrameWriter frameWriter;
    private SystemConfigService systemConfigService;
    /** 가짜 단조 시계(ns) — 프레임을 쓸 때마다 앞으로 감긴다. */
    private final AtomicLong fakeNanos = new AtomicLong(0L);
    /** 하트비트가 발생한 시각(ns) 기록. */
    private final List<Long> beatNanos = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        txService = mock(PortalFrameExtractTxService.class);
        frameWriter = mock(FfmpegFrameExtractor.FrameWriter.class);
        systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getInt(anyString())).thenReturn(5);
        when(frameWriter.sourceExists(any())).thenReturn(true);
        when(txService.touchProcessing(ULD_SN)).thenAnswer(inv -> {
            beatNanos.add(fakeNanos.get());
            return true;
        });
        when(txService.completeReady(eq(ULD_SN), any(), any(), any())).thenReturn(true);
    }

    private PortalUploadProperties props() {
        return new PortalUploadProperties(
                5_368_709_120L, List.of("mp4"), storageDir.toString(),
                List.of("jpg"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, STUCK_TIMEOUT_MINUTES);
    }

    /** 후처리 중으로 전이된 자산 스냅샷 — 상태는 이제 엔티티가 아니라 메타 원장이 소유한다. */
    private PortalUploadAsset processingUld() throws Exception {
        Path video = Files.createFile(storageDir.resolve("video.mp4"));
        return new PortalUploadAsset(ULD_SN, "u1", PortalUploadLedger.TYPE_VIDEO,
                "v.mp4", video.toString(), 1024L, "video/mp4",
                PortalUploadLedger.STATUS_PROCESSING, null, null, 0, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("프레임_한장이_오래_걸려도_무갱신_경과가_고착_커트라인을_넘지_않는다")
    void heartbeatGapStaysUnderStuckCutline() throws Exception {
        // given — 60초 영상(간격 5초 → 12프레임). 프레임 1장을 뽑는 데 5분이 걸린다고 가정하면
        //   전체는 60분으로 커트라인(30분)의 2배다. 그래도 «무갱신» 경과는 커트라인 밑이어야 한다.
        when(txService.beginProcessing(ULD_SN)).thenReturn(Optional.of(processingUld()));
        doAnswer(inv -> {
            Path out = inv.getArgument(1);
            Files.createDirectories(out.getParent());
            Files.writeString(out, "frame");
            fakeNanos.addAndGet(TimeUnit.MINUTES.toNanos(FRAME_COST_MINUTES));
            return null;
        }).when(frameWriter).writeFrameByNumber(any(), any(), anyInt());

        PortalFrameExtractRunner runner = new PortalFrameExtractRunner(
                txService, path -> new PortalVideoProbe.Result(true, 60.0, 30.0),
                frameWriter, systemConfigService, props(), fakeNanos::get);

        // when
        runner.runAsync(ULD_SN);

        // then — 총 경과는 커트라인을 훨씬 넘었지만(전제 확인), 정상 완료여야 한다.
        assertThat(fakeNanos.get())
                .as("전제: 총 추출 시간이 고착 커트라인을 넘는 상황을 실제로 만들었는가")
                .isGreaterThan(TimeUnit.MINUTES.toNanos(STUCK_TIMEOUT_MINUTES));
        verify(txService).completeReady(eq(ULD_SN), any(), any(), any());
        verify(txService, never()).markFailed(anyLong(), anyString());

        // 핵심 — 하트비트 사이 «무갱신» 최대 경과가 커트라인 미만.
        assertThat(beatNanos).as("하트비트가 한 번도 없으면 판정 자체가 성립하지 않는다").isNotEmpty();
        long cutlineNanos = TimeUnit.MINUTES.toNanos(STUCK_TIMEOUT_MINUTES);
        long maxGap = 0L;
        long previous = 0L; // 러너 진입 시점(가짜 시계 0)부터 첫 하트비트까지도 무갱신 구간이다.
        for (Long beat : beatNanos) {
            maxGap = Math.max(maxGap, beat - previous);
            previous = beat;
        }
        // 마지막 하트비트 ~ 추출 종료(completeReady 가 행을 갱신하는 시점)까지의 꼬리 구간.
        maxGap = Math.max(maxGap, fakeNanos.get() - previous);

        assertThat(maxGap)
                .as("무갱신 최대 경과(%d분)가 고착 커트라인(%d분) 이상이면 정상 추출이 방치로 판정돼 "
                                + "부분 프레임과 원본 영상이 비가역 삭제된다",
                        TimeUnit.NANOSECONDS.toMinutes(maxGap), STUCK_TIMEOUT_MINUTES)
                .isLessThan(cutlineNanos);
    }

    @Test
    @DisplayName("하트비트_간격은_고착_커트라인에서_파생돼_커트라인을_줄여도_따라_줄어든다")
    void heartbeatIntervalDerivesFromCutline() {
        // 상수로 박으면 운영자가 커트라인만 줄였을 때 하트비트가 그보다 뜸해져 정상 추출이 죽는다.
        assertThat(PortalFrameExtractRunner.heartbeatIntervalSec(30L)).isEqualTo(60L);   // 상한
        assertThat(PortalFrameExtractRunner.heartbeatIntervalSec(2L)).isEqualTo(30L);    // 1/4
        assertThat(PortalFrameExtractRunner.heartbeatIntervalSec(0L))
                .isEqualTo(PortalFrameExtractRunner.HEARTBEAT_MIN_INTERVAL_SEC);         // 하한
        assertThat(PortalFrameExtractRunner.heartbeatIntervalSec(-5L))
                .isEqualTo(PortalFrameExtractRunner.HEARTBEAT_MIN_INTERVAL_SEC);
    }

    @Test
    @DisplayName("하트비트_간격과_프레임_대기_상한의_합이_고착_커트라인보다_작다")
    void heartbeatBudgetFitsUnderCutline() {
        // 하트비트는 프레임 «사이»에서만 칠 수 있으므로, 한 장이 오래 걸리면 그만큼 창이 벌어진다.
        // 그 창의 상한이 프레임 대기 상한이고, 둘의 합이 커트라인을 넘으면 남은 창으로 다시 샌다.
        long heartbeatSec = PortalFrameExtractRunner.heartbeatIntervalSec(STUCK_TIMEOUT_MINUTES);
        long budgetSec = heartbeatSec + BrampFfmpegFrameWriter.DEFAULT_FRAME_TIMEOUT_SEC;

        assertThat(budgetSec)
                .as("하트비트 간격(%ds) + 프레임 대기 상한(%ds) 이 고착 커트라인(%d분)을 넘으면 "
                                + "한 프레임이 상한까지 끌 때 정상 추출이 방치로 판정된다",
                        heartbeatSec, BrampFfmpegFrameWriter.DEFAULT_FRAME_TIMEOUT_SEC, STUCK_TIMEOUT_MINUTES)
                .isLessThan(TimeUnit.MINUTES.toSeconds(STUCK_TIMEOUT_MINUTES));
    }
}
