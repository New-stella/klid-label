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
 */
@Slf4j
@Component
public class PortalFrameExtractRunner {

    private static final String FRAMES_SUBDIR = "frames";
    /** 진행 중 삭제/전이 감지 주기(프레임 단위). */
    private static final int PROGRESS_CHECK_EVERY = 50;
    /** sysconfig 미설정 시 프레임 간격 폴백(초). */
    private static final int DEFAULT_INTERVAL_SEC = 5;
    /** fps 미상 시 폴백. */
    private static final double DEFAULT_FPS = 30.0;

    private final PortalFrameExtractTxService txService;
    private final PortalVideoProbe videoProbe;
    private final FfmpegFrameExtractor.FrameWriter frameWriter;
    private final SystemConfigService systemConfigService;
    private final PortalUploadProperties properties;
    private final Path storageRoot;

    public PortalFrameExtractRunner(PortalFrameExtractTxService txService,
                                    PortalVideoProbe videoProbe,
                                    FfmpegFrameExtractor.FrameWriter frameWriter,
                                    SystemConfigService systemConfigService,
                                    PortalUploadProperties properties) {
        this.txService = txService;
        this.videoProbe = videoProbe;
        this.frameWriter = frameWriter;
        this.systemConfigService = systemConfigService;
        this.properties = properties;
        this.storageRoot = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
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
            for (int i = 0; i < frameNumbers.size(); i++) {
                // #4/#5 + adversarial #2: N프레임마다 하트비트(mdfcnDt touch)로 스윕 고착 오판을 막고,
                // 동시에 삭제/전이(0행)를 감지해 즉시 중단한다.
                if (i % PROGRESS_CHECK_EVERY == 0 && !txService.touchProcessing(uldSn)) {
                    log.info("[PortalFrame] aborted — asset gone mid-extract uldSn={}", uldSn);
                    cleanup(written, outputDir);
                    return;
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
