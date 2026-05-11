package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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

/**
 * SAM2 segment 단계 (Phase 5 — SAM2).
 * <p>
 * YOLO BBOX 가 있는 프레임에 대해 SAM2 segment 호출 → POLYGON 라벨 추가 INSERT.
 *  - YOLO 라벨이 0건인 프레임은 SAM2 호출 skip (효율성).
 *  - autoLblYn = 'Y'.
 *
 * 보안:
 *  - Path Manipulation (CWE-22): baseRawPath 기준 경로 범위 내로 제한.
 */
@Slf4j
@Component
public class Sam2SegmentStep {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final ObjectMapper objectMapper;
    private final Path baseRawPath;

    public Sam2SegmentStep(AiServerClient aiServerClient,
                           LsDataSrcRepository srcRepository,
                           LsDataLblRepository lblRepository,
                           ObjectMapper objectMapper,
                           @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.aiServerClient = aiServerClient;
        this.srcRepository = srcRepository;
        this.lblRepository = lblRepository;
        this.objectMapper = objectMapper;
        this.baseRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
    }

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        int saved = 0;
        for (LsDataSrc src : frames) {
            List<LsDataLbl> bboxes = lblRepository.findBySrcSnAndAutoLblYn(src.getSrcSn(), LsDataLbl.AUTO_YES);
            if (bboxes.isEmpty()) {
                continue;
            }
            String relPath = resolveImagePath(src);
            String imageB64 = readImageAsBase64(relPath);
            // 첫 번째 YOLO BBOX를 SAM2 box 힌트로 전달
            List<Double> box = parseBbox(bboxes.get(0).getPointsJson());
            Sam2Response resp;
            try {
                resp = aiServerClient.segment(new Sam2Request(imageB64, null, box))
                        .block(Duration.ofSeconds(70));
            } catch (RuntimeException e) {
                log.error("[Batch][Sam2] failed srcSn={} err={}", src.getSrcSn(), e.getMessage());
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 호출 실패", e);
            }
            if (resp == null || resp.polygon() == null) {
                continue;
            }
            BigDecimal score = BigDecimal.valueOf(resp.score()).setScale(4, RoundingMode.HALF_UP);
            // TODO(Phase 6): 다중 객체별 SAM2 호출/병합 — 현재는 V1.7 정책으로 첫 번째 라벨만 처리
            String label = bboxes.get(0).getLabel();
            lblRepository.save(LsDataLbl.createAutoPolygon(src.getSrcSn(), label, serialize(resp.polygon()), score));
            saved++;
        }
        log.info("[Batch][Sam2] saved polygons rawSn={} count={}", rawSn, saved);
        return saved;
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

    private List<Double> parseBbox(String pointsJson) {
        if (pointsJson == null) {
            return null;
        }
        try {
            // MEDIUM-3 fix: raw type 대신 TypeReference 사용 → 역직렬화 타입 안전성 확보.
            return objectMapper.readValue(pointsJson, new TypeReference<List<Double>>() {});
        } catch (JsonProcessingException e) {
            log.warn("[Batch][Sam2] bbox 파싱 실패 — box 없이 호출: {}", e.getMessage());
            return null;
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "polygon 직렬화 실패", e);
        }
    }
}
