package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.ManifestJsonlWriter;
import kr.co.cudo.authoring.marking.dto.MarkItem;
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
import java.time.Duration;
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
    /** 영상 네이티브 프레임레이트 가정값. MarkItem.frameIndex 는 이 fps 기준. */
    static final int NATIVE_VIDEO_FPS = 30;

    private final LsDataSrcRepository srcRepository;
    private final LsDataSrcHstryRepository hstryRepository;
    private final FrameWriter frameWriter;
    private final SystemConfigService systemConfigService;
    private final Path baseRawPath;
    /** Phase 2: 비식별 프레임 출력 base 경로 (영상 2벌 보관 정책). */
    private final Path baseDeidPath;

    public FfmpegFrameExtractor(LsDataSrcRepository srcRepository,
                                LsDataSrcHstryRepository hstryRepository,
                                FrameWriter frameWriter,
                                SystemConfigService systemConfigService,
                                @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
                                @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String storageDeidPath) {
        this.srcRepository = srcRepository;
        this.hstryRepository = hstryRepository;
        this.frameWriter = frameWriter;
        this.systemConfigService = systemConfigService;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.baseDeidPath = Paths.get(storageDeidPath).toAbsolutePath().normalize();
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
        Path outputDir = resolveSafeOutputDir(baseRawPath, raw.getRawSn());
        return extractInternal(raw, source, outputDir);
    }

    /**
     * 원본 영상과 비식별 영상에서 프레임을 추출한다.
     * 원본 프레임은 FILE_PATH, 비식별 프레임은 같은 row 의 SRC_BKUP_FILE_PATH 에 저장한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public List<LsDataSrc> extractBoth(LsDataRaw raw, String deidVideoPath) {
        List<LsDataSrc> rawFrames = extract(raw);

        if (deidVideoPath == null || deidVideoPath.isBlank()) {
            return rawFrames;
        }
        Path deidSource = Paths.get(deidVideoPath);
        if (!frameWriter.sourceExists(deidSource)) {
            log.warn("[Batch][FrameExtract] deid video missing rawSn={} path={} — RAW only",
                    raw.getRawSn(), maskName(deidVideoPath));
            return rawFrames;
        }
        Path deidOutputDir = resolveSafeOutputDir(baseDeidPath, raw.getRawSn());
        attachDeidFrames(raw, deidSource, deidOutputDir, rawFrames);
        return rawFrames;
    }

    /**
     * V2.0 마킹 위치 기반 프레임 추출.
     * marks 의 frameIndex 에 해당하는 프레임만 추출한다 (원본 + 비식별 2벌).
     * marks 가 비어있으면 INVALID_INPUT.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public List<LsDataSrc> extractByMarks(LsDataRaw raw, String deidVideoPath, List<MarkItem> marks) {
        if (raw == null || raw.getFilePath() == null || raw.getFilePath().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 메타가 비어있습니다.");
        }
        if (marks == null || marks.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "마킹 데이터가 비어있습니다.");
        }
        Path source = Paths.get(raw.getFilePath());
        if (!frameWriter.sourceExists(source)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "원본 영상을 찾을 수 없습니다: rawSn=" + raw.getRawSn());
        }
        Path rawOutputDir = resolveSafeOutputDir(baseRawPath, raw.getRawSn());

        Path deidSource = null;
        Path deidOutputDir = null;
        if (deidVideoPath != null && !deidVideoPath.isBlank()) {
            Path deidPath = Paths.get(deidVideoPath);
            if (frameWriter.sourceExists(deidPath)) {
                deidSource = deidPath;
                deidOutputDir = resolveSafeOutputDir(baseDeidPath, raw.getRawSn());
            } else {
                log.warn("[Batch][FrameExtract] deid video missing rawSn={} path={} — RAW only",
                        raw.getRawSn(), maskName(deidVideoPath));
            }
        }

        List<LsDataSrc> saved = new ArrayList<>(marks.size());
        try {
            ensureDir(rawOutputDir);
            if (deidOutputDir != null) {
                ensureDir(deidOutputDir);
            }
            Path manifestPath = rawOutputDir.resolve("manifest.jsonl");
            try (ManifestJsonlWriter mw = new ManifestJsonlWriter(manifestPath)) {
                mw.writeVideoHeader(maskName(raw.getVmsClipId()), 0, 0, marks.size());
                for (int i = 0; i < marks.size(); i++) {
                    MarkItem mark = marks.get(i);
                    Path frameFile = rawOutputDir.resolve("frame-" + i + ".jpg");
                    long seekMillis = mark.frameIndex() * 1000L / NATIVE_VIDEO_FPS;
                    frameWriter.writeFrame(source, frameFile, seekMillis);
                    String checksum = checksumOf(frameFile);
                    mw.writeKeyFrame(i, seekMillis, checksum);

                    LocalDateTime capturedAt = raw.getCapturedAt() == null
                            ? null : raw.getCapturedAt().plus(Duration.ofMillis(seekMillis));
                    LsDataSrc src = srcRepository.save(
                            LsDataSrc.create(raw.getRawSn(), i, frameFile.toString(), capturedAt));
                    hstryRepository.save(LsDataSrcHstry.recordCreated(src.getSrcSn()));

                    if (deidSource != null && deidOutputDir != null) {
                        Path deidFrame = deidOutputDir.resolve("frame-" + i + ".jpg");
                        frameWriter.writeFrame(deidSource, deidFrame, seekMillis);
                        src.attachDeidPath(deidFrame.toString());
                        hstryRepository.save(LsDataSrcHstry.recordDeidAttached(src.getSrcSn()));
                    }
                    saved.add(src);
                }
            }
        } catch (IOException e) {
            log.error("[Batch][FrameExtract] mark-based extraction failed rawSn={} err={}",
                    raw.getRawSn(), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "마킹 기반 프레임 추출 실패", e);
        }
        log.info("[Batch][FrameExtract] mark-based extracted rawSn={} frames={}", raw.getRawSn(), saved.size());
        return saved;
    }

    /**
     * 공통 추출 헬퍼 — sourceVideo 에서 frameCount 만큼 프레임을 outputDir 에 작성하고
     * LS_DATA_SRC 에 적재한다.
     *  - durationSec 검증은 호출자(extract/extractBoth) 가 raw 기준으로 1회만 수행.
     */
    private List<LsDataSrc> extractInternal(LsDataRaw raw, Path sourceVideo, Path outputDir) {
        int duration = raw.getDurationSec() == null ? 0 : raw.getDurationSec();
        int outputFps = getOutputFps();
        int frameCount = computeFrameCount(duration, outputFps);
        Path manifestPath = outputDir.resolve("manifest.jsonl");

        List<LsDataSrc> saved = new ArrayList<>(frameCount);
        try {
            ensureDir(outputDir);
            try (ManifestJsonlWriter mw = new ManifestJsonlWriter(manifestPath)) {
                mw.writeVideoHeader(maskName(raw.getVmsClipId()), 0, 0, frameCount);
                for (int i = 0; i < frameCount; i++) {
                    Path frameFile = outputDir.resolve("frame-" + i + ".jpg");
                    // outputFps 기반 균등 간격 시크 (단위 미스매치 방지):
                    // 예) 1fps → i*1000ms, 2fps → i*500ms.
                    long seekMillis = (long) i * 1000L / outputFps;
                    frameWriter.writeFrame(sourceVideo, frameFile, seekMillis);
                    String checksum = checksumOf(frameFile);
                    mw.writeKeyFrame(i, seekMillis, checksum);

                    LocalDateTime capturedAt = raw.getCapturedAt() == null
                            ? null : raw.getCapturedAt().plus(Duration.ofMillis(seekMillis));
                    LsDataSrc src = srcRepository.save(LsDataSrc.create(raw.getRawSn(), i, frameFile.toString(), capturedAt));
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

    private void attachDeidFrames(LsDataRaw raw, Path sourceVideo, Path outputDir, List<LsDataSrc> rawFrames) {
        int outputFps = getOutputFps();
        try {
            ensureDir(outputDir);
            for (LsDataSrc src : rawFrames) {
                Path frameFile = outputDir.resolve("frame-" + src.getFrameNo() + ".jpg");
                long seekMillis = (long) src.getFrameNo() * 1000L / outputFps;
                frameWriter.writeFrame(sourceVideo, frameFile, seekMillis);
                src.attachDeidPath(frameFile.toString());
                hstryRepository.save(LsDataSrcHstry.recordDeidAttached(src.getSrcSn()));
            }
        } catch (IOException e) {
            log.error("[Batch][FrameExtract] deid attach failed rawSn={} err={}", raw.getRawSn(), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 프레임 추출 실패", e);
        }
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
     * base 하위에 영상별 디렉토리를 안전하게 생성.
     * - resolved 가 base 외부로 빠지면 거부 (Path Manipulation 방어).
     * - Phase 2: base 인자화 — RAW(baseRawPath) / DEID(baseDeidPath) 양쪽에서 사용.
     */
    private static Path resolveSafeOutputDir(Path base, Long rawSn) {
        Path resolved = base.resolve("frames").resolve(String.valueOf(rawSn)).normalize();
        if (!resolved.startsWith(base)) {
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
     * <p>
     * seekMillis 는 영상 시작점부터의 시크 위치(밀리초). 호출자(FfmpegFrameExtractor)가
     * outputFps 와 frameIndex 를 가지고 직접 계산하여 전달한다 — 구현체에서 단위를
     * 자체 변환하지 않도록 하여 단위 미스매치 버그를 구조적으로 차단한다.
     */
    public interface FrameWriter {
        boolean sourceExists(Path sourceVideo);
        void writeFrame(Path sourceVideo, Path outputFrame, long seekMillis) throws IOException;
    }
}
