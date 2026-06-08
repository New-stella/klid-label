package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.ManifestJsonlWriter;
import kr.co.cudo.authoring.marking.dto.MarkItem;
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
import java.util.Optional;

/**
 * FFmpeg 프레임 추출기 (V2.1 — FRAME_EXTRACT 단계, 비식별 분리 반영).
 * <p>
 * 마킹 위치 기반 프레임 추출만 지원한다. marks 의 frameIndex 에 해당하는
 * 프레임만 추출한다 (원본 + 비식별 2벌).
 * <p>
 * Phase 1 (파이프라인 재정렬): 비식별 영상 경로는 더 이상 호출자(orchestrator)가 인자로
 * 전달하지 않는다. {@link LsDeidentProcLogRepository#findLatestSuccessByDataRawSn} 로
 * 적재 직후 선두 단계에서 저장된 최신 성공 비식별 로그를 스스로 조회하여
 * {@link LsDeidentProcLog#getDeIdntfFilePathNm()} 을 비식별 영상 경로로 사용한다.
 * <p>
 * 비식별은 프레임추출의 필수 선행단계다 — {@code raw.deIdntfYn != "Y"} 면 INVALID_INPUT 으로 차단.
 * (실제 운영에서는 net.bramp.ffmpeg 가 ffmpeg 바이너리를 호출. 본 구현은 바이너리 의존을 격리한 추상화.)
 * <p>
 * 보안:
 * - Path Manipulation (CWE-22): 출력 경로는 storage.raw-path 기반 + Path.normalize + base 검증.
 * - Privacy: 영상 경로는 hash 로 마스킹 후 로그 출력.
 */
@Slf4j
@Component
public class FfmpegFrameExtractor implements BatchStep {

    /** 영상 네이티브 프레임레이트 가정값. MarkItem.frameIndex 는 이 fps 기준. */
    static final int NATIVE_VIDEO_FPS = 30;

    /** 비식별 완료 마커 코드 (LsDataRaw.deIdntfYn). */
    private static final String DEIDENTIFIED = "Y";

    private final LsDataSrcRepository srcRepository;
    private final LsDataSrcHstryRepository hstryRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final FrameWriter frameWriter;
    private final Path baseRawPath;
    /** Phase 2: 비식별 프레임 출력 base 경로 (영상 2벌 보관 정책). */
    private final Path baseDeidPath;

    public FfmpegFrameExtractor(LsDataSrcRepository srcRepository,
                                LsDataSrcHstryRepository hstryRepository,
                                LsDeidentProcLogRepository deidentProcLogRepository,
                                FrameWriter frameWriter,
                                @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
                                @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String storageDeidPath) {
        this.srcRepository = srcRepository;
        this.hstryRepository = hstryRepository;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.frameWriter = frameWriter;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.baseDeidPath = Paths.get(storageDeidPath).toAbsolutePath().normalize();
    }

    @Override
    public BatchStage stage() {
        return BatchStage.FRAME_EXTRACT;
    }

    /** Phase 3 — 조건부 step: ctx 토글이 FRAME_EXTRACT off 면 단계 skip (dev 경로 전용, 프로덕션은 항상 on). */
    @Override
    public boolean isEnabled(BatchContext ctx) {
        return ctx.isStageEnabled(stage());
    }

    /**
     * 파이프라인 진입점 — ctx.marks 로 마킹 위치 기반 프레임을 추출한다.
     *
     * <p>동작 보존: 기존 orchestrator 가 FRAME_EXTRACT 단계 진입 직전에 수행하던 가드를
     * 본 메서드로 이동했다.
     * <ul>
     *   <li>marks 가 비어있으면(마킹 없음 포함) {@code INVALID_INPUT} — 기존 orchestrator 의
     *       "마킹 데이터가 없습니다" 가드와 동일 ErrorCode/단계.</li>
     *   <li>추출 결과 0건이면 {@code INTERNAL_ERROR} — 기존 orchestrator 의 "프레임 추출 결과가
     *       0건입니다" 가드와 동일 ErrorCode/단계.</li>
     * </ul>
     */
    @Override
    public void execute(BatchContext ctx) {
        Long rawSn = ctx.getRawSn();
        List<MarkItem> marks = ctx.getMarks();
        if (marks.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "마킹 데이터가 없습니다. rawSn=" + rawSn);
        }
        List<LsDataSrc> frames = extractByMarks(ctx.getRaw(), marks);
        if (frames.isEmpty()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "프레임 추출 결과가 0건입니다 rawSn=" + rawSn);
        }
    }

    /**
     * 마킹 위치 기반 프레임 추출.
     * marks 의 frameIndex 에 해당하는 프레임만 추출한다 (원본 + 비식별 2벌).
     * marks 가 비어있으면 INVALID_INPUT. 비식별 미완료 영상이면 INVALID_INPUT.
     * 비식별 영상 경로는 저장된 최신 성공 비식별 로그에서 조회한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public List<LsDataSrc> extractByMarks(LsDataRaw raw, List<MarkItem> marks) {
        if (raw == null || raw.getRawFilePathNm() == null || raw.getRawFilePathNm().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 메타가 비어있습니다.");
        }
        // 입력 검증 우선: marks 가드를 비식별 가드보다 먼저 평가한다.
        if (marks == null || marks.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "마킹 데이터가 비어있습니다.");
        }
        // 비식별 선행 보장 가드 — 신 시나리오에서 비식별은 프레임추출의 필수 선행단계.
        if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "비식별이 완료되지 않은 영상입니다 rawSn=" + raw.getRawSn());
        }
        Path source = Paths.get(raw.getRawFilePathNm());
        if (!frameWriter.sourceExists(source)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "원본 영상을 찾을 수 없습니다: rawSn=" + raw.getRawSn());
        }
        Path rawOutputDir = resolveSafeOutputDir(baseRawPath, raw.getRawSn());

        // 저장된 최신 성공 비식별 로그에서 비식별 영상 경로를 조회 (self-lookup).
        String deidVideoPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(raw.getRawSn())
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);

        Path deidSource = null;
        Path deidOutputDir = null;
        if (deidVideoPath != null) {
            Path deidPath = Paths.get(deidVideoPath);
            if (frameWriter.sourceExists(deidPath)) {
                deidSource = deidPath;
                deidOutputDir = resolveSafeOutputDir(baseDeidPath, raw.getRawSn());
            } else {
                // deIdntfYn="Y" 인데 비식별 파일이 부재 — 비정상 상황. RAW only 로 graceful 진행.
                log.warn("[Batch][FrameExtract] deid video missing rawSn={} path={} — RAW only",
                        raw.getRawSn(), maskName(deidVideoPath));
            }
        } else {
            // deIdntfYn="Y" 인데 비식별 경로가 없음 — 비정상 상황. RAW only 로 graceful 진행.
            log.warn("[Batch][FrameExtract] deid path not found in proc log rawSn={} — RAW only",
                    raw.getRawSn());
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

                    LocalDateTime capturedAt = raw.getShtDt() == null
                            ? null : raw.getShtDt().plus(Duration.ofMillis(seekMillis));
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
