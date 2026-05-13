package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.EventPresetMapping;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * YOLO 자동 라벨링 단계 (Phase 5 — YOLO).
 * <p>
 * 프레임별 AiServerClient.predictYolo() 호출 → 검출 결과를 LS_DATA_LBL INSERT.
 *  - autoLblYn = 'Y' (강제)
 *  - confScore = response.score (0.0~1.0; clamp 는 LsDataLbl 내부에서 처리)
 *  - lblTypeCd = BBOX
 * <p>
 * 이벤트 타입 기반 프리셋 필터 (V1.8):
 *  - 영상의 EVNT_TYPE_CD 에 매핑된 라벨만 INSERT (노이즈 제거).
 *  - 미정/미매핑 이벤트는 fail-safe 로 전체 통과.
 *  - 라벨 비교는 소문자 + trim 정규화.
 *
 * 보안:
 *  - SSRF: AiServerClient 내부에서 application.yml ai-server.base-url 사용.
 *  - Insecure Deserialization: Jackson 표준 ObjectMapper 사용. enableDefaultTyping 없음.
 *  - Path Manipulation (CWE-22): baseRawPath 기준 경로 범위 내로 제한.
 */
@Slf4j
@Component
public class YoloAutolabelStep {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper;
    private final Path baseRawPath;

    public YoloAutolabelStep(AiServerClient aiServerClient,
                             LsDataSrcRepository srcRepository,
                             LsDataLblRepository lblRepository,
                             VideoRepository videoRepository,
                             ObjectMapper objectMapper,
                             @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.aiServerClient = aiServerClient;
        this.srcRepository = srcRepository;
        this.lblRepository = lblRepository;
        this.videoRepository = videoRepository;
        this.objectMapper = objectMapper;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
    }

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        // 이벤트 타입별 프리셋 필터 (fail-safe): raw 미존재/이벤트 미정 시 전체 통과.
        String eventTypeCd = videoRepository.findById(rawSn)
                .map(LsDataRaw::getEvntTypeCd)
                .orElse(null);
        Optional<Set<String>> allowedLabels = EventPresetMapping.labelsFor(eventTypeCd);

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        int saved = 0;
        int yoloTotal = 0;
        for (LsDataSrc src : frames) {
            String relPath = resolveImagePath(src);
            String imageB64 = readImageAsBase64(relPath);
            YoloResponse resp;
            try {
                resp = aiServerClient.predictYolo(new YoloRequest(imageB64))
                        .block(Duration.ofSeconds(70));
            } catch (RuntimeException e) {
                log.error("[Batch][Yolo] failed srcSn={} err={}", src.getSrcSn(), e.getMessage());
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "YOLO 호출 실패", e);
            }
            if (resp == null || resp.detections() == null) {
                continue;
            }
            for (YoloResponse.Detection d : resp.detections()) {
                yoloTotal++;
                if (!isLabelAllowed(allowedLabels, d.label())) {
                    continue;
                }
                BigDecimal score = BigDecimal.valueOf(d.score()).setScale(4, RoundingMode.HALF_UP);
                lblRepository.save(LsDataLbl.createAutoBbox(
                        src.getSrcSn(), d.label(), serialize(d.points()), score));
                saved++;
            }
        }
        log.info("[Batch][Yolo] saved labels rawSn={} eventType={} yoloCount={} filteredCount={} preset={}",
                rawSn, eventTypeCd, yoloTotal, saved,
                allowedLabels.map(Set::toString).orElse("(none)"));
        return saved;
    }

    /**
     * 이벤트 프리셋 매핑이 있으면 허용 라벨 집합에 포함된 경우에만 통과시킨다.
     * 매핑이 없으면(fail-safe) 모두 통과.
     */
    private static boolean isLabelAllowed(Optional<Set<String>> allowedLabels, String rawLabel) {
        if (allowedLabels.isEmpty()) {
            return true;
        }
        if (rawLabel == null) {
            return false;
        }
        String normalized = rawLabel.trim().toLowerCase();
        return allowedLabels.get().contains(normalized);
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
