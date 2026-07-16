package kr.co.cudo.authoring.label.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3 — YOLO 오토라벨 <b>수동(온라인) 트리거</b> 오케스트레이션 (RQ SFR-08 라벨링 편의).
 *
 * <p>라벨링 화면 툴바에서 작업자가 한 프레임에 대해 YOLO 자동 검출을 즉시 실행한다. 배치
 * {@link kr.co.cudo.authoring.batch.step.YoloAutolabelStep} 와 <b>동일한 좌표 정규화·저장 로직</b>
 * ({@link kr.co.cudo.authoring.batch.step.YoloLabelPersister})을 공유하여 이원화를 막는다.
 *
 * <p><b>트랜잭션 경계 분리 (F-1 커넥션풀 고갈 방지)</b>: 본 오케스트레이션 메서드는 <b>non-transactional</b>
 * 이다. ai-server 블로킹 호출(최대 70s)이 control HikariCP 커넥션을 점유하지 않도록, DB 삭제+삽입만
 * {@link AutolabelPersistService}(별도 빈, 짧은 @Transactional)로 위임한다. 오케스트레이션에 @Transactional 을
 * 붙이면 AI 블로킹 동안 커넥션이 잡혀 동시 요청이 풀(20)을 고갈시켜 앱 전역 DB 마비를 유발한다.
 *
 * <p>보안 / 시나리오 방어:
 * <ul>
 *   <li>IDOR(CWE-639): 진입 최우선 {@link LabelAccessGuard#verifyAndGet} — ai 호출·저장 전에 본인
 *       배정 프레임만 통과(WORKER), REVIEWER 전체 허용.</li>
 *   <li>작업락: {@link WorkLockService#isRawLocked} 잠금 시 409 — 비식별 재처리 중 프레임 변경 차단.
 *       저장 직전 {@link AutolabelPersistService} 에서 <b>재확인</b>(#6 TOCTOU).</li>
 *   <li>동시성(CWE-362): 프레임 단위 in-flight 락으로 진행 중 재요청 409 → 중복 삽입 차단.</li>
 *   <li>동시 병렬 제한(F-2 bulkhead): {@code aiOnline} Resilience4j Bulkhead 로 온라인 AI 경로 동시
 *       호출 수를 제한 — 초과 시 429(TOO_MANY_REQUESTS)로 fail-fast. Tomcat 스레드 고갈 방어.
 *       배치 YOLO 경로는 본 bulkhead 미적용(온라인 전용 인스턴스).</li>
 *   <li>입력 검증(CWE-20): ai 응답 좌표 [x1,y1,x2,y2] 4개·유한(NaN/Infinity 거부)·비음수·순서
 *       (x2&gt;x1,y2&gt;y1) 검증 — 비정상 좌표가 저장되어 이후 역직렬화에서 500 을 유발하는 것을 차단.</li>
 *   <li>mock 차단: ai-server mock 응답은 DB 저장하지 않고 플래그만 반환(학습데이터 오염 방지).</li>
 *   <li>포털 차단: 컨트롤러 @PreAuthorize(REVIEWER/WORKER) + SecurityConfig 채널 격리(CHANNEL_INTERNAL)
 *       로 PORTAL 토큰은 진입 자체가 물리 차단(ADR-013).</li>
 *   <li>SSRF: ai base-url 은 AiServerClient 내부 설정값. 경로 순회(CWE-22): FrameImageEncoder 가드.</li>
 * </ul>
 */
@Slf4j
@Service
public class AutolabelOnlineService {

    /** SystemConfig 조회 실패 시 폴백 — YoloAutolabelStep 과 동일. */
    private static final double DEFAULT_CONF_THRESHOLD = 0.4;
    private static final int DEFAULT_IMGSZ = 1280;
    private static final double DEFAULT_IOU = 0.5;

    private final AiServerClient aiServerClient;
    private final LabelAccessGuard accessGuard;
    private final SystemConfigService systemConfigService;
    private final WorkLockService workLockService;
    private final FrameImageEncoder frameImageEncoder;
    private final AutolabelPersistService persistService;
    /** 온라인 AI 경로 전용 동시 호출 제한 (F-2) — 배치 경로와 격리. */
    private final Bulkhead aiOnlineBulkhead;

    public AutolabelOnlineService(AiServerClient aiServerClient,
                                  LabelAccessGuard accessGuard,
                                  SystemConfigService systemConfigService,
                                  WorkLockService workLockService,
                                  FrameImageEncoder frameImageEncoder,
                                  AutolabelPersistService persistService,
                                  @Qualifier("aiOnlineBulkhead") Bulkhead aiOnlineBulkhead) {
        this.aiServerClient = aiServerClient;
        this.accessGuard = accessGuard;
        this.systemConfigService = systemConfigService;
        this.workLockService = workLockService;
        this.frameImageEncoder = frameImageEncoder;
        this.persistService = persistService;
        this.aiOnlineBulkhead = aiOnlineBulkhead;
    }

    /** 프레임 단위 in-flight 락 — 단일 인스턴스 배포(CLAUDE.md) 전제의 동시 중복 트리거 차단. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 오토라벨 오케스트레이션 결과 — <b>내부 전용</b>(FE-facing DTO 아님).
     *
     * <p>{@code mock} 은 ai-server 내부 mock(모델 미로드) 여부로, 컨트롤러가 이를 읽어
     * {@code ApiResponse.message} 에 안내를 세팅한다(SAM2 세그와 대칭). {@link AutolabelResponse}
     * 자체에는 mock 플래그를 두지 않아 FE 계약을 불변으로 유지한다.
     */
    public record AutolabelOutcome(AutolabelResponse response, boolean mock) {
    }

    /**
     * 오토라벨 오케스트레이션 — <b>non-transactional</b>. AI 블로킹 호출을 트랜잭션 밖에서 수행하고
     * 저장만 {@link AutolabelPersistService}(짧은 트랜잭션)에 위임한다(F-1).
     */
    public AutolabelOutcome autolabel(Long srcSn, TokenClaims actor) {
        // 1) IDOR 최우선 — 본인 배정 프레임 검증 후 프레임 획득(rawSn/경로 확보). (non-tx: 단순 스칼라 조회)
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        Long rawSn = src.getRawSn();

        // 2) 작업락 — 비식별 재처리 등으로 잠긴 영상은 오토라벨 거부. (저장 직전 재확인 — #6 TOCTOU)
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상입니다.");
        }

        // 3) 동시 중복 트리거 차단(CWE-362) — 진행 중 재요청은 409.
        if (!inFlight.add(srcSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 오토라벨링이 진행 중인 프레임입니다.");
        }
        try {
            // 4) ai-server YOLO 추론 (원본 프레임) — 트랜잭션 밖·bulkhead 제한. DB 커넥션 미점유.
            YoloResponse resp = callAiServer(src, rawSn);
            boolean mock = resp != null && resp.mock();
            List<YoloResponse.Detection> detections =
                    (resp == null || resp.detections() == null) ? List.of() : resp.detections();

            // mock 안전장치: 내부 YoloResponse.mock() 을 계속 읽어 DB 저장을 스킵한다(학습데이터 오염 방지).
            // FE 계약(AutolabelResponse)에는 mock 플래그가 없으므로 savedCount=0 + 빈 labels 로 미저장을 신호한다.
            if (mock) {
                log.warn("[Autolabel] mock response — skip persist srcSn={} source={} reason={}",
                        srcSn, LogSanitizer.sanitize(resp.source()), LogSanitizer.sanitize(resp.mockReason()));
                // mock 신호를 컨트롤러로 전달 → ApiResponse.message 에 안내 세팅(자동적용 차단). savedCount=0 skip-save 유지.
                return new AutolabelOutcome(new AutolabelResponse(srcSn, 0, List.of()), true);
            }

            // 5) 좌표 검증 — 하나라도 비정상이면 저장 없이 400(부분 저장 방지, fail-closed).
            for (YoloResponse.Detection d : detections) {
                validateBbox(d.points());
            }

            // 6) 저장 — 짧은 control 트랜잭션(삭제+삽입 원자성 + TOCTOU 잠금 재확인)에 위임.
            List<AutolabelResponse.Item> items = persistService.persist(srcSn, rawSn, detections, actor);
            return new AutolabelOutcome(new AutolabelResponse(srcSn, items.size(), items), false);
        } finally {
            inFlight.remove(srcSn);
        }
    }

    /** ai-server YOLO 추론 호출 — 요청 단위 고유 clipId 로 트래커 상태 격리, bulkhead 로 동시성 제한(F-2). */
    private YoloResponse callAiServer(LsDataSrc src, Long rawSn) {
        String imageB64 = frameImageEncoder.encodeToBase64(src.getSrcFilePathNm());
        double conf = readDoublePercent(ConfigKeys.YOLO_CONF_THRESHOLD, DEFAULT_CONF_THRESHOLD);
        int imgsz = readInt(ConfigKeys.YOLO_IMGSZ, DEFAULT_IMGSZ);
        double iou = readDoublePercent(ConfigKeys.YOLO_IOU, DEFAULT_IOU);
        // 단일 프레임 요청 — clipId 는 요청마다 격리(동시 요청 간섭 방지), frameIndex=0(트래커 리셋).
        String clipId = rawSn + ":" + UUID.randomUUID();
        try {
            // BulkheadOperator 를 최외곽에 두어 permit 을 블로킹 호출(재시도 포함) 전 구간에 걸쳐 점유.
            return aiServerClient.predictYoloTrack(
                            new YoloTrackRequest(imageB64, clipId, 0, conf, imgsz, iou))
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
