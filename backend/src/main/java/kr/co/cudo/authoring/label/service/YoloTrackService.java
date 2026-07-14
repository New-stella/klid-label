package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 인터랙티브 YOLO 객체 트랙 추론 서비스 (온디맨드 프록시).
 *
 * <p>성격: 순수 추론 프록시. DB 저장을 하지 않는다({@link Transactional#readOnly()}). 배치
 * {@link kr.co.cudo.authoring.batch.step.YoloAutolabelStep} 가 자동 라벨을 저장하므로 중복 방지를 위해
 * 본 온디맨드 경로는 검출 결과만 반환하고, FE 가 {@code PUT /v1/frames/{srcSn}/labels} 로 저장한다.
 *
 * <p>동작:
 * <ol>
 *   <li>정렬된 프레임 시퀀스 = [srcSn] + nextSrcSns.</li>
 *   <li>frameIndex 0 = 시작 프레임(트래커 리셋), 1..N = 후속 프레임.</li>
 *   <li>각 프레임 이미지를 {@link FrameImageEncoder} 로 base64 인코딩하여 ai-server {@code /infer/yolo/track} 프록시.</li>
 *   <li>요청 단위 고유 clipId({@code rawSn:UUID})로 트래커 상태를 요청마다 격리(동시 요청 간섭 방지).</li>
 * </ol>
 *
 * <p>보안:
 * <ul>
 *   <li>IDOR (CWE-639): 시작 + 모든 후속 프레임에 {@link LabelAccessGuard} 검증.</li>
 *   <li>교차 영상 혼입 방지: 시퀀스 각 프레임의 rawSn 이 시작 프레임과 동일한지 검증(트래커 무결성).</li>
 *   <li>Path Traversal (CWE-22): {@link FrameImageEncoder} 가 storage.raw-path 기준 경로 범위 내로 제한.</li>
 *   <li>입력 검증 (CWE-20): ai-server 응답 points 4개(x1,y1,x2,y2)·비음수 검증.</li>
 *   <li>Log Injection (CWE-117): 외부 유래 라벨명 {@link LogSanitizer} 로 CRLF 제거.</li>
 *   <li>Info Leak (CWE-209): 내부 경로·스택트레이스 클라이언트 노출 금지.</li>
 *   <li>SSRF: ai-server base-url 은 AiServerClient 내부 설정값 사용(사용자 입력 URL 아님).</li>
 *   <li>커넥션 풀 보호 (CWE-770): 쓰기 없는 순수 조회/프록시이므로 @Transactional 미사용 —
 *       최대 51회 blocking ai 호출 동안 DB 커넥션을 점유하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YoloTrackService {

    /** SystemConfig 조회 실패 시 폴백 — YoloAutolabelStep 과 동일. */
    private static final double DEFAULT_CONF_THRESHOLD = 0.4;
    private static final int DEFAULT_IMGSZ = 1280;
    private static final double DEFAULT_IOU = 0.5;

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LabelAccessGuard accessGuard;
    private final SystemConfigService systemConfigService;
    private final FrameImageEncoder frameImageEncoder;

    public YoloTrackResponseDto track(YoloTrackRequest req, TokenClaims actor) {
        // IDOR 차단 (CWE-639): 시작 + 모든 후속 프레임 접근 권한을 ai 호출 이전에 검증.
        accessGuard.verifyAccess(req.srcSn(), actor);
        for (Long nextSrcSn : req.nextSrcSns()) {
            accessGuard.verifyAccess(nextSrcSn, actor);
        }

        LsDataSrc startSrc = srcRepository.findById(req.srcSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "시작 프레임을 찾을 수 없습니다."));
        // 요청 단위 고유 clipId — 동일 rawSn 에 대한 동시 트랙 요청이 ai-server 트래커 상태를
        // 상호 간섭(frameIndex=0 리셋이 상대 세션 초기화)하지 않도록 요청마다 격리한다.
        // 한 요청 내 모든 프레임은 이 동일 clipId 를 공유(트래킹 연속성). UUID 는 격리용(보안 토큰 아님).
        final String clipId = startSrc.getRawSn() + ":" + UUID.randomUUID();

        // YOLO 추론 파라미터 1회 조회 (fail-safe — YoloAutolabelStep 과 동일 규칙).
        double conf = readDoublePercent(ConfigKeys.YOLO_CONF_THRESHOLD, DEFAULT_CONF_THRESHOLD);
        int imgsz = readInt(ConfigKeys.YOLO_IMGSZ, DEFAULT_IMGSZ);
        double iou = readDoublePercent(ConfigKeys.YOLO_IOU, DEFAULT_IOU);

        List<Long> sequence = new ArrayList<>(req.nextSrcSns().size() + 1);
        sequence.add(req.srcSn());
        sequence.addAll(req.nextSrcSns());

        List<YoloTrackResponseDto.FrameDetections> frames = new ArrayList<>(sequence.size());
        int frameIndex = 0;
        for (Long sn : sequence) {
            LsDataSrc src = (frameIndex == 0)
                    ? startSrc
                    : srcRepository.findById(sn)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임 없음: " + sn));

            // 교차 영상 혼입 방지 — 시퀀스 프레임이 시작 프레임과 다른 영상(rawSn)이면 거부.
            // 다른 영상 프레임이 같은 트래커 clipId 세션에 흘러가 조용히 틀린 trackId 를 반환하는 무결성 결함 차단.
            if (!startSrc.getRawSn().equals(src.getRawSn())) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "시퀀스 프레임이 시작 프레임과 다른 영상에 속합니다: srcSn=" + sn);
            }

            String imageB64 = frameImageEncoder.encodeToBase64(src.getSrcFilePathNm());

            YoloResponse resp;
            try {
                resp = aiServerClient.predictYoloTrack(
                                new kr.co.cudo.authoring.common.client.dto.YoloTrackRequest(
                                        imageB64, clipId, frameIndex, conf, imgsz, iou))
                        .block(Duration.ofSeconds(70));
            } catch (Exception e) {
                // CWE-209: 스택트레이스/내부 경로 미노출. 메시지 요약만.
                log.error("[YoloTrack] ai-server 호출 실패 srcSn={} frameIndex={} err={}",
                        sn, frameIndex, LogSanitizer.sanitize(e.getMessage()));
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "YOLO track 호출 실패: frameIndex=" + frameIndex);
            }

            List<YoloTrackResponseDto.Detected> detections = mapDetections(resp);
            frames.add(new YoloTrackResponseDto.FrameDetections(sn, frameIndex, detections));
            frameIndex++;
        }

        log.info("[YoloTrack] proxied clipId={} startSrc={} frames={} conf={} imgsz={} iou={}",
                clipId, startSrc.getSrcSn(), frames.size(), conf, imgsz, iou);
        return new YoloTrackResponseDto(frames);
    }

    /** ai-server 응답 → 응답 DTO 매핑. null/빈 detections 는 빈 리스트로 처리(예외 아님). */
    private List<YoloTrackResponseDto.Detected> mapDetections(YoloResponse resp) {
        if (resp == null || resp.detections() == null) {
            return List.of();
        }
        List<YoloTrackResponseDto.Detected> out = new ArrayList<>(resp.detections().size());
        for (YoloResponse.Detection d : resp.detections()) {
            validateBbox(d.points());
            out.add(new YoloTrackResponseDto.Detected(
                    d.label(), d.points(), clampScore(d.score()), d.trackId()));
        }
        return out;
    }

    /** ai 응답 score 를 [0.0, 1.0] 로 clamp. NaN 은 null (Sam2TrackService.clampScore 와 동일 규칙). */
    private Double clampScore(double raw) {
        if (Double.isNaN(raw)) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, raw));
    }

    /** 외부 응답 좌표 검증 (CWE-20) — 정확히 4개(x1,y1,x2,y2)이고 모두 0 이상. */
    private void validateBbox(List<Double> points) {
        if (points == null || points.size() != 4) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "YOLO 응답 좌표는 [x1,y1,x2,y2] 4개여야 합니다.");
        }
        for (Double v : points) {
            if (v == null || v < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "YOLO 응답 좌표는 0 이상이어야 합니다.");
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
            log.warn("[YoloTrack] {} 조회 실패, 기본값 {} 사용", key, fallback);
            return fallback;
        }
    }

    /** 정수 값 조회. 실패·null 시 fallback (fail-safe). */
    private int readInt(String key, int fallback) {
        try {
            Integer raw = systemConfigService.getInt(key);
            return raw == null ? fallback : raw;
        } catch (Exception e) {
            log.warn("[YoloTrack] {} 조회 실패, 기본값 {} 사용", key, fallback);
            return fallback;
        }
    }
}
