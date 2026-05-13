package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 보안:
 *  - SSRF: AiServerClient 내부에서 application.yml ai-server.base-url 사용.
 *  - Insecure Deserialization: Jackson 표준 ObjectMapper 사용. enableDefaultTyping 없음.
 *  - Path Manipulation (CWE-22): baseRawPath 기준 경로 범위 내로 제한.
 */
@Slf4j
@Component
public class YoloAutolabelStep {

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
    private final ObjectMapper objectMapper;
    private final Path baseRawPath;

    public YoloAutolabelStep(AiServerClient aiServerClient,
                             LsDataSrcRepository srcRepository,
                             LsDataLblRepository lblRepository,
                             VideoRepository videoRepository,
                             PresetLabelLookupService presetLabelLookup,
                             SystemConfigService systemConfigService,
                             ObjectMapper objectMapper,
                             @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.aiServerClient = aiServerClient;
        this.srcRepository = srcRepository;
        this.lblRepository = lblRepository;
        this.videoRepository = videoRepository;
        this.presetLabelLookup = presetLabelLookup;
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
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
        List<BbHint> hints = new ArrayList<>();
        int bboxSaved = 0;
        int yoloTotal = 0;
        int hintsEmitted = 0;
        // Phase 4: 영상 내 프레임 순서(0-base). Repository 가 frame_no ASC 정렬 보장.
        // ultralytics 트래커는 frame_index=0 시 상태 리셋, 그 외엔 persist=True 로 누적.
        // 같은 영상 프레임은 본 루프에서 순차 호출 — 정적/필드 저장 금지(스레드 안전).
        int frameIndex = 0;
        for (LsDataSrc src : frames) {
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
                frameIndex++;
                continue;
            }
            if (resp.mock()) {
                // ai-server 가 mock 응답을 반환한 경우 — 운영에서 데이터 품질 저하 위험.
                // 파이프라인 차단은 별도 정책. 본 hotfix 에서는 경고 로그로만 표시.
                //
                // MEDIUM-3 fix (CWE-117 Log Injection): resp.source(), resp.mockReason() 은
                // 외부 ai-server 응답에서 유래 → 신뢰할 수 없음. LogSanitizer 로 CRLF/제어문자
                // 제거 후 출력.
                log.warn("[Batch][YOLO] mock response detected — ai-server is in mock mode. "
                                + "rawSn={} srcSn={} source={} mockReason={}",
                        rawSn, src.getSrcSn(),
                        LogSanitizer.sanitize(resp.source()),
                        LogSanitizer.sanitize(resp.mockReason()));
            }
            for (YoloResponse.Detection d : resp.detections()) {
                yoloTotal++;
                AnnotationToggle toggle = resolveToggle(togglesOpt, d.label());
                if (toggle == null) {
                    // 매핑 존재 + 허용 라벨에 미포함 → 노이즈 제거
                    continue;
                }
                // Phase 4: trackId 는 Integer (ai-server 의 ultralytics persist 트래커 부여).
                // null 인 경우(저신뢰 detection fallback) 그대로 null 유지.
                String trackIdStr = d.trackId() == null ? null : String.valueOf(d.trackId());
                if (toggle.bbox()) {
                    BigDecimal score = BigDecimal.valueOf(d.score()).setScale(4, RoundingMode.HALF_UP);
                    lblRepository.save(LsDataLbl.createAutoBbox(
                            src.getSrcSn(), d.label(), serialize(d.points()), score, trackIdStr));
                    bboxSaved++;
                }
                if (toggle.polygon()) {
                    // Phase 4: BbHint 5번째 인자에 d.trackId() (Integer) 그대로 전달.
                    hints.add(new BbHint(src.getSrcSn(), d.label(), d.points(), d.score(), d.trackId()));
                    hintsEmitted++;
                }
            }
            frameIndex++;
        }
        log.info("[Batch][Yolo] saved labels rawSn={} clipId={} eventType={} frames={} yoloCount={} bboxSaved={} hintsEmitted={} preset={} conf={} imgsz={} iou={}",
                rawSn, clipId, eventTypeCd, frameIndex, yoloTotal, bboxSaved, hintsEmitted,
                togglesOpt.map(m -> m.keySet().toString()).orElse("(none)"),
                confThreshold, imgsz, iou);
        return hints;
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
     */
    private static AnnotationToggle resolveToggle(Optional<Map<String, AnnotationToggle>> togglesOpt,
                                                  String rawLabel) {
        if (togglesOpt.isEmpty()) {
            return AnnotationToggle.BOTH;
        }
        if (rawLabel == null) {
            return null;
        }
        String normalized = rawLabel.trim().toLowerCase();
        return togglesOpt.get().get(normalized);
    }

    private String resolveImagePath(LsDataSrc src) {
        if (src.getDeidFilePath() != null) {
            Path deidPath = baseRawPath.resolve(src.getDeidFilePath()).normalize();
            if (deidPath.startsWith(baseRawPath) && Files.exists(deidPath)) {
                return src.getDeidFilePath();
            }
        }
        return src.getFilePath();
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

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "points 직렬화 실패", e);
        }
    }
}
