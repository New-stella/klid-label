package kr.co.cudo.authoring.label.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.common.util.PolygonSimplifier;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 — YOLO 오토라벨 <b>수동(온라인) 트리거</b> 오케스트레이션 (RQ SFR-08 라벨링 편의).
 *
 * <p>라벨링 화면 툴바에서 작업자가 한 프레임에 대해 YOLO 자동 검출을 즉시 실행한다.
 *
 * <p><b>미저장 정책(Phase 1 전환)</b>: 온라인 오토라벨은 더 이상 DB 에 저장하지 않고 ai-server 가 검출한
 * 좌표만 반환한다(SAM2 분할 {@link Sam2SegmentService} 와 동일한 stateless 프록시 패턴). 클라이언트가
 * 결과를 캔버스 작업본에 반영·중복제거한 뒤 기존 라벨 저장 API(PUT /labels)로 확정한다. 따라서 응답
 * {@code labels[].lblSn} 은 항상 {@code null}(미저장 신호)이다. <b>배치 파이프라인 오토라벨</b>
 * ({@link kr.co.cudo.authoring.batch.step.YoloAutolabelStep})은 본 변경과 무관하게 기존대로 저장한다.
 *
 * <p><b>비트랜잭셔널 (F-1 커넥션풀 고갈 방지)</b>: 본 오케스트레이션 메서드는 <b>non-transactional</b>
 * 이다. ai-server 블로킹 호출(최대 70s)이 control HikariCP 커넥션을 점유하지 않도록 DB write 를 하지 않는다.
 *
 * <p>보안 / 시나리오 방어:
 * <ul>
 *   <li>IDOR(CWE-639): 진입 최우선 {@link LabelAccessGuard#verifyAndGet} — ai 호출 전에 본인
 *       배정 프레임만 통과(WORKER), REVIEWER 전체 허용.</li>
 *   <li>작업락(#3): {@link WorkLockService#isRawLocked} 잠금 시 409 — 비식별 재처리 중 프레임 변경 차단.</li>
 *   <li>TOCTOU(#4): AI 블로킹 호출(최대 70s) 완료 후 <b>응답 조립 직전 잠금 재확인</b> — 그사이 비식별
 *       신고가 라벨 퍼지+잠금했다면 좌표를 반환하지 않고 409 로 차단해 프라이버시 불변식을 보호한다.</li>
 *   <li>동시성(CWE-362): 프레임 단위 in-flight 락으로 진행 중 재요청 409 → 중복 트리거 차단.
 *       400(좌표검증)·502(AI실패)·409(락) 모든 경로에서 finally 로 락 해제 보장.</li>
 *   <li>동시 병렬 제한(F-2 bulkhead): {@code aiOnline} Resilience4j Bulkhead 로 온라인 AI 경로 동시
 *       호출 수를 제한 — 초과 시 429(TOO_MANY_REQUESTS)로 fail-fast. Tomcat 스레드 고갈 방어.
 *       배치 YOLO 경로는 본 bulkhead 미적용(온라인 전용 인스턴스).</li>
 *   <li>입력 검증(CWE-20): ai 응답 좌표 [x1,y1,x2,y2] 4개·유한(NaN/Infinity 거부)·비음수·순서
 *       (x2&gt;x1,y2&gt;y1) 를 all-or-nothing 검증 — 하나라도 비정상이면 전체 400(부분 반환 금지).</li>
 *   <li>mock 차단: ai-server mock 응답은 좌표를 반환하지 않고 빈 결과 + 안내 플래그만 반환(오염 방지).</li>
 *   <li>포털 차단: 컨트롤러 @PreAuthorize(REVIEWER/WORKER) + SecurityConfig 채널 격리(CHANNEL_INTERNAL)
 *       로 PORTAL 토큰은 진입 자체가 물리 차단(ADR-013).</li>
 *   <li>SSRF: ai base-url 은 AiServerClient 내부 설정값. 경로 순회(CWE-22): FrameImageEncoder 가드.</li>
 * </ul>
 */
@Slf4j
@Service
public class AutolabelOnlineService {

    /**
     * 매핑된 검출 클래스가 없어 검출을 수행하지 않았을 때 FE 안내(자동적용 차단 신호). 컨트롤러가
     * {@code ApiResponse.message} 에 실어 정상 "0건 검출" 과 구분한다 — 매핑된 라벨만 실제 검출되므로,
     * 라벨 관리에서 COCO 매핑을 먼저 지정해야 함을 안내한다.
     */
    static final String NO_MAPPED_CLASS_MESSAGE =
            "검출할 수 있는 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해 주세요.";

    /** SystemConfig 조회 실패 시 폴백 — YoloAutolabelStep 과 동일. */
    private static final double DEFAULT_CONF_THRESHOLD = 0.4;
    private static final int DEFAULT_IMGSZ = 1280;
    private static final double DEFAULT_IOU = 0.5;

    /** 폴리곤 경로 SAM 분할 박스 상한 폴백 — {@link ConfigKeys#AUTOLABEL_POLYGON_MAX_BOXES} 조회 실패 시. */
    private static final int DEFAULT_POLYGON_MAX_BOXES = 20;

    /** POLYGON_SIMPLIFY_TOLERANCE 조회 실패 시 폴백 epsilon(px) — Sam2SegmentService 와 동일. */
    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 1.0;

    /** 폴리곤 최소 정점 수 (폐곡선). */
    private static final int MIN_POLYGON_POINTS = 3;

    /**
     * 폴리곤 배치 전체(모든 박스 SAM 분할) wall-clock 예산 기본값 (HIGH #1/MED #5, CWE-770/400).
     * <p>개별 {@code .block(60s)} 를 N 회 무한 반복하지 않도록 전체 처리에 단일 데드라인을 둔다.
     * 남은 예산 안에서만 SAM 을 호출하고 초과 시 잘라 안내한다(부분 반환 허용 — 폴리곤 경로 한정).
     */
    private static final Duration DEFAULT_POLYGON_TOTAL_BUDGET = Duration.ofSeconds(60);

    /**
     * 폴리곤 배치 wall-clock 예산 — 예산 소진(truncation) 분기를 단위 테스트에서 발화시킬 수 있도록
     * <b>package-private 필드</b>로 노출한다(운영 기본 {@value}s 상수 유지, 프로덕션 경로 불변).
     * 하드코딩 상수였을 때 테스트 불가하던 분기를 최소 리팩터링으로 검증 가능하게 한다(coverage HIGH).
     */
    Duration polygonTotalBudget = DEFAULT_POLYGON_TOTAL_BUDGET;

    private final AiServerClient aiServerClient;
    private final LabelAccessGuard accessGuard;
    private final SystemConfigService systemConfigService;
    private final WorkLockService workLockService;
    private final FrameImageEncoder frameImageEncoder;
    private final LabelMasterService labelMasterService;
    /** 온라인 AI 경로 전용 동시 호출 제한 (F-2) — 배치 경로와 격리. */
    private final Bulkhead aiOnlineBulkhead;

    public AutolabelOnlineService(AiServerClient aiServerClient,
                                  LabelAccessGuard accessGuard,
                                  SystemConfigService systemConfigService,
                                  WorkLockService workLockService,
                                  FrameImageEncoder frameImageEncoder,
                                  LabelMasterService labelMasterService,
                                  @Qualifier("aiOnlineBulkhead") Bulkhead aiOnlineBulkhead) {
        this.aiServerClient = aiServerClient;
        this.accessGuard = accessGuard;
        this.systemConfigService = systemConfigService;
        this.workLockService = workLockService;
        this.frameImageEncoder = frameImageEncoder;
        this.labelMasterService = labelMasterService;
        this.aiOnlineBulkhead = aiOnlineBulkhead;
    }

    /**
     * 프레임 단위 in-flight 락 — <b>노드-로컬 best-effort</b> 동시 중복 트리거 억제. 2노드 Active-Active
     * 배포에선 노드 간 완전 차단이 아니다(각 노드 독립 Set). 저장 정합성은 본 락이 아니라 저장 게이트
     * (PUT /labels bulkUpsert 의 작업락·멱등)가 최종 보장한다 — 온라인 오토라벨은 미저장 좌표 반환뿐이라
     * 다중노드 동시 진입이 데이터를 오염시키지 않는다.
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 오토라벨 오케스트레이션 결과 — <b>내부 전용</b>(FE-facing DTO 아님).
     *
     * <p>{@code mock} 은 ai-server 내부 mock(모델 미로드) 여부로, 컨트롤러가 이를 읽어
     * {@code ApiResponse.message} 에 안내를 세팅한다(SAM2 세그와 대칭). {@link AutolabelResponse}
     * 자체에는 mock 플래그를 두지 않아 FE 계약을 불변으로 유지한다.
     */
    public record AutolabelOutcome(AutolabelResponse response, boolean mock, String message) {

        /** 하위호환 — 안내 message 없는 2-arg 편의 생성자. */
        public AutolabelOutcome(AutolabelResponse response, boolean mock) {
            this(response, mock, null);
        }
    }

    /** 하위호환 — 클래스 필터·형태 미지정(전체 검출, BBOX) 오버로드. */
    public AutolabelOutcome autolabel(Long srcSn, TokenClaims actor) {
        return autolabel(srcSn, actor, null, null);
    }

    /** 하위호환 — 형태 미지정(BBOX) 3-arg 오버로드. */
    public AutolabelOutcome autolabel(Long srcSn, TokenClaims actor, List<String> classes) {
        return autolabel(srcSn, actor, classes, null);
    }

    /** 하위호환 — 정밀도 override 미지정(시스템설정→상수 폴백) 4-arg 오버로드. */
    public AutolabelOutcome autolabel(Long srcSn, TokenClaims actor, List<String> classes, AutolabelShape shape) {
        return autolabel(srcSn, actor, classes, shape, null, null);
    }

    /**
     * 오토라벨 오케스트레이션 — <b>non-transactional·미저장</b>. AI 블로킹 호출을 트랜잭션 밖에서 수행하고
     * 검출 좌표만 반환한다(DB write 없음, F-1). 클라이언트가 작업본에 반영 후 PUT /labels 로 저장한다.
     *
     * @param classes           검출 대상 클래스 라벨(COCO 영문명) 화이트리스트. null/빈 → 전체(미필터).
     * @param shape             결과 형태(BBOX 기본 | POLYGON). null → BBOX(하위호환). POLYGON 이면 검출 박스마다
     *                          SAM box-prompt 분할로 폴리곤을 산출(HIGH #1/#4/#9 — 상한·예산·부분실패 방어).
     * @param confThreshold     인식 민감도 override(0.25~0.80). null 이면 시스템설정→상수 폴백(무회귀).
     * @param simplifyTolerance 경계 세밀함 override(0.0~50.0, POLYGON 전용). null 이면 시스템설정→상수 폴백.
     */
    public AutolabelOutcome autolabel(Long srcSn, TokenClaims actor, List<String> classes, AutolabelShape shape,
                                      Double confThreshold, Double simplifyTolerance) {
        AutolabelShape effectiveShape = shape == null ? AutolabelShape.BBOX : shape;
        // 1) IDOR 최우선 — 본인 배정 프레임 검증 후 프레임 획득(rawSn/경로 확보). (non-tx: 단순 스칼라 조회)
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        Long rawSn = src.getRawSn();

        // 2) 작업락(#3) — 비식별 재처리 등으로 잠긴 영상은 오토라벨 거부. (AI 호출 후 #4 에서 재확인)
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상입니다.");
        }

        // 3) 동시 중복 트리거 차단(CWE-362) — 진행 중 재요청은 409.
        if (!inFlight.add(srcSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 오토라벨링이 진행 중인 프레임입니다.");
        }
        try {
            // 원본 프레임 이미지를 1회 인코딩 — YOLO + (폴리곤 경로) SAM 이 공유(중복 인코딩 방지).
            String imageB64 = frameImageEncoder.encodeToBase64(src.getSrcFilePathNm());

            // 4) 검출 대상 재구성(HIGH#1, 신뢰 경계) — FE 가 보낸 classes 를 신뢰하지 않고, 서버가
            //    '매핑된 라벨(DTCT_TYPE_CD) → COCO' 조회 결과로 검출 대상을 재구성한다. 미매핑/미지원 값은
            //    제외(WARN)하며, 매핑된 라벨이 없거나 요청이 전부 미매핑이면 ai 호출 없이 빈 결과를 반환한다
            //    (매핑된 라벨만 실제 검출 — 미매핑 우회 차단).
            List<String> effectiveClasses = resolveDetectClasses(classes);
            if (effectiveClasses.isEmpty()) {
                reCheckLock(rawSn);
                log.info("[Autolabel] no mapped detect classes srcSn={} rawSn={} requested={} actor={}",
                        srcSn, rawSn, classes == null ? 0 : classes.size(), actorId(actor));
                return new AutolabelOutcome(new AutolabelResponse(srcSn, 0, List.of()), false,
                        NO_MAPPED_CLASS_MESSAGE);
            }

            // ai-server YOLO 추론 (원본 프레임) — 트랜잭션 밖·bulkhead 제한. DB 커넥션 미점유.
            // 재구성된 매핑 클래스만 전달 → ai-server 가 해당 클래스만 검출(R3 AC3).
            YoloResponse resp = callYolo(src, rawSn, imageB64, effectiveClasses, confThreshold);
            boolean mock = resp != null && resp.mock();
            List<YoloResponse.Detection> detections =
                    (resp == null || resp.detections() == null) ? List.of() : resp.detections();

            // mock 안전장치: 내부 YoloResponse.mock() 을 계속 읽어 좌표 반환을 스킵한다(학습데이터 오염 방지).
            // FE 계약(AutolabelResponse)에는 mock 플래그가 없으므로 detectedCount=0 + 빈 labels 로 신호한다.
            if (mock) {
                log.warn("[Autolabel] mock response — skip detection srcSn={} source={} reason={}",
                        srcSn, LogSanitizer.sanitize(resp.source()), LogSanitizer.sanitize(resp.mockReason()));
                // mock 신호를 컨트롤러로 전달 → ApiResponse.message 에 안내 세팅(자동적용 차단).
                return new AutolabelOutcome(new AutolabelResponse(srcSn, 0, List.of()), true);
            }

            // 5) 좌표 검증 — all-or-nothing. 하나라도 비정상이면 좌표 미반환·400(부분 반환 금지, fail-closed).
            for (YoloResponse.Detection d : detections) {
                validateBbox(d.points());
            }

            // 6) MED #3 — YOLO 박스 0개면 SAM 호출 스킵, 즉시 빈 결과 반환(폴리곤/박스 공통).
            if (detections.isEmpty()) {
                reCheckLock(rawSn); // TOCTOU 재확인(#4) — 빈 결과라도 프라이버시 불변식 확인.
                log.info("[Autolabel] no detections srcSn={} rawSn={} shape={} actor={}",
                        srcSn, rawSn, effectiveShape, actorId(actor));
                return new AutolabelOutcome(new AutolabelResponse(srcSn, 0, List.of()), false);
            }

            // 7) 형태 분기.
            if (effectiveShape == AutolabelShape.POLYGON) {
                return polygonAutolabel(srcSn, rawSn, imageB64, detections, actor, simplifyTolerance);
            }

            // BBOX(기본) — TOCTOU 재확인(#4) 후 검출 좌표를 응답 아이템으로 매핑(미저장, lblSn=null).
            reCheckLock(rawSn);
            List<AutolabelResponse.Item> items = toItems(detections);
            log.info("[Autolabel] detected srcSn={} rawSn={} shape=BBOX count={} actor={}",
                    srcSn, rawSn, items.size(), actorId(actor));
            return new AutolabelOutcome(new AutolabelResponse(srcSn, items.size(), items), false);
        } finally {
            inFlight.remove(srcSn);
        }
    }

    /**
     * 폴리곤 오토라벨(R12) — YOLO 검출 박스마다 SAM box-prompt 분할로 폴리곤을 산출한다(미저장).
     *
     * <p>방어(HIGH #1/#4/#9 · MED #5, CWE-770/400/362):
     * <ul>
     *   <li>박스 개수 상한({@link ConfigKeys#AUTOLABEL_POLYGON_MAX_BOXES}) 까지만 SAM 호출 — 초과분은 잘라 안내.</li>
     *   <li>전체 처리 단일 wall-clock 예산({@link #polygonTotalBudget}) — 개별 block 무한 반복 차단.</li>
     *   <li>박스별 try/catch — 실패/mock 박스는 스킵+사유 로깅, 성공분만 반환(all-or-nothing 아님).</li>
     *   <li>배치 전/후 작업락 재확인(#2 TOCTOU) — 중간 신고 시 좌표 미반환·409.</li>
     * </ul>
     */
    private AutolabelOutcome polygonAutolabel(Long srcSn, Long rawSn, String imageB64,
                                              List<YoloResponse.Detection> detections, TokenClaims actor,
                                              Double simplifyOverride) {
        int maxBoxes = readInt(ConfigKeys.AUTOLABEL_POLYGON_MAX_BOXES, DEFAULT_POLYGON_MAX_BOXES);
        // 경계 세밀함(FEAT-007) — 요청 override 우선, 없으면 시스템설정→상수 폴백(무회귀).
        double simplifyTolerance = simplifyOverride != null ? simplifyOverride : readSimplifyTolerance();
        int detected = detections.size();
        int limit = Math.min(detected, maxBoxes);
        boolean truncated = detected > maxBoxes;

        long deadlineNanos = System.nanoTime() + polygonTotalBudget.toNanos();
        List<AutolabelResponse.Item> items = new ArrayList<>(limit);
        int skipped = 0;
        boolean anyMock = false;

        for (int i = 0; i < limit; i++) {
            // #2 TOCTOU(배치 중) — 중간에 비식별 신고가 잠그면 안전하게 중단(좌표 미반환·409).
            if (workLockService.isRawLocked(rawSn)) {
                throw new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상입니다.");
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                truncated = true; // 예산 소진 — 나머지 박스는 잘라 안내.
                log.warn("[Autolabel] polygon budget exhausted srcSn={} processed={}/{}",
                        srcSn, i, limit);
                break;
            }
            YoloResponse.Detection d = detections.get(i);
            try {
                Sam2Response seg = callSam(srcSn, imageB64, d.points(), remainingNanos);
                if (seg == null || seg.polygon() == null || seg.mock()) {
                    anyMock = anyMock || (seg != null && seg.mock());
                    skipped++;
                    log.warn("[Autolabel] polygon box skipped srcSn={} label={} reason={}",
                            srcSn, LogSanitizer.sanitize(d.label()),
                            seg != null && seg.mock() ? "mock" : "empty");
                    continue;
                }
                validatePolygonPoints(seg.polygon());
                // 경계 세밀함(FEAT-007) — 검증 통과 후 Douglas-Peucker 단순화(3점 미만이면 원본 유지).
                List<List<Double>> polygon = simplifyPolygon(seg.polygon(), simplifyTolerance);
                Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);
                items.add(new AutolabelResponse.Item(
                        null, labelId, d.label(), null, clampScore(d.score()), d.trackId(),
                        AutolabelShape.POLYGON.name(), polygon));
            } catch (CustomException e) {
                // MED-1(자원 보호 우선) — bulkhead 초과 429 는 삼키지 말고 즉시 전파(fail-fast). 부분 스킵으로
                // 흡수하면 Tomcat 스레드 고갈 방어가 무력화된다. 그 외(좌표검증 INVALID_INPUT 등)만 스킵.
                if (e.getErrorCode() == ErrorCode.TOO_MANY_REQUESTS) {
                    throw e;
                }
                // 좌표 검증 실패(폴리곤 형식) 등 — 부분 실패로 스킵(폴리곤 경로는 all-or-nothing 아님).
                skipped++;
                log.warn("[Autolabel] polygon box invalid srcSn={} label={} reason={}",
                        srcSn, LogSanitizer.sanitize(d.label()), LogSanitizer.sanitize(e.getMessage()));
            }
        }

        // #2 TOCTOU(배치 후, 응답 조립 직전) — 그사이 잠기면 좌표 미반환·409.
        reCheckLock(rawSn);

        String message = buildPolygonMessage(detected, items.size(), truncated, anyMock, skipped, items.isEmpty());
        log.info("[Autolabel] detected srcSn={} rawSn={} shape=POLYGON detected={} returned={} skipped={} actor={}",
                srcSn, rawSn, detected, items.size(), skipped, actorId(actor));
        return new AutolabelOutcome(new AutolabelResponse(srcSn, items.size(), items), false, message);
    }

    /**
     * 폴리곤 경로 안내 message 조립.
     * <ul>
     *   <li>상한 초과·예산 소진으로 잘린 경우: "검출 N건 중 상한 M건만 처리".</li>
     *   <li><b>일부</b> 박스가 mock <b>또는 비-mock 실패</b>로 제외된 경우(성공분 존재): "일부 결과 신뢰 불가".</li>
     *   <li><b>전량</b> 실패/mock(반환 0, 검출은 있었음)이면 "모든 결과 신뢰 불가".</li>
     * </ul>
     * {@code skipped} 는 mock·비-mock 을 합한 제외 박스 수로, 비-mock 부분 실패(adversarial MED)도 반드시 고지한다.
     * 특이사항 없으면 null(정상).
     */
    private String buildPolygonMessage(int detected, int processed, boolean truncated,
                                       boolean anyMock, int skipped, boolean empty) {
        StringBuilder sb = new StringBuilder();
        if (truncated) {
            sb.append(AutolabelResponse.polygonTruncatedMessage(detected, processed));
        }
        // mock 이든 비-mock(실패·검증위반)이든 제외된 박스가 있으면 신뢰 불가 안내(전량/일부 구분, code-reviewer LOW).
        boolean anyExcluded = anyMock || skipped > 0;
        if (anyExcluded) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(empty
                    ? AutolabelResponse.POLYGON_ALL_UNRELIABLE_MESSAGE
                    : AutolabelResponse.POLYGON_PARTIAL_MOCK_MESSAGE);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** TOCTOU 재확인 헬퍼(#4) — 잠기면 좌표 미반환·409. */
    private void reCheckLock(Long rawSn) {
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상입니다.");
        }
    }

    /** 검증 통과 detection 을 응답 아이템으로 변환 — <b>미저장</b>이므로 {@code lblSn=null}. */
    private List<AutolabelResponse.Item> toItems(List<YoloResponse.Detection> detections) {
        List<AutolabelResponse.Item> items = new ArrayList<>(detections.size());
        for (YoloResponse.Detection d : detections) {
            Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);
            items.add(new AutolabelResponse.Item(
                    null, labelId, d.label(), d.points(), clampScore(d.score()), d.trackId()));
        }
        return items;
    }

    /** ai 응답 score 를 [0.0, 1.0] 로 clamp. NaN 은 null. */
    private Double clampScore(double raw) {
        if (Double.isNaN(raw)) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, raw));
    }

    /** 감사 로그용 actor 식별자 — null-safe + 로그 위조 방지(CWE-117) sanitize. */
    private String actorId(TokenClaims actor) {
        return actor == null ? "unknown" : LogSanitizer.sanitize(actor.sub());
    }

    /**
     * 검출 대상 클래스 재구성(HIGH#1/#8, 신뢰 경계) — FE 요청을 그대로 신뢰하지 않고 서버가 재검증한다.
     *
     * <p>규칙:
     * <ul>
     *   <li>서버가 '활성 라벨의 COCO 매핑'({@link LabelMasterService#mappedDetectClasses()})을 allowlist 로 삼는다.</li>
     *   <li>요청 classes 가 비어있으면(전체 선택) → 매핑된 전체 클래스로 검출(매핑된 라벨만 검출 강제).</li>
     *   <li>요청 classes 가 있으면 → 매핑 allowlist 와 교집합만 남기고, 미매핑/미지원 값은 제외(WARN).</li>
     *   <li>결과가 비면(매핑 라벨 없음 또는 요청 전부 미매핑) → 빈 리스트(호출자가 ai 호출 없이 빈 결과 반환).</li>
     * </ul>
     */
    private List<String> resolveDetectClasses(List<String> requested) {
        Set<String> mapped = labelMasterService.mappedDetectClasses();
        if (mapped.isEmpty()) {
            return List.of();
        }
        if (requested == null || requested.isEmpty()) {
            return new ArrayList<>(mapped);
        }
        List<String> allowed = new ArrayList<>(requested.size());
        for (String c : requested) {
            String trimmed = c == null ? null : c.trim();
            if (trimmed != null && mapped.contains(trimmed)) {
                allowed.add(trimmed);
            } else {
                // 미매핑/미지원 요청 클래스 — 제외(WARN, 로그 위조 방지 sanitize).
                log.warn("[Autolabel] drop unmapped detect class={}", LogSanitizer.sanitize(trimmed));
            }
        }
        return allowed;
    }

    /**
     * ai-server YOLO 추론 호출 — 요청 단위 고유 clipId 로 트래커 상태 격리, bulkhead 로 동시성 제한(F-2).
     *
     * @param confOverride 인식 민감도 override(0.25~0.80). null 이면 시스템설정→상수 폴백(무회귀).
     */
    private YoloResponse callYolo(LsDataSrc src, Long rawSn, String imageB64, List<String> classes,
                                  Double confOverride) {
        // 인식 민감도(FEAT-007) — 요청 override 우선, 없으면 기존 시스템설정→상수 폴백 경로 그대로(무회귀).
        double conf = confOverride != null
                ? confOverride
                : readDoublePercent(ConfigKeys.YOLO_CONF_THRESHOLD, DEFAULT_CONF_THRESHOLD);
        int imgsz = readInt(ConfigKeys.YOLO_IMGSZ, DEFAULT_IMGSZ);
        double iou = readDoublePercent(ConfigKeys.YOLO_IOU, DEFAULT_IOU);
        // 단일 프레임 요청 — clipId 는 요청마다 격리(동시 요청 간섭 방지), frameIndex=0(트래커 리셋).
        String clipId = rawSn + ":" + UUID.randomUUID();
        try {
            // BulkheadOperator 를 최외곽에 두어 permit 을 블로킹 호출(재시도 포함) 전 구간에 걸쳐 점유.
            return aiServerClient.predictYoloTrack(
                            new YoloTrackRequest(imageB64, clipId, 0, conf, imgsz, iou, classes))
                    .transformDeferred(BulkheadOperator.of(aiOnlineBulkhead))
                    .block(Duration.ofSeconds(70));
        } catch (BulkheadFullException e) {
            // F-2: 온라인 AI 경로 동시 호출 상한 초과 → 429 fail-fast (Tomcat 스레드 고갈 방어).
            log.warn("[Autolabel] bulkhead full — reject srcSn={}", src.getSrcSn());
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "오토라벨 동시 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RuntimeException e) {
            // CWE-209: 스택트레이스/내부 경로 미노출.
            log.error("[Autolabel] ai-server 호출 실패 srcSn={} err={}",
                    src.getSrcSn(), LogSanitizer.sanitize(e.getMessage()));
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "YOLO 오토라벨 호출 실패");
        }
    }

    /**
     * ai-server SAM box-prompt 분할 호출(폴리곤 경로) — bulkhead 동시성 제한 + 남은 예산 내 block.
     *
     * <p>박스별 실패는 폴리곤 경로에서 <b>전파하지 않고</b> 스킵 신호로 다룬다(부분 성공 허용). 단
     * bulkhead 초과(429)만 즉시 상향 — 온라인 AI 자원 보호(F-2)를 우선한다.
     *
     * @param box            SAM box prompt [x1,y1,x2,y2] (검증된 YOLO 검출 좌표)
     * @param remainingNanos 폴리곤 배치 잔여 예산(ns) — block 타임아웃 상한(개별 60s 와 min).
     * @return SAM 응답(mock 여부 포함). 실패 시 {@code null}(스킵 신호).
     */
    private Sam2Response callSam(Long srcSn, String imageB64, List<Double> box, long remainingNanos) {
        // 개별 호출 상한 60s 와 잔여 예산 중 작은 값 — 배치 전체 데드라인을 넘기지 않도록.
        Duration perCall = Duration.ofNanos(Math.min(remainingNanos, Duration.ofSeconds(60).toNanos()));
        try {
            return aiServerClient.segment(new Sam2Request(imageB64, null, box))
                    .transformDeferred(BulkheadOperator.of(aiOnlineBulkhead))
                    .block(perCall);
        } catch (BulkheadFullException e) {
            // 자원 보호 우선 — 동시 호출 상한 초과는 즉시 429(스킵 아님).
            log.warn("[Autolabel] polygon SAM bulkhead full — reject srcSn={}", srcSn);
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "오토라벨 동시 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RuntimeException e) {
            // 박스별 SAM 실패 — 스킵 신호(null). 폴리곤 경로는 성공분만 반환(all-or-nothing 아님).
            log.warn("[Autolabel] polygon SAM call failed srcSn={} err={}",
                    srcSn, LogSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }

    /**
     * 외부 응답 좌표 검증 (CWE-20) — 정확히 4개(x1,y1,x2,y2)이고 모두 유한·비음수이며 순서(x2&gt;x1,y2&gt;y1).
     *
     * <p>NaN/Infinity 는 {@code v < 0} 비교를 통과(NaN&lt;0=false)하므로 {@link Double#isFinite} 로
     * 명시 거부한다 — 미검증 시 저장 후 좌표 역직렬화에서 500 을 유발(#5 / F-4).
     */
    private void validateBbox(List<Double> points) {
        if (points == null || points.size() != 4) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "YOLO 응답 좌표는 [x1,y1,x2,y2] 4개여야 합니다.");
        }
        for (Double v : points) {
            if (v == null || !Double.isFinite(v)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "YOLO 응답 좌표는 유한한 수여야 합니다.");
            }
            if (v < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "YOLO 응답 좌표는 0 이상이어야 합니다.");
            }
        }
        double x1 = points.get(0), y1 = points.get(1), x2 = points.get(2), y2 = points.get(3);
        if (x2 <= x1 || y2 <= y1) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "YOLO 응답 좌표는 x2>x1, y2>y1 이어야 합니다.");
        }
    }

    /**
     * SAM 응답 폴리곤 좌표 검증(CWE-20) — 외부(ai-server) 응답 불신. 정점 최소 3개, 각 [x,y] 두 값,
     * 유한(NaN/Infinity 거부)·비음수. 위반 시 {@link ErrorCode#INVALID_INPUT} (폴리곤 경로에서 스킵으로 처리).
     */
    private void validatePolygonPoints(List<List<Double>> polygon) {
        if (polygon == null || polygon.size() < MIN_POLYGON_POINTS) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "SAM 응답 폴리곤 정점이 " + MIN_POLYGON_POINTS + "개 미만입니다.");
        }
        for (List<Double> pair : polygon) {
            if (pair == null || pair.size() != 2) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "SAM 응답 폴리곤 좌표는 [x, y] 두 값이어야 합니다.");
            }
            for (Double v : pair) {
                if (v == null || !Double.isFinite(v) || v < 0) {
                    throw new CustomException(ErrorCode.INVALID_INPUT,
                            "SAM 응답 폴리곤 좌표는 유한한 0 이상의 수여야 합니다.");
                }
            }
        }
    }

    /** 정수 백분율 → 비율(/100.0). 조회 실패·null 시 fallback (fail-safe). */
    private double readDoublePercent(String key, double fallback) {
        try {
            Integer raw = systemConfigService.getInt(key);
            if (raw == null) {
                return fallback;
            }
            int clamped = Math.max(0, Math.min(100, raw));
            return clamped / 100.0;
        } catch (Exception e) {
            log.warn("[Autolabel] {} 조회 실패, 기본값 {} 사용", key, fallback);
            return fallback;
        }
    }

    /**
     * FEAT-007 경계 세밀함 epsilon 조회. 설정 누락/오류 시 상수 폴백(fail-safe).
     * <p>{@link Sam2SegmentService#readSimplifyTolerance()} 와 동일한 폴백 패턴(폴리곤 온라인 경로 정합).
     */
    private double readSimplifyTolerance() {
        try {
            Double v = systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);
            return v != null ? v : DEFAULT_SIMPLIFY_TOLERANCE;
        } catch (Exception e) {
            log.warn("[Autolabel] POLYGON_SIMPLIFY_TOLERANCE 조회 실패 — 기본값 {} 사용", DEFAULT_SIMPLIFY_TOLERANCE);
            return DEFAULT_SIMPLIFY_TOLERANCE;
        }
    }

    /**
     * FEAT-007 경계 세밀함 — 검증 통과 폴리곤을 Douglas-Peucker 로 단순화한다(Sam2SegmentService 와 동일 규칙).
     * 단순화 결과가 최소 정점 수({@value #MIN_POLYGON_POINTS}) 미만이면 형태 보존을 위해 원본을 그대로 반환한다.
     */
    private List<List<Double>> simplifyPolygon(List<List<Double>> polygon, double tolerance) {
        List<Point> rawPoints = new ArrayList<>(polygon.size());
        for (List<Double> p : polygon) {
            rawPoints.add(new Point(p.get(0), p.get(1)));
        }
        List<Point> simplified = PolygonSimplifier.simplify(rawPoints, tolerance);
        if (simplified.size() < MIN_POLYGON_POINTS) {
            return polygon;
        }
        List<List<Double>> out = new ArrayList<>(simplified.size());
        for (Point p : simplified) {
            out.add(List.of(p.x(), p.y()));
        }
        return out;
    }

    /** 정수 값 조회. 실패·null 시 fallback (fail-safe). */
    private int readInt(String key, int fallback) {
        try {
            Integer raw = systemConfigService.getInt(key);
            return raw == null ? fallback : raw;
        } catch (Exception e) {
            log.warn("[Autolabel] {} 조회 실패, 기본값 {} 사용", key, fallback);
            return fallback;
        }
    }
}
