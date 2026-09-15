package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.AiCallCancelledException;
import kr.co.cudo.authoring.common.client.AiWaitBudgetPolicy;
import kr.co.cudo.authoring.common.client.CancellableAiCall;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 요청 하나가 프레임 시퀀스 루프에 쓰는 wall-clock 예산 — SAM2 추적·오토라벨 폴리곤 경로와
     * <b>같은 장치</b>다.
     *
     * <p>운영 기본값은 {@link AiWaitBudgetPolicy#TRACK_BATCH_BUDGET} 이며, 예산 소진 분기를 단위
     * 테스트에서 발화시킬 수 있도록 <b>package-private 필드</b>로 노출한다(프로덕션 경로 불변).
     */
    Duration trackTotalBudget = AiWaitBudgetPolicy.TRACK_BATCH_BUDGET;

    /** SystemConfig 조회 실패 시 폴백 — YoloAutolabelStep 과 동일. */
    private static final double DEFAULT_CONF_THRESHOLD = 0.4;
    private static final int DEFAULT_IMGSZ = 1280;
    private static final double DEFAULT_IOU = 0.5;

    private final AiServerClient aiServerClient;
    /**
     * 프레임 조회는 {@link LabelAccessGuard#verifyAndGet} 이 <b>인가 검사와 함께</b> 수행한다 —
     * 이 서비스는 프레임 저장소를 직접 주입받지 않는다. 둘을 따로 부르면 같은 행을 프레임마다 두 번
     * 읽고, 그 중복이 요청 단위 시간 예산을 그대로 깎는다.
     */
    private final LabelAccessGuard accessGuard;
    private final SystemConfigService systemConfigService;
    private final FrameImageEncoder frameImageEncoder;
    /**
     * C-ISSUE-41 — 좌표 clamp 상한 기준(프레임 실측 [width, height]). 프레임마다 필요하지만 자체
     * Caffeine 캐시를 보유하므로 시퀀스 내 재조회 비용은 없다. 측정 실패 시 상한만 생략(fail-open).
     */
    private final FrameBoundsResolver frameBoundsResolver;
    /**
     * 검출 클래스(COCO 영문명) → 라벨 마스터 PK 해석 — @design API-123, DFEAT-019.
     * 단일 프레임 추론 경로({@code AutolabelOnlineService})와 <b>같은 축</b>({@code DTCT_TYPE_CD})을
     * 쓰는 공용 매핑이다(자체 매칭 규칙을 만들지 않는다).
     */
    private final LabelMasterService labelMasterService;

    public YoloTrackResponseDto track(YoloTrackRequest req, TokenClaims actor) {
        return trackWithAccess(internalAccess(actor), req);
    }

    /**
     * 내부 채널 입력 경계 — 본인 배정 인가 · 신고 게이트 이미지 · 좌표 상한. 차단 판정은 두지 않는다
     * (종전대로 이미지 게이트 {@link FrameImageEncoder#encodeFrame} 하나로 끊는다). 요청마다 행위자에 묶어 만든다.
     */
    private AiFrameAccess internalAccess(TokenClaims actor) {
        return new InternalAiFrameAccess(accessGuard, frameImageEncoder, frameBoundsResolver, actor, null);
    }

    /**
     * 자동 추적 <b>추론 본체</b> — 채널 독립. 인가 · 입력 이미지 · 좌표 상한 · 차단 판정은 {@code access} 가
     * 공급한다({@link AiFrameAccess}). 요청 단위 시간 예산 · 교차 영상 혼입 거부(400) · 트래커 clipId 격리 ·
     * 취소 · 좌표 정규화 · 라벨 마스터 식별자 해석 · 예산 절단 부분 결과({@code truncated}/{@code resume})는
     * 모든 채널이 <b>이 한 곳</b>을 공유한다.
     *
     * <p>인가는 시작 프레임과 <b>후속 프레임 전부</b>에 대해 ai 호출 이전에 끝난다(같은 프레임 중복은 1회).
     *
     * @design API-123
     * @param access 채널 입력 경계(한 요청·한 행위자에 묶인 인스턴스)
     */
    public YoloTrackResponseDto trackWithAccess(AiFrameAccess access, YoloTrackRequest req) {
        // ── 요청 단위 시간 예산 ──────────────────────────────────────────────────
        // 프레임마다 자기 상한을 그대로 허용하면 요청 하나의 대기가 프레임 수에 비례해 늘어, 절대
        // 상한 안에 서너 프레임밖에 못 넣는다. 그러면 화면이 요청을 쪼개는데 트래커는 요청마다
        // 리셋되므로(clipId 가 요청 단위) 객체 식별자가 조각마다 새로 매겨진다. 그래서 루프 전체에
        // 단일 데드라인을 두고, 예산이 다하면 그때까지의 결과와 이어 보낼 지점을 돌려준다.
        //
        // ★ 시계를 «프레임 수에 비례하는 일» 이 시작되기 <b>전</b>에 세운다. 아래 접근 검증은
        //   프레임마다 DB 를 한 번씩 읽으므로 시계 밖에 두면 요청 전체 소요가 프레임 수를 따라
        //   늘고, 그러면 예산 정책이 «요청 하나가 언제 끝나는지는 프레임 수와 무관하다» 를 근거로
        //   화면에 내려준 대기 상한을 실제 소요가 넘어선다 — 화면이 정상 요청을 끊는 방향이다.
        //   시계 안에 두면 그 비용은 예산을 «쓰고», 다 쓰면 부분 결과 계약(truncated/resume)이
        //   그대로 받아 준다.
        final long deadlineNanos = System.nanoTime() + trackTotalBudget.toNanos();

        // IDOR 차단 (CWE-639): 시작 + 모든 후속 프레임 접근 권한을 ai 호출 이전에 검증한다.
        // 검증이 조회한 행을 그대로 받아 둔다(verifyAndGet) — 루프에서 같은 행을 findById 로 다시
        // 읽으면 프레임마다 조회가 두 번씩 나가고, 그 중복이 그대로 위 예산을 깎는다.
        LsDataSrc startSrc = access.authorize(req.srcSn());
        Map<Long, LsDataSrc> frameBySrcSn = new HashMap<>();
        frameBySrcSn.put(req.srcSn(), startSrc);
        for (Long nextSrcSn : req.nextSrcSns()) {
            // 같은 프레임이 두 번 실려 와도 검증·조회는 한 번이면 된다(판정 결과는 같다).
            frameBySrcSn.computeIfAbsent(nextSrcSn, access::authorize);
        }
        // 요청 단위 고유 clipId — 동일 rawSn 에 대한 동시 트랙 요청이 ai-server 트래커 상태를
        // 상호 간섭(frameIndex=0 리셋이 상대 세션 초기화)하지 않도록 요청마다 격리한다.
        // 한 요청 내 모든 프레임은 이 동일 clipId 를 공유(트래킹 연속성). UUID 는 격리용(보안 토큰 아님).
        final String clipId = startSrc.getRawSn() + ":" + UUID.randomUUID();
        // 채널 차단 판정(진입) — 내부 채널은 판정을 두지 않는다(통과).
        access.requireNotBlocked(startSrc.getRawSn());

        // YOLO 추론 파라미터 1회 조회 (fail-safe — YoloAutolabelStep 과 동일 규칙).
        double conf = readDoublePercent(ConfigKeys.YOLO_CONF_THRESHOLD, DEFAULT_CONF_THRESHOLD);
        int imgsz = DEFAULT_IMGSZ;  // 설정 키 폐지 — ai-server 로더가 640 고정이라 조정이 무효였다(ConfigKeys javadoc)
        double iou = readDoublePercent(ConfigKeys.YOLO_IOU, DEFAULT_IOU);

        List<Long> sequence = new ArrayList<>(req.nextSrcSns().size() + 1);
        sequence.add(req.srcSn());
        sequence.addAll(req.nextSrcSns());

        List<YoloTrackResponseDto.FrameDetections> frames = new ArrayList<>(sequence.size());
        // 검출 클래스 → 라벨 마스터 PK 해석 결과의 요청 단위 메모. 한 요청은 최대 51프레임을 돌고
        // 같은 클래스명이 프레임마다 반복되므로, 메모가 없으면 검출 건수만큼 DB 를 왕복한다(N+1).
        // 값이 없는(=미매핑) 클래스도 캐시해야 반복 조회가 생기지 않으므로 Optional 을 담는다.
        Map<String, Optional<Long>> labelIdMemo = new HashMap<>();
        boolean truncated = false;

        int frameIndex = 0;
        for (Long sn : sequence) {
            // 데드라인은 «반복 진입 직전» 에 본다 — 폴리곤·SAM2 추적 경로와 같은 모양.
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                truncated = true;
                log.warn("[YoloTrack] budget exhausted clipId={} processed={}/{}",
                        clipId, frames.size(), sequence.size());
                break;
            }
            // 접근 검증 단계에서 이미 조회해 둔 행 — 시퀀스의 모든 프레임이 그 단계를 통과했으므로
            // 여기서 다시 읽지 않는다(없을 수 없다).
            LsDataSrc src = frameBySrcSn.get(sn);

            // 교차 영상 혼입 방지 — 시퀀스 프레임이 시작 프레임과 다른 영상(rawSn)이면 거부.
            // 다른 영상 프레임이 같은 트래커 clipId 세션에 흘러가 조용히 틀린 trackId 를 반환하는 무결성 결함 차단.
            if (!startSrc.getRawSn().equals(src.getRawSn())) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "시퀀스 프레임이 시작 프레임과 다른 영상에 속합니다: srcSn=" + sn);
            }

            // 채널 차단 판정(중간) — 프레임마다 ai 전송 직전에 다시 본다.
            access.requireNotBlocked(src.getRawSn());
            String imageB64 = access.encodeImage(src);

            YoloResponse resp;
            try {
                // 프레임당 자기 상한과 잔여 예산 중 작은 값 — 자기 상한은 그대로 두고(종전 동작),
                // 예산이 더 적게 남았을 때만 그만큼으로 좁힌다.
                Duration perCall = Duration.ofNanos(
                        Math.min(remainingNanos, AiWaitBudgetPolicy.ONLINE_BLOCK_TIMEOUT.toNanos()));
                resp = CancellableAiCall.block(
                        aiServerClient.predictYoloTrack(
                                new kr.co.cudo.authoring.common.client.dto.YoloTrackRequest(
                                        imageB64, clipId, frameIndex, conf, imgsz, iou)),
                        perCall);
            } catch (AiCallCancelledException e) {
                // 사용자 취소 — 남은 프레임을 더 보내지 않고 그대로 올린다(502 로 바꾸지 않는다).
                // ★ 예산 소진 판정보다 <b>먼저</b> 온다. 취소를 부분 결과로 흡수하면 사용자가 누른
                //   취소가 «시간이 모자랐다» 로 둔갑해, 취소했는지 아닌지를 화면이 구분하지 못한다.
                throw e;
            } catch (Exception e) {
                // 예산이 다한 순간의 실패는 «시간이 모자랐다» 로 다룬다 — 502 로 올리면 이미 끝난
                // 앞 프레임의 검출까지 통째로 버려진다. 진행이 0 이면 부분 결과가 아니므로(이어 보내도
                // 같은 지점을 다시 시도한다) 종전대로 실패를 올린다.
                if (!frames.isEmpty() && System.nanoTime() >= deadlineNanos) {
                    truncated = true;
                    log.warn("[YoloTrack] budget exhausted while waiting clipId={} processed={}/{} err={}",
                            clipId, frames.size(), sequence.size(), LogSanitizer.sanitize(e.getMessage()));
                    break;
                }
                // CWE-209: 스택트레이스/내부 경로 미노출. 메시지 요약만.
                log.error("[YoloTrack] ai-server 호출 실패 srcSn={} frameIndex={} err={}",
                        sn, frameIndex, LogSanitizer.sanitize(e.getMessage()));
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "YOLO track 호출 실패: frameIndex=" + frameIndex);
            }

            List<YoloTrackResponseDto.Detected> detections = mapDetections(access, resp, src, labelIdMemo);
            frames.add(new YoloTrackResponseDto.FrameDetections(sn, frameIndex, detections));
            frameIndex++;
        }

        // 채널 차단 판정(마감) — 응답 조립 직전.
        access.requireNotBlocked(startSrc.getRawSn());

        // 예산이 다했으면 «이어 보낼 요청» 을 그대로 만들어 준다. 아직 처리하지 않은 첫 프레임이
        // 다음 요청의 시작(트래커 리셋) 프레임이 된다 — 그래서 이어 보내면 trackId 가 새로 매겨진다.
        YoloTrackResponseDto.Resume resume = null;
        if (truncated) {
            List<Long> remaining = sequence.subList(frames.size(), sequence.size());
            resume = new YoloTrackResponseDto.Resume(remaining.get(0),
                    List.copyOf(remaining.subList(1, remaining.size())));
        }

        log.info("[YoloTrack] proxied clipId={} startSrc={} frames={}/{} truncated={} conf={} imgsz={} iou={}",
                clipId, startSrc.getSrcSn(), frames.size(), sequence.size(), truncated, conf, imgsz, iou);
        return new YoloTrackResponseDto(frames, truncated, resume);
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
     * <h3>라벨 마스터 식별자 (@design API-123, DFEAT-019)</h3>
     * 각 검출에 {@code labelId} 를 실어 보낸다 — 해석 축은 <b>AI 검출 클래스</b>({@code DTCT_TYPE_CD})
     * 이며 단일 프레임 추론 경로와 같은 {@link LabelMasterService#findLabelIdByDtctType(String)} 을
     * <b>재사용</b>한다. 미매핑은 {@code null} 이고 <b>지어내지 않는다</b>.
     *
     * @param src          이 프레임 엔티티 — clamp 상한(실측 해상도) 해석 대상. 프레임마다 다르므로 루프 안에서 해석한다.
     * @param labelIdMemo  요청 단위 클래스명 → 라벨 PK 메모(N+1 방지). 호출자가 소유한다.
     */
    private List<YoloTrackResponseDto.Detected> mapDetections(AiFrameAccess access, YoloResponse resp, LsDataSrc src,
                                                              Map<String, Optional<Long>> labelIdMemo) {
        if (resp == null || resp.detections() == null || resp.detections().isEmpty()) {
            return List.of();
        }
        // 프레임별 실측 해상도(채널 입력 경계 공급 — 내부는 FrameBoundsResolver 자체 캐시). 측정 실패면 null → 상한 생략, 하한만 clamp.
        int[] bounds = access.resolveBounds(src).orElse(null);
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
                    d.label(), points.get(), clampScore(d.score()), d.trackId(),
                    resolveLabelId(d.label(), labelIdMemo)));
        }
        return out;
    }

    /**
     * 검출 클래스명 → 라벨 마스터 PK. 미매핑·빈 클래스명은 {@code null}(값을 지어내지 않는다).
     *
     * <p>메모는 <b>요청 단위</b>다 — 그 사이 마스터 매핑이 바뀌면 한 응답 안에서 값이 갈리는 것이
     * 더 나쁘므로 오히려 요청 내 일관성이 요구되는 방향이다. 장수명 캐시를 두지 않는다.
     */
    private Long resolveLabelId(String cocoLabel, Map<String, Optional<Long>> memo) {
        if (cocoLabel == null || cocoLabel.isBlank()) {
            return null;
        }
        return memo.computeIfAbsent(cocoLabel.trim(),
                key -> labelMasterService.findLabelIdByDtctType(key)).orElse(null);
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
