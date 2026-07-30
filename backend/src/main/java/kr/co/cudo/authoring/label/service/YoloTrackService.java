package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.DetectionBoxNormalizer;
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
import java.util.Optional;
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
 *   <li>입력 검증 (CWE-20 · C-ISSUE-41): ai-server 응답 좌표는 {@link DetectionBoxNormalizer} <b>공용 규칙</b>
 *       으로 정규화한다 — 배치({@code YoloLabelPersister})·AI 탐지({@code AutolabelOnlineService})와 같은
 *       함수다. 형식 위반(개수 ≠ 4 · null · NaN/Infinity)만 400 이고, 경계를 넘긴 좌표는 clamp,
 *       퇴화 박스는 <b>그 검출만</b> 스킵한다.</li>
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
    /**
     * C-ISSUE-41 — 좌표 clamp 상한 기준(프레임 실측 [width, height]). 프레임마다 필요하지만 자체
     * Caffeine 캐시를 보유하므로 시퀀스 내 재조회 비용은 없다. 측정 실패 시 상한만 생략(fail-open).
     */
    private final FrameBoundsResolver frameBoundsResolver;

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

            String imageB64 = frameImageEncoder.encodeFrame(src);

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

            List<YoloTrackResponseDto.Detected> detections = mapDetections(resp, src);
            frames.add(new YoloTrackResponseDto.FrameDetections(sn, frameIndex, detections));
            frameIndex++;
        }

        log.info("[YoloTrack] proxied clipId={} startSrc={} frames={} conf={} imgsz={} iou={}",
                clipId, startSrc.getSrcSn(), frames.size(), conf, imgsz, iou);
        return new YoloTrackResponseDto(frames);
    }

    /**
     * ai-server 응답 → 응답 DTO 매핑. null/빈 detections 는 빈 리스트로 처리(예외 아님).
     *
     * <h3>C-ISSUE-41 — 좌표 정규화는 공용 규칙 단일화</h3>
     * 이 서비스는 배치·AI 탐지와 <b>같은 모델</b>({@code /infer/yolo/track})을 호출하므로 같은 경계 좌표
     * (실측 {@code -1.5731…})가 그대로 온다. 구 구현은 음수를 즉시 400 으로 거부해 C-41 이 폐기한 정책이
     * 남아 있었고, {@code NaN < 0} 이 {@code false} 라 <b>NaN 이 검증을 통과</b>해 응답 DTO 에 실렸으며,
     * 상한(이미지 경계)은 아예 보지 않았다. 이제 {@link DetectionBoxNormalizer} 로 통일한다.
     *
     * <p>폐기 범위 축소(중요): 본 서비스는 <b>최대 50프레임 시퀀스</b>를 한 요청에서 처리하므로, 어느 한
     * 프레임의 경계 검출 1건으로 400 을 내면 <b>시퀀스 전체</b>가 버려진다. 그래서 퇴화 박스는 해당 검출만
     * 스킵하고(WARN), 형식 위반(개수 ≠ 4 · null · NaN/Infinity)만 400 으로 올린다 — 외부 응답 불신 계약상
     * 형식이 깨진 응답은 부분 채택하지 않는다({@code AutolabelOnlineService} 와 동일 규약).
     *
     * @param src 이 프레임 엔티티 — clamp 상한(실측 해상도) 해석 대상. 프레임마다 다르므로 루프 안에서 해석한다.
     */
    private List<YoloTrackResponseDto.Detected> mapDetections(YoloResponse resp, LsDataSrc src) {
        if (resp == null || resp.detections() == null || resp.detections().isEmpty()) {
            return List.of();
        }
        // 프레임별 실측 해상도(FrameBoundsResolver 자체 캐시). 측정 실패면 null → 상한 생략, 하한만 clamp.
        int[] bounds = frameBoundsResolver.resolve(src).orElse(null);
        List<YoloTrackResponseDto.Detected> out = new ArrayList<>(resp.detections().size());
        for (YoloResponse.Detection d : resp.detections()) {
            Optional<List<Double>> points;
            try {
                points = DetectionBoxNormalizer.normalizeBbox(d.points(), bounds);
            } catch (IllegalArgumentException e) {
                // 형식 위반 — 고정 문구만 노출(좌표 원문·내부 경로 미포함, CWE-209).
                throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
            }
            if (points.isEmpty()) {
                log.warn("[YoloTrack] detection dropped — box degenerate after clamp srcSn={} label={}",
                        src.getSrcSn(), LogSanitizer.sanitize(d.label()));
                continue;
            }
            out.add(new YoloTrackResponseDto.Detected(
                    d.label(), points.get(), clampScore(d.score()), d.trackId()));
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
