package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.aiserver.service.AiSrvrBatchAssignment;
import kr.co.cudo.authoring.common.client.AiWorkload;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SAM2 segment 단계 (Phase 5 — SAM2, Phase 2 — 토글 분기 + 인메모리 힌트 병합).
 * <p>
 * 입력 소스(라벨 단위):
 * <ol>
 *   <li>DB BBOX — YOLO 가 BBOX_ENABLED=true 인 라벨에 대해 LS_DATA_LBL 에 저장한 row.
 *       기존 BOTH 라벨 경로.</li>
 *   <li>upstreamHints — YOLO 가 POLYGON_ENABLED=true 인 라벨에 대해 인메모리로 전달.
 *       특히 BBOX_ENABLED=false, POLYGON_ENABLED=true 인 POLYGON_ONLY 라벨은 본 경로로만 들어옴.</li>
 * </ol>
 * <p>
 * 중복 제거 (Phase 4): 동일 {@code (srcSn, label, trackId)} 키로 dedup. DB BBOX 가 있으면 그 좌표를 우선 사용.
 * trackId 는 ai-server 가 부여한 객체 ID (Integer). 같은 라벨이라도 다른 trackId 면 별도 객체로
 * 간주하여 SAM2 호출을 분리한다. trackId 가 null 인 경우(legacy/저신뢰 fallback)는 라벨 단위 dedup 으로
 * 자연 흡수된다 (record equality 의 null 처리).
 * <p>양쪽에서 동일 (srcSn, label, trackId) 가 들어오면 SAM2 는 1회만 호출되고 POLYGON 도 1건만 저장된다.
 * <p>
 * 토글 방어:
 * <ul>
 *   <li>라벨별 {@code polygon=false} 면 SAM2 호출 skip + WARN 로그.
 *       (Phase 1 의 (false,false) 거부로 정상 흐름에서는 발생하지 않으나 방어 코드.)</li>
 *   <li>프리셋이 실효하지 않으면(보류·오토라벨 제외) <b>어떤 라벨도 통과시키지 않는다</b> —
 *       구 fail-open(전 라벨 BOTH 처리)은 폐기됐다. 정상 흐름에서는 앞선 탐지 단계가 이미
 *       보류·제외로 끝내므로 이 방어가 발동하지 않는다. [@design ADR-054] [@design AC-119]</li>
 * </ul>
 * <p>
 * NEW-H1 — mock 응답 fail-closed(YOLO 게이트의 형제 결함):
 * <ul>
 *   <li>배포 환경(stg/prd)에서 신뢰 불가 응답({@code untrusted()}) 또는 빈/결측 응답을 받으면
 *       <b>첫 감지 즉시</b> 스텝 전체를 실패시킨다(all-or-nothing).</li>
 *   <li>local/dev 에서도 신뢰 불가 응답의 <b>폴리곤을 저장하지 않고 스킵</b>한다(WARN 만).
 *       사유 면제는 없다 — 근거는 {@link #blocksUntrusted()} 참조.</li>
 * </ul>
 * <p>
 * <b>재실행 멱등 (@req R1)</b>: 이미 SAM2 폴리곤({@code LS_DATA_LBL_AI_INFO.LBL_SRC_CD='SAM2'})이 있는
 * 프레임은 추론·적재를 건너뛴다. 삭제 후 재삽입은 하지 않는다(사람이 이미 수정했을 수 있다).
 * <p>
 * 보안:
 *  - Path Manipulation (CWE-22): baseRawPath 기준 경로 범위 내로 제한.
 */
@Slf4j
@Component
public class Sam2SegmentStep implements BatchStep {

    /** AI_INFO.REG_ID 출처 마커 — 배치 파이프라인 자동 실행. */
    private static final String SOURCE_BATCH = "batch";

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final VideoRepository videoRepository;
    private final PresetLabelLookupService presetLabelLookup;
    private final LabelMasterService labelMasterService;
    private final ObjectMapper objectMapper;
    private final Path baseRawPath;
    /** NEW-H1 — 배포 환경(stg/prd) 여부. YOLO 스텝과 <b>동일한</b> 판정기를 재사용한다(복제 금지). */
    private final DeployedEnvironmentDetector deployedEnvironment;
    /**
     * <b>같은 영상은 같은 장비로</b> — 배치 경로의 영상 고정. [@design ADR-057]
     *
     * <p>YOLO 단계와 <b>같은 배정</b>을 읽는다(영상당 한 건이므로 같은 장비가 나온다). 두 단계가 서로
     * 다른 장비로 가면 SAM2 가 YOLO 의 추적 상태를 이어받지 못한다.
     */
    private final AiSrvrBatchAssignment batchAssignment;

    public Sam2SegmentStep(AiServerClient aiServerClient,
                           LsDataSrcRepository srcRepository,
                           LsDataLblRepository lblRepository,
                           VideoRepository videoRepository,
                           PresetLabelLookupService presetLabelLookup,
                           LabelMasterService labelMasterService,
                           ObjectMapper objectMapper,
                           @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
                           DeployedEnvironmentDetector deployedEnvironment,
                           AiSrvrBatchAssignment batchAssignment) {
        this.aiServerClient = aiServerClient;
        this.srcRepository = srcRepository;
        this.lblRepository = lblRepository;
        this.videoRepository = videoRepository;
        this.presetLabelLookup = presetLabelLookup;
        this.labelMasterService = labelMasterService;
        this.objectMapper = objectMapper;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.deployedEnvironment = deployedEnvironment;
        this.batchAssignment = batchAssignment;
    }

    @Override
    public BatchStage stage() {
        return BatchStage.SAM2;
    }

    /**
     * 파이프라인 진입점 — YOLO 단계가 적재한 ctx.hints 를 SAM2 호출에 전달한다.
     * 동작 보존: 기존 orchestrator 의 {@code sam2Step.run(rawSn, hints)} 와 동일.
     *
     * <p><b>트랜잭션 경계는 여기에 있다</b>(DEV_FIX — self-invocation 트랜잭션 부재). 오케스트레이터가
     * 빈(프록시)의 {@code execute} 를 호출하므로 애노테이션이 발효되고, 아래 {@code this.run(...)} 은
     * 자기호출이라 어드바이스가 걸리지 않아 본 트랜잭션에 참여한다(REQUIRES_NEW 중첩 없음 —
     * 스텝 1건 = 트랜잭션 1건). {@code run()} 을 프록시 경유로 바꾸면 중첩되므로 바꾸지 말 것.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void execute(BatchContext ctx) {
        run(ctx.getRawSn(), ctx.getHints());
    }

    /**
     * 단일 영상의 모든 프레임에 대해 SAM2 segment 호출 + POLYGON 라벨을 저장한다.
     *
     * @param rawSn          LS_DATA_RAW.RAW_SN
     * @param upstreamHints  YoloAutolabelStep 이 발행한 인메모리 BBOX 힌트 (POLYGON_ONLY 라벨 포함)
     * @return 저장된 POLYGON row 수
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn, List<BbHint> upstreamHints) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        List<BbHint> hints = upstreamHints == null ? List.of() : upstreamHints;

        // 이벤트 타입별 프리셋 토글 조회. ★fail-open 폐기 — 실효하지 않으면 빈 맵이라 어떤 라벨도
        //   통과하지 않는다(구 구현은 전 라벨 BOTH 로 열어 저장 기준 없이 폴리곤을 만들었다).
        //   오토라벨 제외 선언(라벨 0건 프리셋)에서 이 단계가 아무것도 만들지 않아야 하는 것도 같은 축이다.
        String eventTypeCd = videoRepository.findById(rawSn)
                .map(LsDataRaw::getEvntTypeCd)
                .orElse(null);
        PresetResolution preset = presetLabelLookup.resolve(eventTypeCd);
        Map<String, AnnotationToggle> toggles = preset.toggles();
        if (!preset.isResolved()) {
            log.info("[Batch][Sam2] preset not effective — no label passes rawSn={} status={}",
                    rawSn, preset.status());
        }

        // srcSn → upstream hint list (프레임 단위 빠른 조회)
        Map<Long, List<BbHint>> hintsBySrc = groupHintsBySrc(hints);

        // ★영상 고정 — 프레임 순회 <전에> 한 번 정한다. YOLO 단계가 이미 배정해 두었으면 <그 장비>가
        //   나온다(영상당 한 건). [design: ADR-057]
        //   ⚠ 후보 0 이면 여기서 거부가 던져진다(폴백 없음) — 프레임을 읽기 전이라 부분 적재가 없다.
        //   ⚠ 원장 조회 실패는 다른 축이다 — 「정하지 못했다」는 표식이 돌아오고(배포 기본 주소),
        //     그 값을 프레임마다 그대로 넘겨 <고정을 지킨다>. 「비었나」로 판정하지 말 것.
        final String srvrAddr = batchAssignment.resolveAddress(rawSn);

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        // ── 재실행 멱등 (@req R1) — 이미 SAM2 폴리곤이 적재된 프레임은 추론·적재를 건너뛴다.
        //
        //  근거는 YoloAutolabelStep 과 동일하다: 자동 재시도 큐가 파이프라인을 선두부터 전부 다시 돌리므로
        //  판정이 없으면 재시도마다 같은 프레임에 폴리곤이 중복 적재된다. 판정 축도 같은
        //  LS_DATA_LBL_AI_INFO(LBL_SRC_CD='SAM2') 이며 영상당 1쿼리다(N+1 금지).
        //
        //  ⚠ 삭제 후 재삽입은 하지 않는다 — 사람이 그 폴리곤을 이미 수정했을 수 있다.
        //
        //  ★ 이력 기록: 멱등 no-op 은 정상 성공과 동일하게 취급하며 LS_BATCH_PROC_LOG 에 SKIPPED 감사 행을
        //  만들지 않는다(YoloAutolabelStep 과 같은 규칙). 관측은 요약 INFO 로그의 skippedFrames 로 한다.
        Set<Long> sam2SegmentedFrames = new HashSet<>(
                lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(rawSn, LsDataLbl.SRC_SAM2));
        int skippedFrames = 0;
        // C-ISSUE-21 — 폴리곤이 실제로 저장된 프레임만 수집(라벨셋 버전 +1 대상, H11 범위 축소).
        Set<Long> labeledFrames = new HashSet<>();
        int saved = 0;
        for (LsDataSrc src : frames) {
            if (sam2SegmentedFrames.contains(src.getSrcSn())) {
                // 멱등 no-op — 외부 추론 호출 0건, INSERT 0건, 라벨셋 버전 bump 없음.
                skippedFrames++;
                continue;
            }
            // (srcSn, label) 키로 중복 제거하면서 SAM2 호출 단위(SegmentJob)를 생성
            List<SegmentJob> jobs = buildJobs(src, hintsBySrc.getOrDefault(src.getSrcSn(), List.of()));
            if (jobs.isEmpty()) {
                continue;
            }
            String relPath = resolveImagePath(src);
            String imageB64 = readImageAsBase64(relPath);
            // B-ISSUE-42 — 이 프레임의 저장 대기 폴리곤. job 마다 save() 하지 않고 프레임 끝에서 saveAll() 한다.
            List<AutoLabelBatchPersister.PendingLabel> pending = new ArrayList<>();
            for (SegmentJob job : jobs) {
                AnnotationToggle toggle = resolveToggle(toggles, job.label);
                if (toggle == null) {
                    // 매핑 존재 + 허용 라벨에 미포함 → 노이즈 제거 (방어)
                    continue;
                }
                if (!toggle.polygon()) {
                    // POLYGON 비활성 라벨 — 정상 흐름에서는 YOLO 가 hint 를 미발행하므로 도달하지 않음.
                    // DB BBOX 만 BBOX_ONLY 라벨로 들어왔다면 본 방어 코드가 발동.
                    log.warn("[Batch] sam2 skipped polygonDisabled label={} rawSn={} srcSn={}",
                            job.label, rawSn, src.getSrcSn());
                    continue;
                }
                Sam2Response resp = callSam2(imageB64, job.box, src.getSrcSn(), srvrAddr);
                if (resp == null || resp.polygon() == null) {
                    // NEW-H1 — 빈 200 바디·무본문 프록시 응답 등 <b>결측 응답</b>. 배포 환경에서는
                    //   "폴리곤 0건인데 배치는 성공" 이라는 무증상 실패를 남기지 않는다(YOLO 와 동일).
                    if (deployedEnvironment.isDeployed()) {
                        log.error("[Batch][Sam2] empty/malformed response on deployed env — aborting step. "
                                + "rawSn={} srcSn={}", rawSn, src.getSrcSn());
                        throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                                "AI 추론 응답이 비었습니다 — 배치 중단");
                    }
                    continue;
                }
                // NEW-H1 — 신뢰 불가 응답(mock/메타 생략)의 폴리곤은 <b>어느 환경에서도 저장하지 않는다</b>.
                if (resp.untrusted()) {
                    // CWE-117 (Log Injection) — source/mockReason 은 외부 ai-server 응답에서 유래하므로
                    //   로그·예외 메시지 모두 LogSanitizer 로 정제한 값만 싣는다(CWE-209 포함).
                    String safeReason = LogSanitizer.sanitize(resp.mockReason());
                    if (blocksUntrusted()) {
                        log.error("[Batch][Sam2] untrusted response on deployed env — aborting step. "
                                        + "rawSn={} srcSn={} source={} mockReason={}",
                                rawSn, src.getSrcSn(), LogSanitizer.sanitize(resp.source()), safeReason);
                        throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                                "SAM2 모델 미배포(mockReason=" + safeReason + ") — 배치 중단");
                    }
                    log.warn("[Batch][Sam2] mock response — ai-server is in mock mode. "
                                    + "rawSn={} srcSn={} source={} mockReason={}",
                            rawSn, src.getSrcSn(), LogSanitizer.sanitize(resp.source()), safeReason);
                    continue;
                }
                BigDecimal score = BigDecimal.valueOf(resp.score()).setScale(4, RoundingMode.HALF_UP);
                // ISSUE-1: SAM2 적재 폴리곤을 저장 검증 상한(MAX_POINTS_PER_LABEL) 이하로 단순화.
                // 적재(무제한)와 라벨 저장(1000점 cap)의 정합성 불일치로 인한 저장 차단 회귀 방지.
                List<List<Double>> capped = capPolygon(resp.polygon());
                // Phase 6: ai-server 응답 라벨명을 LS_LABEL 마스터 PK 로 매핑 (미매칭 시 null).
                Long labelId = labelMasterService.findLabelIdByDtctType(job.label).orElse(null);
                log.info("[Batch][Sam2] mapped label name={} labelId={} points={}",
                        LogSanitizer.sanitize(job.label), labelId, capped.size());
                pending.add(new AutoLabelBatchPersister.PendingLabel(LsDataLbl.createAutoPolygon(
                        src.getSrcSn(), labelId, job.label, serialize(capped), score), score));
                saved++;
                labeledFrames.add(src.getSrcSn());
            }
            // B-ISSUE-42 — 프레임 단위 일괄 저장(라벨 saveAll → AI 메타 saveAll). 저장 대상 0건이면 no-op.
            AutoLabelBatchPersister.saveAll(lblRepository, pending, LsDataLbl.SRC_SAM2);
        }
        // C-ISSUE-21 — 배치 분할이 라벨 row 를 만든 <b>그 프레임</b>의 라벨셋 버전을 +1 한다(단일 UPDATE).
        //   근거는 YoloAutolabelStep 과 동일 — 재처리/재실행이 라벨링 중에도 가능하므로 편집 화면의 낡은
        //   버전을 무효화해 lost update 를 막는다.
        // DEV_FIX(H11 범위) — 구 구현의 영상 전 프레임 bump(과잉 무효화 + 광역 쓰기 락)를 실제 변경 프레임으로 축소.
        // DEV_FIX(H4 주석 정정) — "INSERT 라 락 순서 규약 대상이 아니다"는 근거는 부정확하다. bump 자체가
        //   프레임 행에 쓰기 락을 잡으므로 이 문장도 락 획득이다. 이 경로가 안전한 진짜 이유는 <b>본
        //   트랜잭션의 유일한 프레임 락 획득 지점이 이 한 문장</b>이고, 그 시점까지 기존 라벨 행 락을
        //   하나도 쥐고 있지 않기 때문이다(신규 INSERT 행은 타 트랜잭션이 볼 수 없어 경합 대상이 아니다).
        //   따라서 대기하면서 다른 락을 붙잡는 상태가 없어 순환 대기의 구성원이 될 수 없다.
        if (!labeledFrames.isEmpty()) {
            srcRepository.bumpLabelVersionIn(labeledFrames);
        }
        log.info("[Batch][Sam2] saved polygons rawSn={} count={} skippedFrames={}", rawSn, saved, skippedFrames);
        return saved;
    }

    /**
     * 이 신뢰 불가 응답이 <b>배치 중단</b> 사유인가 (NEW-H1).
     *
     * <p>배포 환경(stg/prd)이면 <b>사유와 무관하게</b> 중단하고, local/dev 면 중단하지 않는다.
     * 다만 중단하지 않는 경우에도 <b>폴리곤은 저장하지 않는다</b>(호출부가 스킵) — 이 점이
     * {@code YoloAutolabelStep.blocksUntrusted()} 와 다른 유일한 지점이며, 이유는 두 추론기의
     * mock 형상이 대칭이 아니기 때문이다:
     * <ul>
     *   <li>YOLO({@code ai-server/app/routers/yolo.py}) — {@code weights_missing}/{@code load_failed}
     *       는 <b>빈 detections</b> 라 dev 에서 진행해도 저장될 가짜 라벨이 없다.</li>
     *   <li>SAM2({@code ai-server/app/routers/sam2.py::_mock_segment}) — <b>사유와 무관하게 항상</b>
     *       합성 사각 폴리곤(score 0.95)을 만든다. 즉 SAM2 에는 "빈 응답이라 안전한 사유"가 없어,
     *       dev 에서 진행시키면 그 즉시 가짜 폴리곤이 LS_DATA_LBL 에 적재된다.</li>
     * </ul>
     * <p>따라서 dev 스킵은 관대함을 줄인 것이 아니라 <b>YOLO 의 "빈 detections" 와 결과를 맞춘 것</b>이다
     * (모델 없이 배치를 완주해 보는 개발 동선은 그대로 유지된다 — 예외를 던지지 않는다).
     *
     * <p>이 경로는 ai-server 기동 가드({@code app/startup_guard.py})가 대신 막아주지 못한다.
     * {@code weights_missing}/{@code load_failed} 는 {@code AI_MOCK_MODE} 와 무관한 별도 사유라,
     * SAM2 모델만 부분 배포 안 된 형상에서는 YOLO 가 실모델로 통과하고 이 단계만 mock 이 된다.
     */
    private boolean blocksUntrusted() {
        return deployedEnvironment.isDeployed();
    }

    private Sam2Response callSam2(String imageB64, List<Double> box, Long srcSn, String srvrAddr) {
        try {
            // [design: ADR-056] 용도를 배치로 명시한다 — ai-server 의 배치 전용 실행 슬롯으로 가고 서킷도 배치 축을
            // 쓴다(ADR-056). 명시하지 않으면 화면 슬롯으로 떨어져 작업자 요청과 한 줄에 선다.
            // [design: ADR-057] 장비는 <이 영상에 고정된> 것을 쓴다. 프레임마다 다시 고르면 같은 영상이
            //   두 장비로 흩어져 객체 식별자가 어긋난다.
            return aiServerClient.segment(new Sam2Request(imageB64, null, box), AiWorkload.BATCH, srvrAddr)
                    .block(Duration.ofSeconds(70));
        } catch (RuntimeException e) {
            log.error("[Batch][Sam2] failed srcSn={} err={}", srcSn, e.getMessage());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 호출 실패", e);
        }
    }

    /**
     * (srcSn, label, trackId) 키로 dedup 한 SAM2 호출 단위를 생성한다. (Phase 4)
     * <p>DB BBOX 우선 — 같은 (라벨, trackId) 가 hint 로도 들어오면 hint 는 무시.
     * trackId 가 null 인 경우 record equality 의 null 비교로 자연스럽게 label 단위 dedup 이 된다.
     */
    private List<SegmentJob> buildJobs(LsDataSrc src, List<BbHint> frameHints) {
        // DB BBOX 우선 등록 (insertion-order 보존: BBOX → hint)
        LinkedHashMap<DedupKey, SegmentJob> jobs = new LinkedHashMap<>();
        List<LsDataLbl> bboxes = lblRepository.findBySrcSnAndAutoLblYn(src.getSrcSn(), LsDataLbl.AUTO_YES).stream()
                .filter(l -> LsDataLbl.TYPE_BBOX.equals(l.getLblTypeCd()))
                .toList();
        for (LsDataLbl lbl : bboxes) {
            Integer trackId = parseTrackId(lbl.getTrackId());
            DedupKey key = new DedupKey(src.getSrcSn(), lbl.getLabelNm(), trackId);
            jobs.putIfAbsent(key, new SegmentJob(lbl.getLabelNm(), parseBbox(lbl.getPointCn())));
        }
        for (BbHint h : frameHints) {
            DedupKey key = new DedupKey(h.srcSn(), h.label(), h.trackId());
            jobs.putIfAbsent(key, new SegmentJob(h.label(), h.points()));
        }
        return new ArrayList<>(jobs.values());
    }

    /**
     * LsDataLbl.trackId 는 String(VARCHAR) 으로 저장되지만 ai-server 는 Integer 를 부여한다.
     * dedup 키는 BbHint(Integer) 와 일치해야 하므로 정수 파싱. 파싱 실패는 null 로 fallback.
     */
    private static Integer parseTrackId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            // legacy/임의 문자열 trackId — null 로 fallback (라벨 단위 dedup 으로 흡수)
            return null;
        }
    }

    private static Map<Long, List<BbHint>> groupHintsBySrc(List<BbHint> hints) {
        if (hints.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<BbHint>> bySrc = new LinkedHashMap<>();
        for (BbHint h : hints) {
            bySrc.computeIfAbsent(h.srcSn(), k -> new ArrayList<>()).add(h);
        }
        return bySrc;
    }

    private static AnnotationToggle resolveToggle(Map<String, AnnotationToggle> toggles, String rawLabel) {
        if (rawLabel == null) {
            return null;
        }
        // Phase 4: 토글 맵 키(마스터 검출유형 DTCT_TYPE_CD, COCO 축 정규화)와 동일 규칙으로 검출 라벨을 정규화해 축을 일치시킨다.
        // ⚠ 맵에 없으면 null — 저장하지 않는다. 구 "맵이 비었으면 전량 허용" 폴백은 폐기됐다. [@design ADR-054]
        return toggles.get(PresetLabelLookupService.normalizeLabelKey(rawLabel));
    }

    /**
     * 추론 입력 이미지 경로 — 배치 오토라벨은 정책상 <b>원본</b> 프레임에만 실행한다.
     *
     * <p>M-6 (null 가드) — 원본 경로가 결측이면 그대로 반환해 하위에서
     * {@code baseRawPath.resolve(null)} NPE(500)로 터졌다. 결측은 추상 메시지로 fail-fast 한다
     * (해상도 파생 프레임처럼 원본이 실재하지 않는 프레임은 배치 대상이 아니다).
     */
    private String resolveImagePath(LsDataSrc src) {
        String path = src.getSrcFilePathNm();
        if (path == null || path.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 이미지 경로가 없습니다.");
        }
        return path;
    }

    private String readImageAsBase64(String relativePath) {
        Path imagePath = baseRawPath.resolve(relativePath).normalize();
        if (!imagePath.startsWith(baseRawPath)) {
            // HIGH-2 fix (CWE-209): 클라이언트 응답에 내부 스토리지 경로 노출 금지.
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 경로 범위 초과");
        }
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            // HIGH-2 fix (CWE-209): 내부 경로 노출 금지.
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 파일 읽기 실패", e);
        }
    }

    /**
     * 저장된 BBOX 라벨의 POINT_CN 을 SAM2 box prompt 가 기대하는 평탄 {@code [x1,y1,x2,y2]} 로 변환한다.
     *
     * <p>DEV_FIX: Phase 1 좌표 정규화로 YOLO 가 BBOX 를 nested {@code [[x1,y1],[x2,y2]]} 로 저장한다.
     * 이전 {@code TypeReference<List<Double>>} flat 전용 파싱은 nested 입력에서 실패하는 회귀가 있었다.
     * {@link LabelPointSerializer#fromJson}(flat·nested·object-array 3변종 흡수)으로 {@link Point}
     * 리스트를 얻은 뒤 {@code (x, y)} 순으로 평탄화한다.
     *
     * @return SAM2 가 기대하는 평탄 좌표. 파싱 불가/빈 입력은 {@code null} (box 없이 호출).
     */
    private List<Double> parseBbox(String pointsJson) {
        if (pointsJson == null) {
            return null;
        }
        try {
            List<Point> points = LabelPointSerializer.fromJson(pointsJson, objectMapper);
            if (points.isEmpty()) {
                return null;
            }
            List<Double> flat = new ArrayList<>(points.size() * 2);
            for (Point p : points) {
                flat.add(p.x());
                flat.add(p.y());
            }
            return flat;
        } catch (IllegalArgumentException e) {
            // 좌표 형식 인식 실패 — box 없이 SAM2 호출 (메시지에 원본 JSON 미노출, CWE-117/209).
            log.warn("[Batch][Sam2] bbox 파싱 실패 — box 없이 호출: {}", LogSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }

    /**
     * SAM2 응답 폴리곤([[x,y],...])을 저장 검증 상한({@link kr.co.cudo.authoring.label.service.LabelService#MAX_POINTS_PER_LABEL})
     * 이하로 단순화한다. 상한 이하면 원본을 그대로 반환한다.
     */
    private List<List<Double>> capPolygon(List<List<Double>> polygon) {
        if (polygon == null || polygon.size() <= kr.co.cudo.authoring.label.service.LabelService.MAX_POINTS_PER_LABEL) {
            return polygon;
        }
        List<kr.co.cudo.authoring.common.util.Point> pts = new ArrayList<>(polygon.size());
        for (List<Double> p : polygon) {
            if (p != null && p.size() >= 2) {
                pts.add(new kr.co.cudo.authoring.common.util.Point(p.get(0), p.get(1)));
            }
        }
        List<kr.co.cudo.authoring.common.util.Point> simplified =
                kr.co.cudo.authoring.common.util.PolygonSimplifier.simplifyToMax(
                        pts, 1.0, kr.co.cudo.authoring.label.service.LabelService.MAX_POINTS_PER_LABEL);
        List<List<Double>> out = new ArrayList<>(simplified.size());
        for (kr.co.cudo.authoring.common.util.Point p : simplified) {
            out.add(List.of(p.x(), p.y()));
        }
        return out;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "polygon 직렬화 실패", e);
        }
    }

    /**
     * SAM2 호출 단위 — 라벨 + bbox 좌표.
     * 동일 (srcSn, label, trackId) 의 DB BBOX 와 upstreamHint 가 동시 존재할 경우 DB BBOX 좌표가 우선.
     */
    private record SegmentJob(String label, List<Double> box) {
    }

    /**
     * SAM2 dedup 키 (Phase 4) — 같은 라벨이라도 trackId 가 다르면 별도 객체로 간주.
     * trackId=null 인 경우 record equality 의 null 처리로 라벨 단위 dedup 으로 fallback.
     */
    private record DedupKey(long srcSn, String label, Integer trackId) {
    }
}
