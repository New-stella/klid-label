package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Phase 11 — 포털 간편 라벨링(YOLO 체험) 서비스.
 *
 *  - PORTAL_USER 가 본인 업로드 영상 프레임에 대해 YOLO 추론을 1회 호출.
 *  - 본 Phase 는 결과를 DB 에 저장하지 않고 즉시 반환만 (체험 목적).
 *  - 본인 영상 검증은 컨트롤러/서비스 진입 전에 PortalUploadService.getMyVideo 로 수행해야 함.
 *
 * 보안:
 *  - AiServerClient 자체에 Resilience4j 타임아웃/재시도/서킷브레이커 적용됨.
 *  - 추가로 짧은 .block(timeout) 으로 동기 호출 안전성 확보.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalAutolabelService {

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(60);

    private final AiServerClient aiServerClient;

    public YoloResponse predict(String imageB64) {
        if (imageB64 == null || imageB64.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "imageB64 누락");
        }
        try {
            YoloResponse result = aiServerClient.predictYolo(new YoloRequest(imageB64, 0.4, 1280, 0.5))
                    .block(BLOCK_TIMEOUT);
            if (result == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "AI 응답이 비어있습니다.");
            }
            log.info("[Portal] autolabel completed detections={}",
                    result.detections() == null ? 0 : result.detections().size());
            return result;
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Portal] autolabel failed message={}", e.getMessage());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "YOLO 호출 실패");
        }
    }
}
