package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * YOLO 자동 라벨링 단계 (Phase 5 — YOLO).
 * <p>
 * 프레임별 AiServerClient.predictYolo() 호출 → 검출 결과를 LS_DATA_LBL INSERT.
 *  - autoLblYn = 'Y' (강제)
 *  - confScore = response.score (0.0~1.0; clamp 는 LsDataLbl 내부에서 처리)
 *  - lblTypeCd = BBOX
 *
 * 보안:
 *  - SSRF: AiServerClient 내부에서 application.yml ai-server.base-url 사용.
 *  - Insecure Deserialization: Jackson 표준 ObjectMapper 사용. enableDefaultTyping 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YoloAutolabelStep {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final ObjectMapper objectMapper;

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        int saved = 0;
        for (LsDataSrc src : frames) {
            String imageRef = src.getDeidFilePath() != null ? src.getDeidFilePath() : src.getFilePath();
            YoloResponse resp;
            try {
                resp = aiServerClient.predictYolo(new YoloRequest(imageRef))
                        .block(Duration.ofSeconds(70));
            } catch (RuntimeException e) {
                log.error("[Batch][Yolo] failed srcSn={} err={}", src.getSrcSn(), e.getMessage());
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "YOLO 호출 실패", e);
            }
            if (resp == null || resp.detections() == null) {
                continue;
            }
            for (YoloResponse.Detection d : resp.detections()) {
                BigDecimal score = BigDecimal.valueOf(d.score()).setScale(4, RoundingMode.HALF_UP);
                lblRepository.save(LsDataLbl.createAutoBbox(
                        src.getSrcSn(), d.label(), serialize(d.points()), score));
                saved++;
            }
        }
        log.info("[Batch][Yolo] saved labels rawSn={} count={}", rawSn, saved);
        return saved;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // points 직렬화 실패는 데이터 무결성 문제 — 명시적 예외.
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "points 직렬화 실패", e);
        }
    }
}
