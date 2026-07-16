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
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
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

    /** 비식별 완료 마커 코드 (LsDataRaw.deIdntfYn). */
    private static final String DEIDENTIFIED = "Y";

    private final LsDataSrcRepository srcRepository;
    private final LsDataSrcHstryRepository hstryRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final FrameWriter frameWriter;
    /**
     * 단일 fps 소스 — MarkItem.frameIndex → seekMillis 변환에 실 fps(video.fps)를 사용한다(M-3 수정).
     * 마킹 단계(MarkingService)와 동일 resolveFps 경로라 frameIndex↔seekMillis 가 정합한다.
     */
    private final VideoFpsResolver fpsResolver;
    private final Path baseRawPath;
    /** Phase 2: 비식별 프레임 출력 base 경로 (영상 2벌 보관 정책). */
    private final Path baseDeidPath;

    public FfmpegFrameExtractor(LsDataSrcRepository srcRepository,
                                LsDataSrcHstryRepository hstryRepository,
                                LsDeidentProcLogRepository deidentProcLogRepository,
                                FrameWriter frameWriter,
                                VideoFpsResolver fpsResolver,
                                @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
                                @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String storageDeidPath) {
        this.srcRepository = srcRepository;
        this.hstryRepository = hstryRepository;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.frameWriter = frameWriter;
        this.fpsResolver = fpsResolver;
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
        // TOCTOU 제거 — 마킹이 pin 한 fps 를 재조회 없이 사용한다. marks 는 최신 마킹(markings.get(0))의
        // markCn 에서 파싱되므로(MarkingLoadStep), 동일 마킹의 fps 를 pin 값으로 넘긴다. ctx.markings 는
        // MARKING 단계가 이미 로드했으므로 여기서 추가 조회가 없다(N+1 없음). pin 이 null(기존 데이터)이면
        // extractByMarks 가 resolveFps 로 폴백한다(하위호환).
        List<LsMarking> markings = ctx.getMarkings();
        Double pinnedFps = markings.isEmpty() ? null : markings.get(0).getFps();
        List<LsDataSrc> frames = extractByMarks(ctx.getRaw(), marks, pinnedFps);
        if (frames.isEmpty()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "프레임 추출 결과가 0건입니다 rawSn=" + rawSn);
        }
    }

    /**
     * 마킹 위치 기반 프레임 추출 (fps pin 미지정 — resolveFps 폴백).
     * marks 의 frameIndex 에 해당하는 프레임만 추출한다 (원본 + 비식별 2벌).
     * marks 가 비어있으면 INVALID_INPUT. 비식별 미완료 영상이면 INVALID_INPUT.
     * 비식별 영상 경로는 저장된 최신 성공 비식별 로그에서 조회한다.
     *
     * <p>이 2-인자 진입점은 <b>하위호환</b>용이다 — 마킹의 pin 된 fps 가 없을 때(구 데이터/테스트) 호출되며
     * {@code fpsResolver.resolveFps} 로 fps 를 조회한다. 프로덕션 파이프라인({@link #execute})은
     * 마킹 pin 을 넘기는 3-인자 오버로드를 사용한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public List<LsDataSrc> extractByMarks(LsDataRaw raw, List<MarkItem> marks) {
        return extractByMarks(raw, marks, null);
    }

    /**
     * 마킹 위치 기반 프레임 추출 (fps pin 지원 — TOCTOU 제거).
     *
     * <p>{@code pinnedFps} 는 마킹 시점에 확정된 실 프레임레이트다. 유효(non-null·유한·&gt;0)하면
     * {@code resolveFps} 재조회 없이 그대로 사용하여, 마킹이 frameIndex 를 산출할 때 쓴 fps 와 추출이
     * seekMillis 를 계산할 때 쓰는 fps 를 <b>동일 레코드의 동일 값</b>으로 일치시킨다(정합성 불변식).
     * {@code null}/비정상이면 (구 데이터·fail-safe) {@code resolveFps} 폴백한다.
     *
     * @param pinnedFps 마킹 pin fps (nullable — null 이면 resolveFps 폴백)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public List<LsDataSrc> extractByMarks(LsDataRaw raw, List<MarkItem> marks, Double pinnedFps) {
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
        Path rawOutputDir = resolveSafeOutputDir(baseRawPath, raw.getRawSn(), FrameKind.RAW);

        // 저장된 최신 성공 비식별 로그에서 비식별 영상 경로를 조회 (self-lookup).
        String deidVideoPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(raw.getRawSn())
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);

        Path deidSource = null;
        Path deidOutputDir = null;
        if (deidVideoPath != null) {
            Path deidPath = Paths.get(deidVideoPath).normalize();
            if (!deidPath.startsWith(baseDeidPath)) {
                // MED-sec: 비식별 영상 경로가 비식별 base 밖 — 외부 응답·DB 오염 등 신뢰불가 경로.
                // VideoStreamService.resolveSafe 와 대칭으로 fail-closed: 비식별 입력으로 쓰지 않고
                // 미존재처럼 RAW only 진행(원본 fallback 차단, 경로 원문 미노출).
                log.warn("[Batch][FrameExtract] deid path outside base rawSn={} — RAW only", raw.getRawSn());
            } else if (frameWriter.sourceExists(deidPath)) {
                deidSource = deidPath;
                deidOutputDir = resolveSafeOutputDir(baseDeidPath, raw.getRawSn(), FrameKind.DEID);
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

        // TOCTOU 제거 — frameIndex → seekMillis 변환에 마킹이 pin 한 fps 를 최우선 사용한다. 마킹과
        // 동일 레코드의 동일 값이므로, 마킹이 frameIndex 를 산출할 때 쓴 fps 와 구조적으로 정확히 일치한다.
        // pin 이 없으면(구 데이터·null) resolveFps 폴백 → 그래도 미상 시 30.0 이라 기존 결과와 동일(무회귀).
        //
        // 무회귀 주의: seekMillis 는 Math.round(frameIndex*1000.0/fps) 로 계산한다. 구 정수절삭
        // (frameIndex*1000/30) 대비 ≤1ms 차이가 날 수 있으나, ffmpeg 는 seek 위치에서 최근접 프레임으로
        // snap 하므로 실제 선택되는 프레임은 불변이다(정확도 개선 — 구 절삭으로 되돌리지 않는다).
        double fps = effectiveFps(pinnedFps, raw.getRawSn());

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
                    long seekMillis = Math.round(mark.frameIndex() * 1000.0 / fps);
                    frameWriter.writeFrame(source, frameFile, seekMillis);
                    String checksum = checksumOf(frameFile);
                    mw.writeKeyFrame(i, seekMillis, checksum);

                    LocalDateTime capturedAt = raw.getShtDt() == null
                            ? null : raw.getShtDt().plus(Duration.ofMillis(seekMillis));
                    LsDataSrc src = srcRepository.save(
                            LsDataSrc.create(raw.getRawSn(), i, (long) mark.frameIndex(), frameFile.toString(), capturedAt));
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
     * 증강 영상 프레임 <b>번호 기반</b> 재추출 (Phase 11 — 증강본 프레임 복사→재추출 전환).
     *
     * <p><b>배경</b>: 증강 영상(WINTER/NIGHT/RAIN)은 원본과 픽셀이 달라 부모 프레임 이미지를 그대로
     * 복사하면 오손(원본 픽셀이 증강본 라벨에 붙음)이다. 따라서 부모 프레임의 디코더 프레임 번호
     * ({@code videoFrameNo}) 목록을 받아 <b>증강 영상 파일에서 동일 번호 프레임을 새로 추출</b>한다.
     * 라벨 좌표는 해상도가 동일하므로 그대로 복사한다(호출자 책임).
     *
     * <p><b>비식별 게이트 재사용 금지 (Phase 11 #2 — 순환 의존 차단)</b>: {@link #extractByMarks} 의
     * {@code deIdntfYn=='Y'} 선행 가드를 여기서 <b>쓰지 않는다</b>. 증강 신규 RAW 는 이 재추출이
     * <b>성공한 뒤에야</b> 'Y' 로 확정되므로(호출자 AugmentFrameExtractionService 가 성공 시 세팅),
     * 여기서 'Y' 를 요구하면 "추출하려면 'Y' 필요 ↔ 'Y' 되려면 추출 성공 필요"의 순환이 된다.
     * 대신 <b>소스 파일 존재만</b> 확인한다. 증강본은 RAW=DEID 동일 취급이라 원본 1벌만 추출한다
     * (비식별 base 폴백 없음).
     *
     * <p><b>all-or-nothing</b>: 한 프레임이라도 IOException 이면 전체 실패({@code INTERNAL_ERROR})다.
     * 호출자는 이 메서드를 자신의 트랜잭션(REQUIRES_NEW)에 참여(REQUIRED)시켜, 실패 시 저장된 프레임이
     * 전부 롤백되어 고아 프레임/라벨이 남지 않게 한다.
     *
     * @param frameNumbers 부모 프레임의 디코더 프레임 번호 목록(=videoFrameNo). 신규 프레임의
     *                     {@code videoFrameNo} 에 그대로 실어 호출자가 videoFrameNo 기준으로 라벨을
     *                     재매핑할 수 있게 한다. FRM_NO 는 추출 순번(0-base loop index)이다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRED)
    public List<LsDataSrc> extractByFrameNumbers(LsDataRaw raw, List<Long> frameNumbers) {
        if (raw == null || raw.getRawFilePathNm() == null || raw.getRawFilePathNm().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "증강 영상 메타가 비어있습니다.");
        }
        if (frameNumbers == null || frameNumbers.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "재추출할 프레임 번호가 없습니다.");
        }
        // Phase 11 #2 — deIdntfYn 게이트 미적용(순환 의존 차단). 소스 파일 존재만 확인한다.
        Path source = Paths.get(raw.getRawFilePathNm());
        if (!frameWriter.sourceExists(source)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 영상을 찾을 수 없습니다: rawSn=" + raw.getRawSn());
        }
        Path outputDir = resolveSafeOutputDir(baseRawPath, raw.getRawSn(), FrameKind.RAW);

        List<LsDataSrc> saved = new ArrayList<>(frameNumbers.size());
        try {
            ensureDir(outputDir);
            for (int i = 0; i < frameNumbers.size(); i++) {
                long frameNo = frameNumbers.get(i);
                Path frameFile = outputDir.resolve("frame-" + i + ".jpg");
                // frame-exact 추출 — fps/seek 가정 없이 디코더 프레임 번호로 직접 1장 추출.
                frameWriter.writeFrameByNumber(source, frameFile, (int) frameNo);
                // videoFrameNo = 부모 프레임 번호(라벨 재매핑 key), FRM_NO = 추출 순번(i).
                LsDataSrc src = srcRepository.save(
                        LsDataSrc.create(raw.getRawSn(), i, frameNo, frameFile.toString(), raw.getShtDt()));
                hstryRepository.save(LsDataSrcHstry.recordCreated(src.getSrcSn()));
                saved.add(src);
            }
        } catch (IOException e) {
            // all-or-nothing — 호출자 REQUIRES_NEW 트랜잭션이 롤백되어 부분 저장된 프레임이 소멸한다.
            log.error("[Batch][FrameExtract] augment frame re-extraction failed rawSn={} err={}",
                    raw.getRawSn(), e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "증강 프레임 재추출 실패", e);
        }
        log.info("[Batch][FrameExtract] augment frames re-extracted rawSn={} frames={}",
                raw.getRawSn(), saved.size());
        return saved;
    }

    /**
     * base 하위에 영상별 디렉토리를 안전하게 생성.
     * - resolved 가 base 외부로 빠지면 거부 (Path Manipulation 방어, CWE-22).
     * - Phase 2: base 인자화 — RAW(baseRawPath) / DEID(baseDeidPath) 양쪽에서 사용.
     * - 스킴 A: {@code {base}/frames/{kind}/{rawSn}} — kind(raw/deid) 서브세그먼트로 분기하여
     *   두 base 가 동일 경로여도 원본/비식별 프레임 디렉토리가 충돌하지 않는다.
     *   {@code frames/raw}·{@code frames/deid} 모두 base 하위이므로 startsWith 가드를 통과한다.
     */
    private static Path resolveSafeOutputDir(Path base, Long rawSn, FrameKind kind) {
        Path resolved = base.resolve("frames").resolve(kind.getSegment())
                .resolve(String.valueOf(rawSn)).normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "프레임 출력 경로가 허용된 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /**
     * 추출에 쓸 실효 fps 결정 — 마킹이 pin 한 fps 를 최우선 사용(TOCTOU 제거).
     *
     * <p>{@code pinnedFps} 가 유효(non-null·유한·&gt;0)하면 재조회 없이 그대로 사용한다. null 또는
     * 비정상(NaN/Infinity/≤0)이면 (구 데이터 하위호환·fail-safe) {@code resolveFps} 로 폴백한다.
     * 폴백도 미상 시 {@code VideoFpsResolver.DEFAULT_FPS}(30.0)라 무회귀.
     */
    private double effectiveFps(Double pinnedFps, Long rawSn) {
        if (pinnedFps != null && Double.isFinite(pinnedFps) && pinnedFps > 0) {
            return pinnedFps;
        }
        return fpsResolver.resolveFps(rawSn);
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

        /**
         * 프레임 <b>번호</b>로 직접 추출(frame-exact). seek/fps 변환 없이 디코더 프레임 인덱스
         * {@code frameNo} 에 정확히 해당하는 프레임 1장을 추출한다.
         * <p>
         * 비식별 영상은 원본에 마스킹만 한 것이라 프레임 시퀀스가 동일하므로,
         * "원본 N번 프레임"과 "비식별 N번 프레임"은 같은 장면이다. fps 가정에 의존하지 않아
         * 출처별 frm_no 의미 차이로 인한 좌표 어긋남을 구조적으로 제거한다.
         *
         * @param frameNo 0-base 디코더 프레임 인덱스. 음수면 IOException.
         */
        void writeFrameByNumber(Path sourceVideo, Path outputFrame, int frameNo) throws IOException;
    }
}
