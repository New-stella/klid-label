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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
 * <p>
 * <b>재실행 멱등 (@req R1)</b>: 이미 추출된 {@code (RAW_SN, FRM_NO)} 프레임은 <b>재추출하지 않고</b>
 * 기존 행을 결과에 그대로 싣는다. 자동 재시도 큐가 파이프라인을 선두부터 다시 돌리므로 이 판정이 없으면
 * UNIQUE 제약({@code UK_LS_DATA_SRC_RAW_FRAME})으로 하드 실패하고, 그 영상은 재시도 상한을 소진하며
 * 영구히 완주하지 못한다. 판정 단위가 스텝이 아니라 <b>프레임</b>인 이유는 일부만 추출된 상태에서
 * 나머지가 이어져야 하기 때문이다. 기존 행 3분기:
 * <ul>
 *   <li>위치 일치({@code VDO_FRM_NO} == 마킹 frameIndex) → <b>재사용</b></li>
 *   <li>위치 미검증({@code VDO_FRM_NO} NULL — 레거시 행) → <b>재사용 + WARN</b>, 단 <b>비식별 이미지는
 *       붙이지 않는다</b>. skip 하면 전 프레임이 NULL 인 영상이 "추출 결과 0건" 으로 <b>영구 실패</b>한다
 *       (dev 실측 115행 실재). {@code VDO_FRM_NO} 는 참값을 모르므로 NULL 로 남긴다(지어내지 않는다).</li>
 *   <li>위치 불일치({@code VDO_FRM_NO} non-null && 값 다름) → <b>skip(fail-closed)</b>. 마킹이 교체된
 *       신호라 재사용하면 이미지와 라벨 좌표가 어긋난다(dev 실측: 프레임 생성 14일 뒤 마킹 추가 실재).</li>
 * </ul>
 * <b>위치가 검증된 재사용</b>에서만, 기존 행의 비식별 경로가 비어 있고 지금은 비식별 소스가 보이면
 * <b>비식별 이미지를 쓰고 그 행을 dirty-update</b> 한다({@code SRC_SN} 보존) — 그러지 않으면 재시도는
 * 성공하는데 비식별 이미지가 영구 부재가 되어 export PARTIAL·{@code /deid-image} 404 로 이어진다.
 * 위치 미검증 행에까지 붙이면 <b>원본과 비식별이 서로 다른 순간</b>이 되고 제자리 덮어쓰기라 비가역이다.
 * <p>
 * ⚠ {@link DeidentFrameAttacher}(비식별 신고 해소 축)와는 <b>다른 축</b>이다 — 같은 dirty-update 패턴을
 * 쓰지만 그 클래스를 호출하지 않는다(어느 축이 그 행을 고쳤는지 구분되어야 한다).
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
                // MED-sec: 비식별 영상 경로가 <b>허용 base 전부</b>의 밖(또는 실경로가 base 밖을 가리키는
                // 심링크) — 외부 응답·DB 오염·NAS 링크 조작 등 신뢰불가 경로.
                // VideoStreamService.resolveSafe 와 대칭으로 fail-closed: 비식별 입력으로 쓰지 않고
                // 미존재처럼 RAW only 진행(원본 fallback 차단, 경로 원문 미노출).
                log.warn("[Batch][FrameExtract] deid path rejected (base/realpath) rawSn={} — RAW only",
                        raw.getRawSn());
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

        // ── 재실행 멱등 (@req R1) — 이미 추출된 프레임의 <b>기존 행</b>을 먼저 확보한다.
        //
        //  왜 필요한가: 자동 재시도 큐(BatchRetryQuartzJob → BatchOrchestrator.process)는 사람의 조작 없이
        //  파이프라인을 <b>선두부터 전부</b> 다시 돈다. 프레임추출이 한 번 성공한 영상이 뒷단계(YOLO/SAM2)
        //  에서 실패해 재시도되면, 이 루프가 LsDataSrc.create() 로 같은 (RAW_SN, FRM_NO) 를 다시 INSERT 해
        //  UNIQUE 제약(UK_LS_DATA_SRC_RAW_FRAME, V4)에 걸린다 — 우아한 skip 조차 아니라 <b>하드 실패</b>다.
        //  그러면 재시도가 재무장되어 매번 같은 지점에서 실패하고 그 영상은 영구히 완주하지 못한다.
        //
        //  판정 단위는 <b>프레임</b>이다(스텝 단위 아님) — 일부만 추출된 상태에서 나머지가 이어져야 한다.
        //  조회는 영상당 1회(N+1 금지)이며 폐기 프레임도 포함한다(UNIQUE 제약은 폐기여부와 무관).
        Map<Long, LsDataSrc> existingByFrameNo = new HashMap<>();
        for (LsDataSrc existing : srcRepository.findByRawSnOrderByFrameNoAsc(raw.getRawSn())) {
            existingByFrameNo.put(existing.getFrameNo(), existing);
        }

        List<LsDataSrc> saved = new ArrayList<>(marks.size());
        int reused = 0;
        int reusedUnverifiable = 0;
        int deidBackfilled = 0;
        int deidStillMissing = 0;
        int skippedMismatch = 0;
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

                    // ── 멱등 분기 (@req R1) — 이 (RAW_SN, FRM_NO) 는 이미 추출됐다.
                    LsDataSrc existing = existingByFrameNo.get((long) i);
                    if (existing != null) {
                        // ① 위치가 <b>실재하고 불일치</b> — 다른 마킹으로 뽑힌 프레임이라 재사용이 위험하다.
                        //    조용히 덮지 않고 fail-closed 로 건너뛴다(자세한 도달성 근거는
                        //    {@link #positionConflicts} javadoc).
                        if (positionConflicts(existing, mark.frameIndex())) {
                            log.warn("[Batch][FrameExtract] frame position mismatch — skipped (fail-closed) "
                                            + "rawSn={} frameNo={} — 마킹이 교체된 신호다. 점검 대상: "
                                            + "DeidentStageResumeService.resumeMarking 의 프레임 존재 가드 / "
                                            + "배치 진입 클레임(MARKING_READY→PROCESSING) 이 우회됐는가.",
                                    raw.getRawSn(), i);
                            skippedMismatch++;
                            continue;
                        }
                        // ② 위치를 <b>검증할 수 없다</b>(VDO_FRM_NO NULL — 레거시 행) → <b>그대로 재사용</b>.
                        //
                        //  ★ 구 동작(skip) 폐기 — 되돌리지 말 것. skip 하면 <b>전 프레임이 NULL 인 영상</b>은
                        //  saved 가 빈 리스트가 되어 execute 의 "추출 결과 0건" INTERNAL_ERROR 로 떨어지고,
                        //  재시도가 재무장돼 <b>매 회차 같은 지점에서 실패</b>한다(영구 미완주). dev 실측으로
                        //  그런 행이 실재한다: 632프레임 중 115건이 NULL 이고 rawSn 1~10 은 전 프레임 NULL 이다.
                        //
                        //  ★ 재추출로 갱신하지 않는다 — 그 행에 이미 라벨이 붙어 있으면 이미지를 다른 프레임으로
                        //  바꾸는 순간 <b>라벨 좌표가 무의미</b>해진다(영구 실패보다 나쁜 조용한 손상).
                        //  재사용은 위치를 <b>추측하는 것이 아니라 이미 존재하는 산출물을 그대로 쓰는 것</b>이라
                        //  "순번 폴백 금지" 경계와 성질이 다르다. VDO_FRM_NO 는 참값을 모르므로 <b>NULL 로
                        //  남긴다</b>(지어내지 않는다 · 백필도 하지 않는다).
                        //
                        //  ★★ 그리고 <b>비식별 이미지도 붙이지 않는다</b>(@req R1 · 3라운드 정정). 붙이려면
                        //  비식별 영상에서 <b>어느 위치를 뽑을지</b> 정해야 하는데 그 위치는 이번 실행의
                        //  <b>최신 마킹</b>에서 계산된다({@code MarkingLoadStep} 은
                        //  {@code findByRawSnOrderByRegDtDescMarkingSnDesc} 로 최신 1건을 쓴다). 그런데 재사용하는
                        //  원본은 <b>다른(옛) 마킹</b>의 산물일 수 있다 — dev 실측: rawSn 1 은 프레임 21건이
                        //  {@code marking_sn=1}(수동)으로 생성된 뒤 14일 지나 {@code marking_sn=15}(간격 60)가
                        //  추가됐다. 그 위치로 비식별 프레임을 뽑아 붙이면 <b>원본과 비식별이 서로 다른 순간</b>이
                        //  되고, 출력 경로가 {@code frames/deid/{rawSn}/frame-{i}.jpg} 제자리라 <b>비가역</b>이다.
                        //  게다가 그 레거시 프레임에는 라벨이 붙어 있다(실측 115프레임에 1,680건).
                        //
                        //  이는 CLAUDE.md 구속 정책과 같은 축이다 — 재비식별 재추출도 "{@code VDO_FRM_NO} NULL 은
                        //  순번 폴백 없이 skip + WARN(폴백하면 결함을 그대로 유지)" 으로 규정돼 있다. 컬럼에는
                        //  "참값을 모르니 안 쓴다"면서 파일에는 같은 추측을 쓰는 자기모순을 만들지 않는다.
                        //
                        //  ⚠ 잃는 것(명시): 레거시 프레임(실측 115건)의 {@code DE_IDNTF_SRC_FILE_PATH_NM} 은
                        //  NULL 로 남아 그 프레임에서 export 가 PARTIAL 이다. <b>이 변경 이전과 동일한 상태이므로
                        //  회귀가 아니다</b>. 레거시 복구는 {@code VDO_FRM_NO} 참값을 아는 별도 경로가 필요한
                        //  <b>별건</b>이다(여기서 추측으로 때우지 않는다).
                        boolean positionVerified = !positionUnverifiable(existing);
                        if (!positionVerified) {
                            log.warn("[Batch][FrameExtract] frame position unverifiable (VDO_FRM_NO null) "
                                            + "— 기존 산출물을 그대로 재사용하고, 위치를 검증할 수 없어 비식별 "
                                            + "이미지를 붙이지 않는다 rawSn={} frameNo={}",
                                    raw.getRawSn(), i);
                            reusedUnverifiable++;
                        }

                        // ── 비식별 경로 백필 (@req R1) — <b>위치가 검증된 재사용에만</b> 적용한다(위 ② 근거).
                        //  1회차에 비식별 영상이 아직 보이지 않아 RAW only 로 빠진 프레임은
                        //  DE_IDNTF_SRC_FILE_PATH_NM 이 null 로 남는다. 재사용만 하고 넘어가면 재시도는
                        //  <b>성공하는데</b> 그 프레임의 비식별 이미지는 영구 부재가 되어
                        //  export PARTIAL · /deid-image 404 · V_COMPLETED_FRAME.DEIDENTIFIED_PATH NULL 로
                        //  이어진다(복구 경로 0 — DeidentFrameAttacher 는 비식별 신고 해소 축에서만 돈다).
                        //  이 저장소에 같은 계열의 과거 사고 기록이 있어 반복을 막는다.
                        //
                        //  ⚠ SRC_SN 을 보존해야 하므로 <b>새 행을 만들지 않고 dirty-update</b> 한다(관리 엔티티라
                        //  커밋 시 flush 된다). {@code srcRepository.save} 를 호출하지 않는 이유는 이 경로가
                        //  INSERT 가 아님을 호출 흔적으로도 분명히 하기 위함이다(DeidentFrameAttacher 의
                        //  refreshExisting 과 같은 패턴이지만 <b>그 클래스를 호출하지는 않는다</b> — 신고 해소
                        //  축과 섞이면 어느 축이 그 행을 고쳤는지 구분되지 않는다).
                        if (positionVerified && isBlank(existing.getDeIdntfSrcFilePathNm())) {
                            if (deidSource != null && deidOutputDir != null) {
                                Path deidFrame = deidOutputDir.resolve("frame-" + i + ".jpg");
                                frameWriter.writeFrame(deidSource, deidFrame, seekMillis);
                                existing.attachDeidPath(deidFrame.toString());
                                hstryRepository.save(LsDataSrcHstry.recordDeidAttached(existing.getSrcSn()));
                                deidBackfilled++;
                                log.info("[Batch][FrameExtract] deid path backfilled on reused frame rawSn={} frameNo={}",
                                        raw.getRawSn(), i);
                            } else {
                                // 비식별 소스가 여전히 안 보인다 — 조용히 넘어가되 사실은 남긴다(무증상 금지).
                                log.warn("[Batch][FrameExtract] reused frame still has no deid image "
                                                + "(deid source unavailable) rawSn={} frameNo={}",
                                        raw.getRawSn(), i);
                                deidStillMissing++;
                            }
                        }

                        // 재추출·재INSERT 를 하지 않는다. SRC_SN 이 보존되므로 이 프레임에 달린 라벨의
                        // FK 가 끊기지 않는다. 매니페스트 항목은 계속 채워 파일이 반쪽이 되지 않게 한다.
                        mw.writeKeyFrame(i, seekMillis, checksumOf(frameFile));
                        saved.add(existing);
                        reused++;
                        continue;
                    }

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
        // reused>0 이면 이번 회차는 그 프레임에 대해 실질 작업 없이 통과했다는 뜻이다(재시도 관측 지표).
        //  reusedUnverifiable = 위치 미검증(레거시 VDO_FRM_NO NULL)으로 그대로 재사용한 건수,
        //  deidBackfilled/deidStillMissing = 재사용 프레임의 비식별 경로 보정 결과(무증상 결손 관측).
        log.info("[Batch][FrameExtract] mark-based extracted rawSn={} frames={} newlyExtracted={} reused={} "
                        + "reusedUnverifiable={} deidBackfilled={} deidStillMissing={} skippedMismatch={}",
                raw.getRawSn(), saved.size(), saved.size() - reused, reused,
                reusedUnverifiable, deidBackfilled, deidStillMissing, skippedMismatch);
        return saved;
    }

    /**
     * 기존 프레임 행의 영상 내 위치가 <b>실재하는데 이번 실행이 뽑을 위치와 다른가</b> (@req R1).
     *
     * <p>{@code FRM_NO}(추출 순번)는 같고 {@code VDO_FRM_NO}(영상 내 실제 프레임 위치)가 다르면 그 행은
     * <b>다른 마킹으로 뽑힌 프레임</b>이므로 재사용할 수 없다(그 위에 라벨이 붙어 있다면 이미지와 좌표가
     * 어긋난다). 이 경우만 skip 한다 — 위치를 <b>알 수 없는</b> 경우({@link #positionUnverifiable})와
     * 반드시 구분한다.
     *
     * <h3>도달성 — <b>도달 가능</b>하다 (dev 실측, 2026-08-12)</h3>
     * <p>⚠⚠ <b>구 서술("도달 불가") 폐기 — 되살리지 말 것.</b> 구 근거는 "마킹 생성이
     * {@code MARKING_READY} 를 요구하므로 프레임이 있는 영상에는 새 마킹이 생길 수 없다" 였는데,
     * {@code MarkingGuards.requirePreconditions} 는 <b>배치 단계만 확인하고 프레임 존재는 보지 않는다</b>.
     * 실측: {@code rawSn} 1 은 프레임 21건이 {@code marking_sn=1}(수동)으로 생성된 뒤 <b>14일 지나</b>
     * {@code marking_sn=15}(간격 60)가 추가됐다({@code rawSn} 11·17 도 동일 형상). 즉 "프레임 존재 후
     * 마킹 교체" 는 실재한다.
     *
     * <p>프레임 존재를 확인하는 가드는 {@code DeidentStageResumeService.resumeMarking}
     * <b>한 경로에만</b> 있다(그 경로는 되감기 직전 {@code countByRawSn>0} 이면 CONFLICT). 일반 마킹 생성
     * 경로에는 그 가드가 없다.
     *
     * <p><b>그래서 이 분기의 skip 은 실제 방어다</b>(이론적 안전망이 아니다) — 위치가 어긋난 기존 행을
     * 재사용하면 이미지와 라벨 좌표가 어긋나고, 비식별 이미지를 새 위치로 덮어쓰면 비가역이다.
     *
     * <p>⚠ 남은 선존 조건(별건): 이 분기가 <b>전 프레임</b>에 걸리면 결과 0건 → INTERNAL_ERROR → 영구
     * 실패다. 다만 이는 이 변경 <b>이전에도 동일</b>했고(구 동작은 같은 상황에서 UNIQUE 위반으로 실패),
     * 어떻게 처리할지(재마킹 차단 / 프레임 재생성 / 사람 개입)는 정책 판단이라 여기서 정하지 않는다.
     *
     * <p>⚠ {@code VDO_FRM_NO} <b>NULL</b> 은 이 분기가 아니다({@link #positionUnverifiable}) — NULL 은
     * 실재하는 레거시 데이터이며(dev 실측 632프레임 중 115건, {@code rawSn} 1~10 전 프레임) 그 행들을
     * 이 분기로 넣어 skip 하면 해당 영상이 재진입마다 영구 실패한다.
     */
    private static boolean positionConflicts(LsDataSrc existing, int markFrameIndex) {
        Long videoFrameNo = existing.getVideoFrameNo();
        return videoFrameNo != null && videoFrameNo != (long) markFrameIndex;
    }

    /**
     * 기존 프레임 행의 영상 내 위치를 <b>검증할 수 없는가</b>({@code VDO_FRM_NO} NULL — 레거시 행, @req R1).
     *
     * <p>검증 불가는 <b>불일치가 아니다</b>. 호출부는 이 경우 WARN 만 남기고 기존 산출물을 그대로 재사용한다
     * (재추출로 갱신하면 라벨 좌표가 무의미해지고, skip 하면 그 영상이 영구 실패한다).
     *
     * <p>★ <b>이 행에는 비식별 이미지를 붙이지 않는다</b>(@req R1) — 붙일 위치는 이번 실행의 최신 마킹에서
     * 계산되는데 재사용하는 원본은 옛 마킹의 산물일 수 있어(dev 실측 확인) 원본과 비식별이 서로 다른 순간이
     * 되고, 출력이 제자리 덮어쓰기라 비가역이다. CLAUDE.md 의 "{@code VDO_FRM_NO} NULL 은 순번 폴백 없이
     * skip" 구속 정책과 같은 축이다. 상세 근거는 호출부 주석(②) 참조.
     */
    private static boolean positionUnverifiable(LsDataSrc existing) {
        return existing.getVideoFrameNo() == null;
    }

    /** null/blank 판정 — 비식별 경로 백필 대상 여부. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
     *   <li><b>실경로(심링크) 재검증</b> — {@link #underBaseWithRealPath} 참조.</li>
     *   <li>거부 로그에 경로 원문을 남기지 않는다(CWE-209).</li>
     *   <li>리졸버 미주입(단위 테스트 수동 생성)이면 구 동작({@code deidentified-path} 단독)으로
     *       판정한다 — 넓어지지 않는다(fail-closed).</li>
     * </ul>
     */
    private boolean isUnderAllowedDeidBase(Path deidPath, LsDataRaw raw) {
        if (artifactRootResolver == null) {
            return underBaseWithRealPath(deidPath, baseDeidPath);
        }
        List<Path> bases;
        try {
            bases = artifactRootResolver.readableDeidVideoBases(raw.getRawSn(), raw.getRawFilePathNm());
        } catch (RuntimeException e) {
            // 후보 도출 자체가 실패하면 구 동작으로 판정한다(fail-secure — 넓히지 않는다).
            return underBaseWithRealPath(deidPath, baseDeidPath);
        }
        for (Path base : bases) {
            if (underBaseWithRealPath(deidPath, base)) {
                return true;
            }
        }
        return false;
    }

    /**
     * base 1건에 대한 허용 판정 — lexical({@code startsWith}) <b>+ 실경로 재검증</b>(CWE-59).
     *
     * <h3>왜 lexical 만으로는 부족한가</h3>
     * <p>허용 집합에 co-locate 경로({@code dirname(원본)/{rawSn}/deid})가 들어오면서, 판정 대상이
     * <b>외부 비식별 벤더(KPST)가 공유 마운트로 직접 산출물을 쓰는 디렉터리</b>로 넓어졌다. 즉 신뢰
     * 경계가 "우리만 쓰는 저장소" → "벤더가 쓰는 NAS 디렉터리" 로 확장됐으므로, 그 디렉터리 안의
     * <b>대상 파일 자체</b>가 심링크로 원본(PII) 영상을 가리키는 경우를 막아야 한다. lexical 검사만
     * 통과시키면 원본에서 추출한 프레임이 {@code DE_IDNTF_SRC_FILE_PATH_NM} 에 "비식별본" 으로
     * 적재되어 데이터마트 뷰·export {@code deid/} 벌·포털 프레임 서빙으로 새어 나간다(CWE-359).
     * 신고 게이트도 이 경로를 {@code 'Y'} 로 보기 때문에 뒤에서 막아주지 않는다.
     *
     * <h3>판정 로직을 복제하지 않는다</h3>
     * <p>실경로 판정은 {@link VideoArtifactRootResolver#verifyRealPathUnder}(리졸버가 base 축에
     * 쓰는 <b>같은</b> 정적 메서드)를 그대로 호출한다 — 이 결함군의 뿌리가 "가드가 여러 벌로 갈라져
     * 하나씩 샌다" 였으므로 여기서 {@code toRealPath} 비교를 다시 구현하지 않는다.
     *
     * <p>검증 실패(실경로가 base 밖 / 해석 불가·권한 오류)는 모두 <b>거부</b>다(fail-closed).
     * 거부는 예외가 아니라 {@code false} 이므로 호출부의 기존 "RAW only" 분기로 흡수된다 —
     * 파이프라인을 실패시키지 않고, 원본 폴백도 만들지 않는다.
     */
    private static boolean underBaseWithRealPath(Path deidPath, Path base) {
        if (!deidPath.startsWith(base)) {
            return false;
        }
        try {
            VideoArtifactRootResolver.verifyRealPathUnder(deidPath, base);
            return true;
        } catch (RuntimeException e) {
            // 실경로가 base 밖(심링크 우회) 또는 해석 실패 — 이 base 로는 허용하지 않는다.
            return false;
        }
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

    /**
     * 프레임 파일의 SHA-256 — 매니페스트 기록용.
     *
     * <p>실패 시 {@code "unknown"} 을 기록하고 <b>WARN 을 남긴다</b>. 무음 폴백이면 매니페스트에
     * {@code "unknown"} 이 조용히 쌓여 무결성 검증이 의미를 잃는다(재사용 분기에서 특히 그렇다 — 그쪽은
     * 파일을 방금 쓰지 않았으므로 부재가 현실적이다). 두 경로 모두 같은 신호를 남기도록 여기 한 곳에 둔다.
     *
     * <p>⚠ <b>재추출로 복구하지 않는다</b> — 파일이 실제로 없으면 하류 YOLO 가 그 프레임을 읽다 큰 실패를
     * 내는 것이 옳다(여기서 다시 뽑으면 라벨이 붙은 프레임의 이미지를 조용히 바꿀 수 있다).
     * 경로 원문은 로그에 싣지 않는다(CWE-209).
     */
    private String checksumOf(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("[Batch][FrameExtract] frame checksum unavailable — manifest records \"unknown\" reason={}",
                    e.getClass().getSimpleName());
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
