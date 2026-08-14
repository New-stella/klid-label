package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.DetectionBoxNormalizer;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * YOLO 자동 라벨링 단계 (Phase 5 — YOLO, Phase 4 — Track 전환).
 * <p>
 * 프레임별 AiServerClient.predictYoloTrack() 호출 → 검출 결과를 LS_DATA_LBL INSERT.
 *  - autoLblYn = 'Y' (강제)
 *  - confScore = response.score (0.0~1.0; clamp 는 LsDataLbl 내부에서 처리)
 *  - lblTypeCd = BBOX
 *  - trackId   = ultralytics 트래커가 부여한 객체 ID (null 허용 — 저신뢰 fallback)
 * <p>
 * Phase 4 — Track 호출 전환:
 *  - clipId   : {@code String.valueOf(rawSn)} — 영상 단위 트래커 상태 격리 키.
 *  - frameIndex : 영상 내 프레임 순서(0 부터 누적). ai-server 가 0 일 때 트래커 상태를 리셋.
 *  - 같은 영상의 모든 프레임은 반드시 단일 스레드에서 순서대로 호출되어야 한다
 *    ({@code findByRawSnOrderByFrameNoAsc} 가 ORDER BY 보장). 정적/필드에 frameIndex 저장 금지.
 * <p>
 * 이벤트 타입 기반 프리셋 필터 (V1.8):
 *  - 영상의 EVNT_TYPE_CD 에 매핑된 프리셋(LS_LABEL_PRESET.EVNT_TYPE_CD) 의 라벨 코드만 INSERT (노이즈 제거).
 *  - 매핑은 운영자가 프리셋 UI 에서 동적으로 관리 — {@link PresetLabelLookupService} 가 DB 조회.
 *  - 미정/미매핑 이벤트는 fail-safe 로 전체 통과.
 *  - 라벨 비교는 소문자 + trim 정규화.
 * <p>
 * Phase 2 — 라벨별 BBOX/POLYGON 토글 분기:
 *  - {@code toggle.bbox()=true} 라벨은 기존처럼 LS_DATA_LBL 에 BBOX row INSERT.
 *  - {@code toggle.bbox()=false} 라벨은 LS_DATA_LBL INSERT skip (POLYGON_ONLY 라벨).
 *  - {@code toggle.polygon()=true} 라벨은 {@link BbHint} 로 누적하여 Sam2SegmentStep 에 전달.
 *  - {@code toggle.polygon()=false} 라벨은 BbHint 미발행.
 *  - togglesFor empty (fail-safe): 모든 라벨이 {@link AnnotationToggle#BOTH} 로 처리 — 기존 동작.
 *  - 메서드 반환 타입은 {@code List<BbHint>} 로, 정적/필드 저장 없이 호출자에게 인메모리 전달.
 *
 * G-ISSUE-02 — 배포 환경 mock 응답 fail-closed:
 *  - stg/prd 에서 ai-server 가 신뢰 불가 응답(가중치 부재/로드 실패/메타 생략)을 주면 <b>첫 프레임에서</b>
 *    스텝 전체를 실패시킨다(all-or-nothing). <b>사유 면제는 없다</b> — {@code env_mock} 면제는
 *    폐기됐다(그 사유가 유일하게 합성 라벨을 적재하는 사유였다. {@link #blocksUntrusted()} 참조).
 *  - local/dev 는 기존 WARN-only 유지. 판정은 {@link DeployedEnvironmentDetector}(정적 설정만) + 이미
 *    받은 응답 메타로만 하며 <b>추가 네트워크 호출이 없다</b>.
 *
 * <b>재실행 멱등 (@req R1)</b>:
 *  - 이미 YOLO 자동 라벨({@code LS_DATA_LBL_AI_INFO.LBL_SRC_CD='YOLO'})이 있는 프레임은
 *    <b>적재(INSERT)만 건너뛰고 추론은 그대로 수행</b>한다. 자동 재시도 큐가 파이프라인을 선두부터 다시
 *    돌리므로, 적재를 막지 않으면 재시도마다 자동 라벨이 중복 적재된다.
 *  - <b>추론까지 건너뛰지 않는 이유</b>: 이 스텝의 산출물은 자기 적재만이 아니라 <b>다음 단계(SAM2)의
 *    입력</b>인 {@link BbHint} 이며, 그것은 {@code BatchContext} 인메모리로만 전달된다. 두 스텝은 별개
 *    트랜잭션이라 "YOLO 성공 → SAM2 실패 → 재시도" 에서 추론까지 건너뛰면 hints 가 0건이 되고 SAM2 는
 *    예외 없이 0 을 반환해 <b>배치가 폴리곤 없이 COMPLETED 로 완주</b>한다(조용한 미완성 성공).
 *    상세 근거·기각한 대안은 {@link #run} 주석에 있다.
 *  - <b>삭제 후 재삽입 금지</b> — 사람이 그 라벨을 이미 수정했을 수 있다({@link #run} 주석 참조).
 *  - ⚠ 구 서술 폐기(되살리지 말 것): "추론·적재를 <b>둘 다</b> 건너뛴다 / 한 프레임에 POLYGON 전용 라벨이
 *    섞여 있으면 그 폴리곤은 재생성되지 않는다(알려진 한계)". 그것은 한계가 아니라 조용한 손실이었고,
 *    범위도 POLYGON 전용에 한정되지 않았다(그 프레임의 힌트가 <b>전부</b> 재발행되지 않았다).
 * <p>
 * 보안:
 *  - SSRF: AiServerClient 내부에서 application.yml ai-server.base-url 사용.
 *  - Insecure Deserialization: Jackson 표준 ObjectMapper 사용. enableDefaultTyping 없음.
 *  - Path Manipulation (CWE-22): baseRawPath 기준 경로 범위 내로 제한.
 */
@Slf4j
@Component
public class YoloAutolabelStep implements BatchStep {

    /** Phase 1 fallback — SystemConfig 미설정/조회 실패 시 사용할 기본값(0.4). */
    static final double DEFAULT_CONF_THRESHOLD = 0.4;
    /** Phase 1 fallback — SystemConfig 미설정/조회 실패 시 사용할 기본 imgsz(1280px). */
    static final int DEFAULT_IMGSZ = 1280;
    /** Phase 1 fallback — SystemConfig 미설정/조회 실패 시 사용할 기본 IoU(0.5). */
    static final double DEFAULT_IOU = 0.5;

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final VideoRepository videoRepository;
    private final PresetLabelLookupService presetLabelLookup;
    private final SystemConfigService systemConfigService;
    private final LabelMasterService labelMasterService;
    /** C-ISSUE-41 — 저장 전 좌표 clamp 기준(프레임 실측 [width, height], 캐시). 측정 실패 시 상한 생략. */
    private final FrameBoundsResolver frameBoundsResolver;
    private final ObjectMapper objectMapper;
    private final Path baseRawPath;
    /** G-ISSUE-02 — 배포 환경(stg/prd) 여부. 정적 설정만 읽는 순수 판정(외부 호출 없음). */
    private final DeployedEnvironmentDetector deployedEnvironment;

    public YoloAutolabelStep(AiServerClient aiServerClient,
                             LsDataSrcRepository srcRepository,
                             LsDataLblRepository lblRepository,
                             VideoRepository videoRepository,
                             PresetLabelLookupService presetLabelLookup,
                             SystemConfigService systemConfigService,
                             LabelMasterService labelMasterService,
                             FrameBoundsResolver frameBoundsResolver,
                             ObjectMapper objectMapper,
                             @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
                             DeployedEnvironmentDetector deployedEnvironment) {
        this.aiServerClient = aiServerClient;
        this.srcRepository = srcRepository;
        this.lblRepository = lblRepository;
        this.videoRepository = videoRepository;
        this.presetLabelLookup = presetLabelLookup;
        this.systemConfigService = systemConfigService;
        this.labelMasterService = labelMasterService;
        this.frameBoundsResolver = frameBoundsResolver;
        this.objectMapper = objectMapper;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.deployedEnvironment = deployedEnvironment;
    }

    @Override
    public BatchStage stage() {
        return BatchStage.YOLO;
    }

    /**
     * 파이프라인 진입점 — YOLO 자동 라벨링 결과 힌트를 컨텍스트에 적재한다.
     * 동작 보존: 기존 orchestrator 의 {@code ctx.hints = yoloStep.run(rawSn)} 와 동일.
     *
     * <p><b>트랜잭션 경계는 여기에 있다</b>(DEV_FIX — self-invocation 트랜잭션 부재). 오케스트레이터는
     * 주입받은 <b>빈(프록시)</b> 의 {@code execute} 를 호출하므로 이 애노테이션이 실제로 발효되고,
     * 오케스트레이터 자신은 트랜잭션이 없다({@code process()} 무-tx). 아래 {@code this.run(...)} 은
     * <b>자기호출이라 트랜잭션 어드바이스가 걸리지 않아</b> 본 트랜잭션에 그대로 참여한다 — 즉
     * REQUIRES_NEW 가 두 번 열리지 않는다(스텝 1건 = 트랜잭션 1건). {@code run()} 을 프록시 경유로
     * 바꾸면 중첩되므로 바꾸지 말 것.
     *
     * <p>이 경계가 없던 동안 {@code srcRepository.bumpLabelVersionIn}(@Modifying 네이티브 UPDATE)이
     * "Executing an update/delete query" 로 매번 터져 YOLO 단계가 전량 FAILED 였고, 라벨은 저장되는데
     * {@code LBL_VER} 는 0 으로 남아 lost update 방어가 무효화됐다(로컬 실기동 실측).
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void execute(BatchContext ctx) {
        ctx.setHints(run(ctx.getRawSn()));
    }

    /**
     * 단일 영상의 모든 프레임에 대해 YOLO 자동 라벨링을 수행하고
     * SAM2 단계로 전달할 인메모리 힌트 목록을 반환한다.
     *
     * @param rawSn LS_DATA_RAW.RAW_SN
     * @return {@code polygon=true} 인 라벨의 {@link BbHint} 목록 (불변 보장 위해 새 ArrayList 반환)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public List<BbHint> run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        // 이벤트 타입별 프리셋 필터 (fail-safe): raw 미존재/이벤트 미정 시 전체 통과.
        String eventTypeCd = videoRepository.findById(rawSn)
                .map(LsDataRaw::getEvntTypeCd)
                .orElse(null);
        Optional<Map<String, AnnotationToggle>> togglesOpt = presetLabelLookup.togglesFor(eventTypeCd);

        // Phase 1: 운영 UI 로 조정 가능한 YOLO 추론 파라미터를 1회 조회 (Caffeine 캐시 활용).
        double confThreshold = readDoublePercent(ConfigKeys.YOLO_CONF_THRESHOLD, DEFAULT_CONF_THRESHOLD);
        int imgsz = readInt(ConfigKeys.YOLO_IMGSZ, DEFAULT_IMGSZ);
        double iou = readDoublePercent(ConfigKeys.YOLO_IOU, DEFAULT_IOU);

        // Phase 4: 영상 식별자 — ai-server 트래커가 clipId 단위로 상태 격리.
        // 두 영상이 연속 처리되어도 clip A 의 track_id 가 clip B 로 누수되지 않는다.
        final String clipId = String.valueOf(rawSn);

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        // ── 재실행 멱등 (@req R1) — 이미 YOLO 자동 라벨이 적재된 프레임은 <b>적재(INSERT)만</b> 건너뛴다.
        //
        //  왜 필요한가: 자동 재시도 큐(BatchRetryQuartzJob → BatchOrchestrator.process)는 사람의 조작 없이
        //  파이프라인을 선두부터 전부 다시 돈다. 그때 이 스텝이 "이미 했는지"를 보지 않으면 같은 프레임에
        //  자동 라벨이 <b>중복 적재</b>된다(재시도마다 2배·3배…).
        //
        //  ★★ 왜 <b>추론은 그대로 수행</b>하는가 (DEV_FIX — 구 동작 "추론·적재 둘 다 skip" 폐기).
        //  이 스텝의 산출물은 자기 적재만이 아니다 — {@link BbHint} 는 <b>다음 단계(SAM2)의 입력</b>이며
        //  {@code BatchContext} 인메모리로만 전달된다. YOLO 와 SAM2 는 별개 트랜잭션(스텝 1건 = tx 1건)이라
        //  "YOLO 성공(라벨 커밋) → SAM2 실패 → 재시도" 가 현실적으로 발생하는데, 추론까지 건너뛰면
        //  그 재시도에서 hints 가 0건이 되고 SAM2 는 할 일이 없어 <b>예외 없이 0 을 반환</b>한다. 그러면
        //  INTERPOLATE 까지 통과해 배치가 COMPLETED 로 완주하면서 폴리곤만 없는 <b>조용한 미완성 성공</b>이
        //  된다(오류 신호 0건). 큰 실패를 무증상 데이터 손실로 바꾸는 것이라, 추론을 다시 도는 비용을 택했다.
        //  추론은 아무 상태도 바꾸지 않으므로 "이미 성공한 앞 단계를 건드리지 않는다"는 요구와 충돌하지 않는다
        //  (상태를 바꾸는 것은 적재뿐이며 그것만 건너뛴다).
        //
        //  ⚠ 기각한 대안 (다시 꺼내지 말 것): "skip 조건에 SAM2 완료 여부를 AND 로 넣어 복구가 필요한
        //  프레임만 재추론한다" — 비용은 싸지만 한 스텝이 <b>다른 스텝의 완료 상태</b>를 읽게 되어, SAM2 를
        //  끄거나 순서를 바꾸면(BatchPipelineConfig 한 곳에서 재배치 가능한 것이 이 설계의 불변식이다)
        //  YOLO 의 동작이 조용히 달라진다. 그 결합은 두지 않는다.
        //
        //  ⚠ <b>삭제 후 재삽입은 하지 않는다</b>. 사람이 그 자동 라벨을 이미 수정했을 수 있어 지우면 작업
        //  결과가 파괴된다. {@code TrackInterpolationStep} 이 삭제-재삽입인 것은 그 축이 <b>사람이 만지지
        //  않는 보간 생성행</b>이기 때문이며, 자동 라벨에는 그 근거가 성립하지 않는다.
        //
        //  판정 축은 LS_DATA_LBL_AI_INFO(LBL_SRC_CD='YOLO') 다 — 출처·자동여부가 라벨 본체가 아니라 그
        //  테이블에만 있다. 영상당 1쿼리(N+1 금지).
        //
        //  ★ 이력 기록: 멱등 no-op 은 <b>정상 성공과 동일하게</b> 취급한다 — LS_BATCH_PROC_LOG 에 SKIPPED
        //  감사 행을 만들지 않는다(그 축은 재개가 필요한 보류·사람이 누르는 스킵이 쓸 자리다). 단계 이력은
        //  오케스트레이터의 stage 기록이 그대로 담당하고, 이번 회차에 적재를 건너뛴 사실은 아래 요약 INFO
        //  로그의 persistSkippedFrames 로만 남긴다.
        Set<Long> yoloLabeledFrames = new HashSet<>(
                lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(rawSn, LsDataLbl.SRC_YOLO));
        int persistSkippedFrames = 0;
        List<BbHint> hints = new ArrayList<>();
        // C-ISSUE-21 — 자동 라벨이 실제로 저장된 프레임만 수집(라벨셋 버전 +1 대상, H11 범위 축소).
        Set<Long> labeledFrames = new HashSet<>();
        int bboxSaved = 0;
        int yoloTotal = 0;
        int hintsEmitted = 0;
        // DEV_FIX(M-1/M-2) — 검출 단위 드롭은 관측 가능해야 한다(전체 실패가 아니므로 로그가 유일한 신호).
        int droppedDegenerate = 0;
        int droppedMalformed = 0;
        // Phase 4: 영상 내 프레임 순서(0-base). Repository 가 frame_no ASC 정렬 보장.
        // ultralytics 트래커는 frame_index=0 시 상태 리셋, 그 외엔 persist=True 로 누적.
        // 같은 영상 프레임은 본 루프에서 순차 호출 — 정적/필드 저장 금지(스레드 안전).
        int frameIndex = 0;
        for (LsDataSrc src : frames) {
            // 멱등 판정 (@req R1) — 이 프레임은 <b>적재만</b> 건너뛴다. 추론·좌표 정규화·힌트 발행은 그대로
            //   수행해 다음 단계(SAM2)의 입력이 재시도에서도 동일하게 재현되게 한다(위 블록의 근거 참조).
            //   드롭 판정(퇴화·형식위반)도 그대로 태워, 재시도의 힌트 집합이 최초 실행과 어긋나지 않게 한다.
            boolean persistSkipped = yoloLabeledFrames.contains(src.getSrcSn());
            if (persistSkipped) {
                persistSkippedFrames++;
            }
            // B-ISSUE-42 — 이 프레임의 저장 대기 라벨. 검출마다 save() 하지 않고 프레임 끝에서 saveAll() 한다.
            List<AutoLabelBatchPersister.PendingLabel> pending = new ArrayList<>();
            String relPath = resolveImagePath(src);
            String imageB64 = readImageAsBase64(relPath);
            YoloResponse resp;
            try {
                resp = aiServerClient.predictYoloTrack(
                                new YoloTrackRequest(imageB64, clipId, frameIndex,
                                        confThreshold, imgsz, iou))
                        .block(Duration.ofSeconds(70));
            } catch (RuntimeException e) {
                log.error("[Batch][Yolo] failed srcSn={} frameIndex={} err={}",
                        src.getSrcSn(), frameIndex, e.getMessage());
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "YOLO 호출 실패", e);
            }
            if (resp == null || resp.detections() == null) {
                // G-ISSUE-02(후속) — 빈 200 바디·무본문 프록시 응답은 <b>게이트를 우회하는 경로</b>였다.
                //   구 구현은 여기서 그냥 continue 해, 배포 환경에서도 "라벨 0건인데 배치는 성공" 이라는
                //   아래 mock 게이트가 막으려던 바로 그 무증상 실패가 남았다. 배포 환경에서는 동일하게
                //   all-or-nothing 으로 중단한다(local/dev 는 기존 스킵 유지).
                if (deployedEnvironment.isDeployed()) {
                    log.error("[Batch][YOLO] empty/malformed response on deployed env — aborting step. "
                            + "rawSn={} srcSn={}", rawSn, src.getSrcSn());
                    throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                            "AI 추론 응답이 비었습니다 — 배치 중단");
                }
                frameIndex++;
                continue;
            }
            // 판정은 긍정 증명 기반(untrusted) — mock 메타 생략 응답도 신뢰하지 않는다(AiMockMeta).
            if (resp.untrusted()) {
                // MEDIUM-3 fix (CWE-117 Log Injection): resp.source(), resp.mockReason() 은
                // 외부 ai-server 응답에서 유래 → 신뢰할 수 없음. LogSanitizer 로 CRLF/제어문자
                // 제거 후 출력하며, 예외 메시지에도 정제된 값만 싣는다(CWE-117/209).
                String safeReason = LogSanitizer.sanitize(resp.mockReason());
                if (blocksUntrusted()) {
                    // G-ISSUE-02 — 배포 환경(stg/prd)에서 가중치 부재/로드 실패 mock 이 오면 <b>즉시</b>
                    //   스텝 전체를 실패시킨다(fail-closed).
                    //
                    // ★ 이 축은 위쪽 "검출 단위 드롭"(퇴화 박스·형식 위반)과 <b>다른 축</b>이다.
                    //   그쪽은 검출 1건의 이상이라 스킵하고 나머지를 저장하지만, mock 응답은 <b>모델
                    //   자체가 없다</b>는 신호라 그 영상의 자동 라벨 전체가 무의미하다. 앞 프레임은 정상
                    //   모델, 뒤 프레임은 mock(빈 detections) 인 영상이 남으면 "모델이 없어 라벨이 없는
                    //   프레임"과 "정말 객체가 없는 프레임"이 구분되지 않는다(무증상 오염).
                    // ★ all-or-nothing: 첫 감지에서 던지므로 이후 프레임은 추론하지 않고, 이미 저장된
                    //   앞 프레임 라벨도 run()/execute() 의 REQUIRES_NEW 트랜잭션이 롤백한다. 예외가
                    //   BatchOrchestrator 로 전파되어 후속 SAM2/INTERPOLATE 단계도 실행되지 않는다.
                    // ★ 실행시점 판정 — ai-server 는 별도 프로세스라 기동 순서가 보장되지 않아
                    //   기동시점 조회는 "아직 안 뜬 정상 상황"을 사고로 오인한다. 이미 받은 응답만
                    //   보므로 네트워크 추가 호출이 없다.
                    log.error("[Batch][YOLO] untrusted response on deployed env — aborting step. "
                                    + "rawSn={} srcSn={} source={} mockReason={}",
                            rawSn, src.getSrcSn(), LogSanitizer.sanitize(resp.source()), safeReason);
                    throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                            "YOLOX 가중치 미배포(mockReason=" + safeReason + ") — 배치 중단");
                }
                // local/dev(개발 환경) — 기존 동작 유지(경고만).
                log.warn("[Batch][YOLO] mock response detected — ai-server is in mock mode. "
                                + "rawSn={} srcSn={} source={} mockReason={}",
                        rawSn, src.getSrcSn(),
                        LogSanitizer.sanitize(resp.source()), safeReason);
            }
            // C-ISSUE-41 — 이 프레임의 실측 해상도(캐시). clamp 상한 기준이며, 측정 불가면 null 로
            //   상한만 생략한다(fail-open — 원천 이미지가 없는 정상 작업을 배치가 막지 않는다).
            int[] frameBounds = resp.detections().isEmpty()
                    ? null : frameBoundsResolver.resolve(src).orElse(null);
            for (YoloResponse.Detection d : resp.detections()) {
                yoloTotal++;
                AnnotationToggle toggle = resolveToggle(togglesOpt, d.label());
                if (toggle == null) {
                    // 매핑 존재 + 허용 라벨에 미포함 → 노이즈 제거
                    continue;
                }
                // ── DEV_FIX(M-1) — 좌표 정규화를 검출 루프 <b>선두에서 1회</b> 수행하고, 그 결과를
                //    BBOX 저장과 polygon hint 가 <b>공유</b>한다. 구 구현은 정규화가 persistBbox 안에만
                //    있어 hint 는 언제나 <b>미clamp 원본</b>을 실었고, 그 결과 ①bbox 가 퇴화로 스킵된 검출
                //    ②애초에 bbox=false 인 폴리곤 전용 프리셋에서 DB BBOX 가 없어 Sam2SegmentStep 이
                //    hint 를 채택 → 이미지 완전 밖 좌표가 SAM box 프롬프트로 나가고 그 산출 폴리곤이
                //    LS_DATA_LBL 에 저장됐다(학습데이터 오염). 정규화 실패/퇴화면 bbox·polygon 을 <b>둘 다</b>
                //    스킵한다.
                // ── DEV_FIX(M-2) — 형식 위반(개수 ≠ 4 · null · NaN/Infinity)도 <b>검출 단위 드롭</b>이다.
                //    구 구현은 예외가 run() 밖으로 전파되어 300프레임 영상의 마지막 검출 1건 때문에 그 영상의
                //    YOLO 단계 전체가 실패(작업 상태 FAILED)하고 앞서 저장된 라벨만 남는 부분 상태가 됐다.
                //    같은 클래스가 "영상 1건의 배치를 통째로 실패시키지 않는다"(YoloLabelPersister)를 명시해
                //    놓고 퇴화는 스킵·NaN 은 전체 실패로 정책이 갈리던 자기모순을 해소한다.
                //    ※ 온라인 경로(AutolabelOnlineService·YoloTrackService)의 all-or-nothing 400 은 유지한다
                //      — 사용자가 즉시 재시도할 수 있고 외부 응답 불신 계약이 우선이다. 배치만 관대하다.
                List<Double> points;
                try {
                    Optional<List<Double>> normalized =
                            DetectionBoxNormalizer.normalizeBbox(d.points(), frameBounds);
                    if (normalized.isEmpty()) {
                        droppedDegenerate++;
                        log.warn("[Batch][Yolo] detection dropped — box degenerate after clamp rawSn={} srcSn={} label={}",
                                rawSn, src.getSrcSn(), LogSanitizer.sanitize(d.label()));
                        continue;
                    }
                    points = normalized.get();
                } catch (IllegalArgumentException e) {
                    droppedMalformed++;
                    log.warn("[Batch][Yolo] detection dropped — malformed coordinates rawSn={} srcSn={} label={}",
                            rawSn, src.getSrcSn(), LogSanitizer.sanitize(d.label()));
                    continue;
                }
                // Phase 6: ai-server 응답 라벨명을 LS_LABEL 마스터 PK 로 매핑 (미매칭 시 null).
                Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);
                log.info("[Batch][Yolo] mapped label name={} labelId={}",
                        LogSanitizer.sanitize(d.label()), labelId);
                if (toggle.bbox()) {
                    // Phase 3(online): BBOX 엔티티 생성은 공용 헬퍼로 단일화. 배치 출처 마커 REG_ID = "batch".
                    //
                    // 헬퍼 시그니처는 그대로 두고 <b>이미 정규화된 좌표를 다시 넘긴다</b>. 근거: ①clamp 는
                    // 멱등이고(정규화된 좌표를 재정규화하면 같은 값) 비퇴화 박스는 재정규화 후에도 비퇴화라
                    // 결과가 동일하다 ②헬퍼가 단독 호출돼도 정규화 방어가 유지된다(오버로드를 추가하면
                    // "검증 없는 저장 경로"가 새로 생긴다 — 이 리포에서 반복된 '게이트 없는 쌍둥이' 패턴).
                    // 따라서 여기서 empty/예외가 나오면 그것은 정규화 계약 위반이라 방어적으로 드롭한다.
                    //
                    // B-ISSUE-42 — 여기서는 <b>저장 대기 목록에 담기만</b> 하고, 실제 INSERT 는 프레임 끝의
                    //   saveAll 1회가 수행한다. "퇴화·형식위반 검출은 스킵하고 나머지는 저장" 시맨틱은
                    //   드롭 판정이 여전히 검출 단위에서 일어나므로 그대로 유지된다.
                    //
                    // @req R1 — {@code persistSkipped} 인 프레임에서도 <b>buildBbox 는 그대로 호출</b>한다.
                    //   순수 인메모리 조립·검증이라 상태를 바꾸지 않으면서, 퇴화/형식위반 시의 {@code continue}
                    //   (= 그 검출의 polygon 힌트도 발행하지 않는다)가 최초 실행과 동일하게 재현된다.
                    //   호출을 건너뛰면 재시도의 힌트 집합이 최초 실행보다 넓어져 SAM2 입력이 어긋난다.
                    try {
                        Optional<AutoLabelBatchPersister.PendingLabel> built = YoloLabelPersister.buildBbox(
                                objectMapper, src.getSrcSn(), d.label(), labelId,
                                points, frameBounds, d.score(), d.trackId());
                        if (built.isPresent()) {
                            // @req R1 — 상태를 바꾸는 것은 이 세 줄뿐이므로 <b>여기만</b> 건너뛴다
                            //   (적재 대기 · 저장 카운트 · 라벨셋 버전 bump 대상).
                            if (!persistSkipped) {
                                pending.add(built.get());
                                bboxSaved++;
                                labeledFrames.add(src.getSrcSn());
                            }
                        } else {
                            droppedDegenerate++;
                            log.warn("[Batch][Yolo] detection dropped — box degenerate after clamp rawSn={} srcSn={} label={}",
                                    rawSn, src.getSrcSn(), LogSanitizer.sanitize(d.label()));
                            continue;
                        }
                    } catch (CustomException e) {
                        if (e.getErrorCode() != ErrorCode.INVALID_INPUT) {
                            throw e;
                        }
                        droppedMalformed++;
                        log.warn("[Batch][Yolo] detection dropped — persist rejected coordinates rawSn={} srcSn={} label={}",
                                rawSn, src.getSrcSn(), LogSanitizer.sanitize(d.label()));
                        continue;
                    }
                }
                if (toggle.polygon()) {
                    // C-ISSUE-41 — BbHint 좌표는 SAM box 프롬프트 입력이지만 <b>clamp 된 좌표를 싣는다</b>.
                    //   구 주석("프롬프트는 DB BBOX(=clamp 된 값)를 우선 사용한다")은 사실이 아니었다:
                    //   Sam2SegmentStep.buildJobs 는 (label, trackId) 키로 DB BBOX 를 우선 등록한 뒤
                    //   putIfAbsent 로 hint 를 채우므로, DB BBOX 가 <b>없을 때</b>(bbox 스킵 · 폴리곤 전용
                    //   프리셋)는 hint 가 그대로 프롬프트가 된다. 위에서 정규화한 좌표를 공유해 그 구멍을 막는다.
                    // Phase 4: BbHint 5번째 인자에 d.trackId() (Integer) 그대로 전달.
                    hints.add(new BbHint(src.getSrcSn(), d.label(), points, d.score(), d.trackId()));
                    hintsEmitted++;
                }
            }
            // B-ISSUE-42 — 프레임 단위 일괄 저장(라벨 saveAll 1회). 검출 0건이면 no-op.
            AutoLabelBatchPersister.saveAll(lblRepository, pending, LsDataLbl.SRC_YOLO);
            frameIndex++;
        }
        // C-ISSUE-21 — 배치 오토라벨이 라벨 row 를 만든 <b>그 프레임</b>의 라벨셋 버전을 +1 한다(단일 UPDATE,
        //   N+1 금지). 배치는 통상 배정 이전에 돌지만 수동 재처리/오토라벨 재실행은 라벨링 중에도 가능하므로,
        //   편집 화면이 보유한 버전을 무효화해 낡은 full-replace 저장이 방금 생성된 자동 라벨을 지우는
        //   lost update 를 막는다.
        // DEV_FIX(H11 범위) — 구 구현은 영상 전 프레임(bumpLabelVersionByRawSn)을 올려, 검출이 없어
        //   라벨이 그대로인 프레임을 편집 중인 작업자까지 409 로 밀어내고 영상 전 프레임 행에 쓰기 락을
        //   잡았다. 실제 라벨이 추가된 프레임만 올린다.
        // DEV_FIX(H4 주석 정정) — "INSERT 라 락 순서 규약 대상이 아니다"는 근거는 부정확하다. bump 자체가
        //   프레임 행에 쓰기 락을 잡으므로 이 문장도 락 획득이다. 이 경로가 안전한 진짜 이유는 <b>본
        //   트랜잭션의 유일한 프레임 락 획득 지점이 이 한 문장</b>이고, 그 시점까지 기존 라벨 행 락을
        //   하나도 쥐고 있지 않기 때문이다(신규 INSERT 행은 타 트랜잭션이 볼 수 없어 경합 대상이 아니다).
        if (!labeledFrames.isEmpty()) {
            srcRepository.bumpLabelVersionIn(labeledFrames);
        }
        // persistSkippedFrames>0 이면 재시도 회차다 — 그 프레임들은 추론·힌트는 정상 수행하고 적재만 건너뛴다.
        log.info("[Batch][Yolo] saved labels rawSn={} clipId={} eventType={} frames={} persistSkippedFrames={} yoloCount={} bboxSaved={} hintsEmitted={} droppedDegenerate={} droppedMalformed={} preset={} conf={} imgsz={} iou={}",
                rawSn, clipId, eventTypeCd, frameIndex, persistSkippedFrames, yoloTotal, bboxSaved, hintsEmitted,
                droppedDegenerate, droppedMalformed,
                togglesOpt.map(m -> m.keySet().toString()).orElse("(none)"),
                confThreshold, imgsz, iou);
        return hints;
    }

    /**
     * 이 신뢰 불가 응답이 <b>배치 중단</b> 사유인가 (G-ISSUE-02).
     *
     * <p>배포 환경(stg/prd)이면 <b>사유와 무관하게</b> 차단한다 — local/dev 는 가중치 없이 배치를
     * 돌려보는 것이 정상 개발 동선이라 기존 WARN-only 를 유지한다. 호출부가 이미
     * {@code resp.untrusted()} 를 확인했으므로 여기서는 환경만 판정한다.
     *
     * <p><b>{@code env_mock} 면제는 폐기됐다(되살리지 말 것).</b> 면제는 "개발자가 의도적으로 켠
     * 모드이니 오탐이다" 라는 전제였지만 실측은 정반대였다:
     * <ol>
     *   <li>{@code weights_missing}/{@code load_failed} mock 은 <b>빈 detections</b> 를 내지만
     *       {@code env_mock} mock 은 <b>합성 person 박스</b>(중앙, score=0.9)를 만든다
     *       ({@code ai-server/app/routers/yolo.py}). 즉 면제된 사유가 유일하게
     *       <b>가짜 라벨을 실제로 적재</b>하는 사유였다 — 이 스텝이 막으려던 오염보다 나쁘다.</li>
     *   <li>{@code yolox_loader.get_yolox_model} 이 <b>가중치 존재 확인보다 먼저</b>
     *       {@code AI_MOCK_MODE} 를 보므로, 가중치가 없어도 사유가 {@code env_mock} 으로 보고된다.
     *       즉 "가중치 미배포" 사고가 면제 사유로 위장할 수 있었다.</li>
     * </ol>
     * 개발 편의(모델 없이 배치 실행)는 프로파일 축이 이미 보장하므로 사유 축 면제는 불필요하다.
     * 배포 환경의 mock 형상 자체는 ai-server 기동 가드({@code app/startup_guard.py})가 별도로 막는다.
     */
    private boolean blocksUntrusted() {
        return deployedEnvironment.isDeployed();
    }

    /**
     * 시스템 설정에서 정수 백분율 값을 읽어 비율(/100.0) 로 변환한다.
     * 조회 실패·null·범위 이탈 시 {@code fallback} 반환 (fail-safe).
     */
    private double readDoublePercent(String key, double fallback) {
        try {
            Integer raw = systemConfigService.getInt(key);
            if (raw == null) {
                return fallback;
            }
            // NUMBER_RANGE 는 SystemConfigService.update 시점에 이미 검증됨.
            // 추가 방어: 0 미만/100 초과만 클램프 후 비율 변환.
            int clamped = Math.max(0, Math.min(100, raw));
            return clamped / 100.0;
        } catch (Exception e) {
            log.warn("[Batch][Yolo] {} 조회 실패, 기본값 {} 사용 err={}", key, fallback, e.getMessage());
            return fallback;
        }
    }

    /**
     * 시스템 설정에서 정수 값을 읽어 반환한다.
     * 조회 실패·null 시 {@code fallback} 반환 (fail-safe).
     */
    private int readInt(String key, int fallback) {
        try {
            Integer raw = systemConfigService.getInt(key);
            return raw == null ? fallback : raw;
        } catch (Exception e) {
            log.warn("[Batch][Yolo] {} 조회 실패, 기본값 {} 사용 err={}", key, fallback, e.getMessage());
            return fallback;
        }
    }

    /**
     * togglesFor 결과 + 검출 라벨로 적용할 토글을 결정한다.
     *
     * <ul>
     *   <li>togglesOpt empty (매핑 없음) → {@link AnnotationToggle#BOTH} fail-safe</li>
     *   <li>매핑 존재 + 라벨이 맵에 있음 → 해당 토글</li>
     *   <li>매핑 존재 + 라벨이 맵에 없음 → {@code null} (노이즈 제거)</li>
     * </ul>
     *
     * <p>Phase 4: 토글 맵 키는 마스터 검출유형(DTCT_TYPE_CD, COCO 축) 정규화이며, 검출 라벨({@code d.label()})도
     * 동일 규칙({@link PresetLabelLookupService#normalizeLabelKey(String)})으로 정규화해 축을 일치시킨다.
     */
    private static AnnotationToggle resolveToggle(Optional<Map<String, AnnotationToggle>> togglesOpt,
                                                  String rawLabel) {
        if (togglesOpt.isEmpty()) {
            return AnnotationToggle.BOTH;
        }
        if (rawLabel == null) {
            return null;
        }
        String normalized = PresetLabelLookupService.normalizeLabelKey(rawLabel);
        return togglesOpt.get().get(normalized);
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
}
