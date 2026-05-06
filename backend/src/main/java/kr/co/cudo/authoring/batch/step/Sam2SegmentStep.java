package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 * SAM2 segment 단계 (Phase 5 — SAM2).
 * <p>
 * YOLO BBOX 가 있는 프레임에 대해 SAM2 segment 호출 → POLYGON 라벨 추가 INSERT.
 *  - YOLO 라벨이 0건인 프레임은 SAM2 호출 skip (효율성).
 *  - autoLblYn = 'Y'.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Sam2SegmentStep {

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
            List<LsDataLbl> bboxes = lblRepository.findBySrcSnAndAutoLblYn(src.getSrcSn(), LsDataLbl.AUTO_YES);
            if (bboxes.isEmpty()) {
                continue;
            }
            String imageRef = src.getDeidFilePath() != null ? src.getDeidFilePath() : src.getFilePath();
            Sam2Response resp;
            try {
                resp = aiServerClient.segment(new Sam2Request(imageRef, null, null))
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
            // (BBOX 라벨이 여러 개여도 SAM2 segment 는 영상 단위 1회 호출. 객체 검증은 VLM 단계 책임)
            String label = bboxes.get(0).getLabel();
            lblRepository.save(LsDataLbl.createAutoPolygon(src.getSrcSn(), label, serialize(resp.polygon()), score));
            saved++;
        }
        log.info("[Batch][Sam2] saved polygons rawSn={} count={}", rawSn, saved);
        return saved;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "polygon 직렬화 실패", e);
        }
    }
}
