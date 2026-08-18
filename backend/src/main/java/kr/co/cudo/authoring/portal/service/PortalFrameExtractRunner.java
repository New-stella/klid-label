package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * 포털 영상 프레임 추출 비동기 실행기.
 *
 * <p>{@link kr.co.cudo.authoring.portal.listener.PortalFrameExtractBridge} 가
 * {@code PortalVideoUploadedEvent}(AFTER_COMMIT) 수신 후 호출한다. 실제 추출은 포털 전용
 * {@code portalExtractExecutor} 풀의 별도 스레드에서 수행한다(관제 배치 풀과 자원 격리 — security M-3).
 *
 * <p>트랜잭션 경계는 {@link PortalFrameExtractTxService} 로 위임하고, ffmpeg 루프(장시간 I/O)는
 * 트랜잭션 밖에서 수행한다.
 * <ol>
 *   <li>#4 러너 진입 시 UPLOADED→PROCESSING 원자 전이(전이 실패=삭제/중복이면 즉시 중단).</li>
 *   <li>#6 ffprobe 길이/fps(타임아웃은 probe 구현), fps 미상 시 30 폴백.</li>
 *   <li>#8 프레임 간격은 시작 시 sysconfig 스냅샷 1회. #9 상한(maxFrames) 초과 시 균등 샘플링, 최소 1.</li>
 *   <li>#5 프레임 전체 추출 후 원자 커밋(READY). 진행 중 자산 삭제 감지 시 즉시 중단 + 파일 정리.</li>
 *   <li>#7 ffmpeg 실패 시 부분 파일 정리 + markFailed.</li>
 * </ol>
 *
 * <h3>★ 하트비트 — 「무갱신 경과」 판정을 살리는 쪽 계약</h3>
 * <p>고착 스윕({@code PortalUploadSweepJob#failStuckUploads})은 <b>최종 변경 일시가 갱신되지 않은 채
 * 지난 시간</b>으로 방치를 판정하고, 그 판정은 <b>삭제의 예고</b>다(FAILED → 실패 보존기간 경과 →
 * 파일·행 비가역 삭제). 그래서 이 러너는 추출 중 {@code touchProcessing} 으로 그 값을 밀어낸다.
 *
 * <p><b>그 하트비트는 「프레임 개수」가 아니라 「경과 시간」 기준이어야 한다.</b> 개수 기준이면
 * 하트비트 사이 간격에 상한이 없다 — 프레임 1장의 추출 비용은 영상 내 위치에 비례해 커지므로
 * (아래 「남은 창」) 뒤쪽 몇 장만으로도 커트라인을 넘겨 <b>정상 추출이 방치로 판정</b>된다.
 *
 * <h3>남은 창 — 프레임 1장이 커트라인을 넘는 경우</h3>
 * <p>하트비트는 프레임 <b>사이</b>에서만 칠 수 있으므로, 한 장을 뽑는 호출이 커트라인보다 오래
 * 걸리면 이 러너만으로는 막을 수 없다. 그 창은 <b>프레임 추출 프로세스의 대기 상한</b>
 * ({@code authoring.ffmpeg.frame-timeout-sec} — {@code BrampFfmpegFrameWriter})이 닫는다.
 * 두 값의 관계는 <b>하트비트 간격 + 프레임 대기 상한 &lt; 고착 커트라인</b> 이어야 하며
 * {@code PortalFrameExtractHeartbeatTest} 가 기본값 조합으로 고정한다.
 */
@Slf4j
@Component
public class PortalFrameExtractRunner {

    private static final String FRAMES_SUBDIR = "frames";
    /** sysconfig 미설정 시 프레임 간격 폴백(초). */
    private static final int DEFAULT_INTERVAL_SEC = 5;
    /** fps 미상 시 폴백. */
    private static final double DEFAULT_FPS = 30.0;

    /**
     * 하트비트 최소 간격의 <b>상한</b>(초) — 커트라인이 아무리 길어도 이보다 드물게 치지 않는다.
     * 하트비트 1회는 짧은 조건부 UPDATE 1건이라 1분 주기는 부하로 유의미하지 않다.
     */
    public static final long HEARTBEAT_MAX_INTERVAL_SEC = 60L;
    /**
     * 하트비트 최소 간격의 <b>하한</b>(초) — 커트라인이 아주 짧게 설정돼도 프레임마다 DB 쓰기가
     * 폭주하지 않게 막는다.
     */
    public static final long HEARTBEAT_MIN_INTERVAL_SEC = 5L;

    private final PortalFrameExtractTxService txService;
    private final PortalVideoProbe videoProbe;
    private final FfmpegFrameExtractor.FrameWriter frameWriter;
    private final SystemConfigService systemConfigService;
    private final PortalUploadProperties properties;
    private final Path storageRoot;
    /** 단조 시각 원천(ns) — 하트비트 간격 판정용. 테스트가 가짜 시계를 주입한다. */
    private final LongSupplier nanoTime;
    /** 하트비트 최소 간격(ns) — 고착 커트라인에서 파생(아래 {@link #heartbeatIntervalSec}). */
    private final long heartbeatIntervalNanos;

    @org.springframework.beans.factory.annotation.Autowired
    public PortalFrameExtractRunner(PortalFrameExtractTxService txService,
                                    PortalVideoProbe videoProbe,
                                    FfmpegFrameExtractor.FrameWriter frameWriter,
                                    SystemConfigService systemConfigService,
                                    PortalUploadProperties properties) {
        this(txService, videoProbe, frameWriter, systemConfigService, properties, System::nanoTime);
    }

    /** 시각 원천 주입 생성자 — 하트비트 간격 검증(회귀 가드) 전용. */
    public PortalFrameExtractRunner(PortalFrameExtractTxService txService,
                                    PortalVideoProbe videoProbe,
                                    FfmpegFrameExtractor.FrameWriter frameWriter,
                                    SystemConfigService systemConfigService,
                                    PortalUploadProperties properties,
                                    LongSupplier nanoTime) {
        this.txService = txService;
        this.videoProbe = videoProbe;
        this.frameWriter = frameWriter;
        this.systemConfigService = systemConfigService;
        this.properties = properties;
        this.storageRoot = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
        this.nanoTime = nanoTime;
        this.heartbeatIntervalNanos =
                TimeUnit.SECONDS.toNanos(heartbeatIntervalSec(properties.stuckTimeoutMinutes()));
    }

    /**
     * 하트비트 최소 간격(초) — 고착 커트라인({@code portal.upload.stuck-timeout-minutes})에서 파생한다.
     *
     * <p>커트라인의 <b>1/4</b>을 취하고 {@link #HEARTBEAT_MIN_INTERVAL_SEC}~{@link #HEARTBEAT_MAX_INTERVAL_SEC}
     * 로 자른다. 상수로 박지 않고 파생하는 이유는, 운영자가 커트라인을 짧게 줄였을 때 하트비트가 그보다
     * 뜸하면 <b>정상 추출이 그대로 방치로 판정</b>되기 때문이다 — 두 값이 따로 놀면 안 된다.
     * (커트라인이 0·음수여도 여기서는 하한으로 잘려 무해하다. 그 경우 스윕은 아예 그 회차를 건너뛴다.)
     */
    public static long heartbeatIntervalSec(long stuckTimeoutMinutes) {
        long quarterSec = Math.max(0L, stuckTimeoutMinutes) * 60L / 4L;
        return Math.max(HEARTBEAT_MIN_INTERVAL_SEC, Math.min(HEARTBEAT_MAX_INTERVAL_SEC, quarterSec));
    }

    @Async("portalExtractExecutor")
    public void runAsync(Long uldSn) {
        if (uldSn == null) {
            return;
        }
        try {
            extract(uldSn);
        } catch (Exception e) {
            // @Async — 예외 전파 금지. markFailed 는 extract 내부에서 이미 수행.
            log.warn("[PortalFrame] runner failed uldSn={} cause={}", uldSn, e.getClass().getSimpleName());
        }
    }

    /** 동기 추출 본체 — 단위 테스트가 직접 호출 가능. */
    void extract(Long uldSn) {
        // #4: UPLOADED → PROCESSING 원자 전이. 실패면 삭제/중복 → 즉시 중단.
        Optional<LsPortalUld> begun = txService.beginProcessing(uldSn);
        if (begun.isEmpty()) {
            log.info("[PortalFrame] skip — not UPLOADED or deleted uldSn={}", uldSn);
            return;
        }
        LsPortalUld uld = begun.get();
        Path source = Paths.get(uld.getFilePathNm());
        Path outputDir = resolveSafeFramesDir(uldSn);
        List<Path> written = new ArrayList<>();
        try {
            if (!frameWriter.sourceExists(source)) {
                throw new IllegalStateException("영상 파일이 존재하지 않습니다.");
            }
            // #6: 길이/fps 프로브(타임아웃은 구현 내부). fps 미상 시 폴백.
            PortalVideoProbe.Result probe = videoProbe.probe(source);
            double fps = probe.fps() > 0 ? probe.fps() : DEFAULT_FPS;
            double duration = probe.durationSec();
            // #8: 프레임 간격 sysconfig 스냅샷 1회.
            int intervalSec = snapshotIntervalSec();

            // #9: 간격 × 상한 → 프레임 번호 목록(균등 샘플링, 최소 1).
            List<Integer> frameNumbers = computeFrameNumbers(duration, fps, intervalSec, properties.maxFrames());

            Files.createDirectories(outputDir);
            List<LsPortalUldFrme> frames = new ArrayList<>(frameNumbers.size());
            long lastBeatNanos = 0L;
            boolean beatenOnce = false;
            for (int i = 0; i < frameNumbers.size(); i++) {
                // #4/#5 + adversarial #2: 하트비트(mdfcnDt touch)로 스윕 고착 오판을 막고, 동시에
                // 삭제/전이(0행)를 감지해 즉시 중단한다.
                //
                // ★ 주기는 「프레임 개수」가 아니라 「경과 시간」이다. 스윕의 판정 축이 무갱신 <b>시간</b>
                //   인데 프레임 개수로 치면 그 사이 간격에 상한이 없다 — 한 장을 뽑는 비용은
                //   프레임 위치에 비례해 커지므로, 뒤쪽 프레임 몇 장만으로도 커트라인을 넘겨
                //   <b>정상 추출이 방치로 판정</b>되고 그 자산은 실패 보존기간 뒤 비가역 삭제된다.
                if (!beatenOnce || nanoTime.getAsLong() - lastBeatNanos >= heartbeatIntervalNanos) {
                    if (!txService.touchProcessing(uldSn)) {
                        log.info("[PortalFrame] aborted — asset gone mid-extract uldSn={}", uldSn);
                        cleanup(written, outputDir);
                        return;
                    }
                    lastBeatNanos = nanoTime.getAsLong();
                    beatenOnce = true;
                }
                Path frameFile = outputDir.resolve("frame-" + i + ".jpg");
                frameWriter.writeFrameByNumber(source, frameFile, frameNumbers.get(i));
                written.add(frameFile);
                frames.add(LsPortalUldFrme.create(uldSn, i, frameFile.toString()));
            }

            // #5: 전체 추출 성공 후 원자 커밋(READY).
            boolean ready = txService.completeReady(uldSn, frames, duration, fps);
            if (!ready) {
                cleanup(written, outputDir);
                return;
            }
            log.info("[PortalFrame] ready uldSn={} frames={}", uldSn, frames.size());
        } catch (Exception e) {
            // #7: ffmpeg/probe 실패 → 부분 파일 정리 + markFailed.
            cleanup(written, outputDir);
            txService.markFailed(uldSn, "프레임 추출 실패: " + e.getClass().getSimpleName());
            log.warn("[PortalFrame] extract failed uldSn={} cause={}", uldSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 프레임 번호 목록 계산(순수 로직 — #8/#9 검증 대상).
     *
     * <p>간격(초)×fps 로 후보 프레임 번호를 만들고, 개수가 {@code maxFrames} 를 넘으면 전체 구간을
     * 균등 샘플링하여 상한 이내로 재계산한다. 영상 길이가 간격보다 짧아도 최소 1프레임(0번)을 보장한다.
     */
    public static List<Integer> computeFrameNumbers(double durationSec, double fps, int intervalSec, int maxFrames) {
        double effFps = fps > 0 ? fps : DEFAULT_FPS;
        int effInterval = intervalSec > 0 ? intervalSec : DEFAULT_INTERVAL_SEC;
        int cap = Math.max(1, maxFrames);
        long totalFrames = Math.max(1L, Math.round(durationSec * effFps));
        long step = Math.max(1L, Math.round(effInterval * effFps));

        List<Integer> candidates = new ArrayList<>();
        for (long n = 0; n < totalFrames; n += step) {
            candidates.add((int) n);
        }
        if (candidates.isEmpty()) {
            candidates.add(0); // #9: 최소 1프레임.
        }
        if (candidates.size() <= cap) {
            return candidates;
        }
        // #9: 상한 초과 → [0, totalFrames) 를 cap 개로 균등 재샘플링(중복 제거·단조 증가).
        List<Integer> sampled = new ArrayList<>(cap);
        double stride = (double) totalFrames / cap;
        int last = -1;
        for (int i = 0; i < cap; i++) {
            int frameNo = (int) Math.floor(i * stride);
            if (frameNo <= last) {
                frameNo = last + 1;
            }
            if (frameNo >= totalFrames) {
                break;
            }
            sampled.add(frameNo);
            last = frameNo;
        }
        if (sampled.isEmpty()) {
            sampled.add(0);
        }
        return sampled;
    }

    private int snapshotIntervalSec() {
        try {
            Integer v = systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FRAME_INTERVAL_SEC);
            return v != null && v > 0 ? v : DEFAULT_INTERVAL_SEC;
        } catch (RuntimeException e) {
            log.warn("[PortalFrame] interval config unavailable — fallback {}", DEFAULT_INTERVAL_SEC);
            return DEFAULT_INTERVAL_SEC;
        }
    }

    private Path resolveSafeFramesDir(Long uldSn) {
        Path resolved = storageRoot.resolve(FRAMES_SUBDIR).resolve(String.valueOf(uldSn)).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalStateException("프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /** 부분 추출 파일 + 디렉토리 best-effort 정리(WARN 로그). */
    private void cleanup(List<Path> written, Path outputDir) {
        for (Path p : written) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.warn("[PortalFrame] partial file cleanup failed cause={}", e.getClass().getSimpleName());
            }
        }
        try (Stream<Path> remaining = Files.exists(outputDir) ? Files.list(outputDir) : Stream.empty()) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(outputDir);
            }
        } catch (IOException e) {
            log.warn("[PortalFrame] output dir cleanup failed cause={}", e.getClass().getSimpleName());
        }
    }
}
