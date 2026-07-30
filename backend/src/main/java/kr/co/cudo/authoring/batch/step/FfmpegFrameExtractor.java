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
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
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
    /**
     * 비식별 <b>입력 영상</b> 읽기 허용 base 판정의 단일 원천 (Phase 5A co-locate 정합).
     * 출력(프레임) base 는 여전히 {@link #baseDeidPath} 다 — 읽기 축과 쓰기 축을 섞지 않는다.
     */
    private final VideoArtifactRootResolver artifactRootResolver;

    public FfmpegFrameExtractor(LsDataSrcRepository srcRepository,
                                LsDataSrcHstryRepository hstryRepository,
                                LsDeidentProcLogRepository deidentProcLogRepository,
                                FrameWriter frameWriter,
                                VideoFpsResolver fpsResolver,
                                VideoArtifactRootResolver artifactRootResolver,
                                @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
                                @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String storageDeidPath) {
        this.srcRepository = srcRepository;
        this.hstryRepository = hstryRepository;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.frameWriter = frameWriter;
        this.fpsResolver = fpsResolver;
        this.artifactRootResolver = artifactRootResolver;
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
     *
     * <p><b>트랜잭션 경계는 여기에 있다</b>(DEV_FIX — self-invocation 트랜잭션 부재). 오케스트레이터가
     * 빈(프록시)의 {@code execute} 를 호출하므로 애노테이션이 발효되고, 아래 {@code this.extractByMarks(...)}
     * 는 자기호출이라 어드바이스가 걸리지 않아 본 트랜잭션에 참여한다(REQUIRES_NEW 중첩 없음 —
     * 스텝 1건 = 트랜잭션 1건). {@code extractByMarks} 를 프록시 경유로 바꾸면 중첩되므로 바꾸지 말 것.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
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
            Path deidPath = Paths.get(deidVideoPath).toAbsolutePath().normalize();
            if (!isUnderAllowedDeidBase(deidPath, raw)) {
                // MED-sec: 비식별 영상 경로가 <b>허용 base 전부</b>의 밖 — 외부 응답·DB 오염 등 신뢰불가 경로.
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

                    // 비식별 프레임 경로는 INSERT 시점에 함께 담는다(6-arg create). 신규 INSERT 직후 같은
                    // 트랜잭션에서 attachDeidPath setter dirty-update 로 채우던 옛 패턴은 컬럼이 DB 에
                    // 반영되지 않아 DE_IDNTF_SRC_FILE_PATH_NM 이 NULL 로 남고 export 가 PARTIAL 이 됐다.
                    // → 비식별 프레임을 create() 이전에 먼저 쓰고, 경로를 INSERT 에 포함한다.
                    String deidPath = null;
                    boolean deidAttached = false;
                    if (deidSource != null && deidOutputDir != null) {
                        Path deidFrame = deidOutputDir.resolve("frame-" + i + ".jpg");
                        frameWriter.writeFrame(deidSource, deidFrame, seekMillis);
                        deidPath = deidFrame.toString();
                        deidAttached = true;
                    }

                    LsDataSrc src = srcRepository.save(
                            LsDataSrc.create(raw.getRawSn(), i, (long) mark.frameIndex(),
                                    frameFile.toString(), deidPath, capturedAt));
                    hstryRepository.save(LsDataSrcHstry.recordCreated(src.getSrcSn()));
                    if (deidAttached) {
                        // 이력 의미 보존: 비식별을 채운 경우 CREATED + DEID_ATTACHED 2건을 남긴다.
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
     * 비식별 <b>입력 영상</b> 경로가 허용 base 중 <b>하나라도</b> 하위인지 판정한다 (CWE-22, fail-closed).
     *
     * <h3>왜 base 가 여러 개인가 (근본 원인 — 읽는 쪽 가드가 과하게 좁았다)</h3>
     * <p>구 구현은 {@code deidentified-path} <b>하나만</b> 기준으로 검증했다. 그런데 비식별 영상의 산출
     * 위치는 Phase 5A 부터 <b>co-locate</b>({@code dirname(원본)/{rawSn}/deid/}) 이고, 그 경로는 우리가
     * {@link VideoArtifactRootResolver#deidVideoDir} 로 <b>직접 지정</b>해 고정 allowlist
     * ({@code raw-mount-roots}) 검증까지 통과시킨 경로다. 원본이 관제 NAS(=raw base) 하위에 있는 정상
     * 형상에서 이 경로는 {@code deidentified-path} 밖이므로, 가드가 <b>자기가 지정한 산출물</b>을
     * 신뢰불가로 판정해 비식별 프레임 벌을 항상 건너뛰었다(로컬 실기동 실측:
     * {@code DE_IDNTF_SRC_FILE_PATH_NM} 전 행 NULL → {@code V_COMPLETED_FRAME.DEIDENTIFIED_PATH} NULL →
     * export 비식별 벌 결손). 즉 <b>산출 경로가 아니라 읽는 쪽 가드가 틀렸다</b>. 판정은
     * {@link VideoArtifactRootResolver#readableDeidVideoBases}(이미 {@code VideoStreamService} 가 쓰는
     * 동일 축)로 위임해 가드가 갈라지지 않게 한다.
     *
     * <h3>유지되는 방어</h3>
     * <ul>
     *   <li>{@code ..} 순회는 {@code normalize()} 로 접힌 뒤 어느 base 하위도 아니게 되어 거부된다.</li>
     *   <li>허용 루트 밖(외부·DB 오염) 경로는 여전히 거부 — <b>원본(비-비식별) 경로 폴백은 없다</b>
     *       (거부 시 비식별 입력 없이 RAW only 진행. {@code deIdntfYn='Y'} 행에 원본 PII 경로가 실릴
     *       여지를 만들지 않는다 — CWE-359).</li>
     *   <li>거부 로그에 경로 원문을 남기지 않는다(CWE-209).</li>
     *   <li>리졸버 미주입(단위 테스트 수동 생성)이면 구 동작({@code deidentified-path} 단독)으로
     *       판정한다 — 넓어지지 않는다(fail-closed).</li>
     * </ul>
     */
    private boolean isUnderAllowedDeidBase(Path deidPath, LsDataRaw raw) {
        if (artifactRootResolver == null) {
            return deidPath.startsWith(baseDeidPath);
        }
        List<Path> bases;
        try {
            bases = artifactRootResolver.readableDeidVideoBases(raw.getRawSn(), raw.getRawFilePathNm());
        } catch (RuntimeException e) {
            // 후보 도출 자체가 실패하면 구 동작으로 판정한다(fail-secure — 넓히지 않는다).
            return deidPath.startsWith(baseDeidPath);
        }
        for (Path base : bases) {
            if (deidPath.startsWith(base)) {
                return true;
            }
        }
        return false;
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
