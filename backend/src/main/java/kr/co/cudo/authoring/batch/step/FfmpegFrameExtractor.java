package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.ManifestJsonlWriter;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * FFmpeg 프레임 추출기 (Phase 5 — FRAME_EXTRACT 단계).
 * <p>
 * 시스템 설정 {@code FFMPEG_OUTPUT_FPS}(1~30) 값을 사용해 초당 N프레임을 추출한다.
 * 예) 1 fps · 113초 영상 → 113 프레임, 2 fps · 60초 영상 → 120 프레임.
 * (실제 운영에서는 net.bramp.ffmpeg 가 ffmpeg 바이너리를 호출. 본 구현은 바이너리 의존을 격리한 추상화.)
 * <p>
 * 보안:
 * - Path Manipulation (CWE-22): 출력 경로는 storage.raw-path 기반 + Path.normalize + base 검증.
 * - Resource Exhaustion (CWE-770): durationSec=0 또는 음수면 INVALID_INPUT.
 *   outputFps 는 [1,30] 범위로 클램프되어 무한정 프레임 생성 방지.
 * - Privacy: 영상 경로는 hash 로 마스킹 후 로그 출력.
 */
@Slf4j
@Component
public class FfmpegFrameExtractor {

    /** outputFps 허용 하한 (ConfigKeys.NUMBER_RANGE 와 동일). */
    static final int MIN_OUTPUT_FPS = 1;
    /** outputFps 허용 상한 (ConfigKeys.NUMBER_RANGE 와 동일). */
    static final int MAX_OUTPUT_FPS = 30;
    /** 시스템 설정 조회 실패 시 사용할 기본 fps. */
    static final int DEFAULT_OUTPUT_FPS = 1;

    private final LsDataSrcRepository srcRepository;
    private final LsDataSrcHstryRepository hstryRepository;
    private final FrameWriter frameWriter;
    private final SystemConfigService systemConfigService;
    private final Path baseRawPath;

    public FfmpegFrameExtractor(LsDataSrcRepository srcRepository,
                                LsDataSrcHstryRepository hstryRepository,
                                FrameWriter frameWriter,
                                SystemConfigService systemConfigService,
                                @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.srcRepository = srcRepository;
        this.hstryRepository = hstryRepository;
        this.frameWriter = frameWriter;
        this.systemConfigService = systemConfigService;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
    }

    /**
     * 영상으로부터 키프레임을 추출하여 LS_DATA_SRC INSERT + manifest.jsonl 작성.
     * 추출 수량은 {@code durationSec × FFMPEG_OUTPUT_FPS}.
     * - REQUIRES_NEW 트랜잭션: 다른 단계 실패가 본 단계 결과에 영향 없도록 격리.
     * - durationSec 0 이하 또는 filePath 미존재면 INVALID_INPUT.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public List<LsDataSrc> extract(LsDataRaw raw) {
        if (raw == null || raw.getFilePath() == null || raw.getFilePath().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 메타가 비어있습니다.");
        }
        Path source = Paths.get(raw.getFilePath());
        if (!frameWriter.sourceExists(source)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "원본 영상을 찾을 수 없습니다: rawSn=" + raw.getRawSn());
        }
        int duration = raw.getDurationSec() == null ? 0 : raw.getDurationSec();
        if (duration <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "durationSec 가 0 이하입니다: rawSn=" + raw.getRawSn());
        }

        int outputFps = getOutputFps();
        int frameCount = computeFrameCount(duration, outputFps);
        Path outputDir = resolveSafeOutputDir(raw.getRawSn());
        Path manifestPath = outputDir.resolve("manifest.jsonl");

        List<LsDataSrc> saved = new ArrayList<>(frameCount);
        try {
            ensureDir(outputDir);
            try (ManifestJsonlWriter mw = new ManifestJsonlWriter(manifestPath)) {
                mw.writeVideoHeader(maskName(raw.getVmsClipId()), 0, 0, frameCount);
                for (int i = 0; i < frameCount; i++) {
                    Path frameFile = outputDir.resolve("frame-" + i + ".jpg");
                    frameWriter.writeFrame(source, frameFile, i);
                    String checksum = checksumOf(frameFile);
                    mw.writeKeyFrame(i, (long) i * 1000L, checksum);

                    LocalDateTime capturedAt = raw.getCapturedAt() == null
                            ? null : raw.getCapturedAt().plusMinutes(i);
                    LsDataSrc src = srcRepository.save(
                            LsDataSrc.create(raw.getRawSn(), i, frameFile.toString(), capturedAt));
                    hstryRepository.save(LsDataSrcHstry.recordCreated(src.getSrcSn()));
                    saved.add(src);
                }
            }
        } catch (IOException e) {
            log.error("[Batch][FrameExtract] failed rawSn={} err={}", raw.getRawSn(), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "프레임 추출 실패", e);
        }
        log.info("[Batch][FrameExtract] extracted rawSn={} frames={} outputFps={}",
                raw.getRawSn(), saved.size(), outputFps);
        return saved;
    }

    /**
     * 추출 프레임 수 계산: durationSec × outputFps. 최소 1프레임 보장.
     * outputFps 는 [MIN_OUTPUT_FPS, MAX_OUTPUT_FPS] 범위로 클램프된 값을 전제로 한다.
     */
    static int computeFrameCount(int durationSec, int outputFps) {
        int fps = clampOutputFps(outputFps);
        return Math.max(1, durationSec * fps);
    }

    /** outputFps 값을 [MIN, MAX] 범위로 클램프. 범위 밖이면 기본값으로 보정. */
    static int clampOutputFps(int outputFps) {
        if (outputFps < MIN_OUTPUT_FPS || outputFps > MAX_OUTPUT_FPS) {
            return DEFAULT_OUTPUT_FPS;
        }
        return outputFps;
    }

    /**
     * 시스템 설정 FFMPEG_OUTPUT_FPS 조회. 조회 실패 또는 범위 이탈 시 기본 1 fps 폴백.
     * - SystemConfigService 가 Caffeine 캐시(TTL 60s) 적용되어 핫 패스에서도 부담 적음.
     * - 캐시/DB 장애 발생 시 배치가 중단되지 않도록 광범위 catch + WARN 로그.
     */
    private int getOutputFps() {
        try {
            Integer raw = systemConfigService.getInt(ConfigKeys.FFMPEG_OUTPUT_FPS);
            int value = raw == null ? DEFAULT_OUTPUT_FPS : raw;
            return clampOutputFps(value);
        } catch (Exception e) {
            log.warn("[Batch][FrameExtract] FFMPEG_OUTPUT_FPS 조회 실패, 기본 {} fps 사용 err={}",
                    DEFAULT_OUTPUT_FPS, e.getMessage());
            return DEFAULT_OUTPUT_FPS;
        }
    }

    /**
     * baseRawPath 하위에 영상별 디렉토리를 안전하게 생성.
     * - resolved 가 baseRawPath 외부로 빠지면 거부 (Path Manipulation 방어).
     */
    private Path resolveSafeOutputDir(Long rawSn) {
        Path resolved = baseRawPath.resolve("frames").resolve(String.valueOf(rawSn)).normalize();
        if (!resolved.startsWith(baseRawPath)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    private void ensureDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    private String maskName(String name) {
        if (name == null) return "anonymous";
        return Integer.toHexString(name.hashCode());
    }

    private String checksumOf(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    /**
     * 프레임 쓰기 추상화. 운영은 net.bramp.ffmpeg 으로 실제 추출,
     * 단위 테스트는 빈 jpg 더미 파일 작성. 본체는 BatchStep 책임 분리.
     */
    public interface FrameWriter {
        boolean sourceExists(Path sourceVideo);
        void writeFrame(Path sourceVideo, Path outputFrame, int frameIndex) throws IOException;
    }
}
