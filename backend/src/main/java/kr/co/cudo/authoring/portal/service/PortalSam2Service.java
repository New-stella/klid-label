package kr.co.cudo.authoring.portal.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 9 — 포털 채널 전용 SAM2 인터랙티브 추론 서비스 (세그멘테이션 + 자동추적).
 *
 * <p><b>핵심 안전 설계 (CRITICAL — 위반 시 데이터마트 오염)</b>:
 * <ul>
 *   <li>내부 {@link kr.co.cudo.authoring.label.service.Sam2TrackService} 는 Phase 3 이후 추적 결과를
 *       LS_DATA_LBL 에 <b>즉시 저장하지 않고 좌표만 반환</b>(draft)한다. 그럼에도 포털 채널은 인가·자원
 *       경계가 달라(APPROVED 데이터마트 노출 영상 재검증·포털 전용 bulkhead/rate-limit) 내부 서비스를 그대로
 *       개방하지 않고 본 서비스로 격리한다. 본 서비스도 마찬가지로 <b>DB 저장을 절대 하지 않으며</b>
 *       좌표(폴리곤/마스크)만 반환하고, 포털 사용자의 저장은 별도 {@code LS_PORTAL_USER_LABEL}(단방향)로만
 *       이뤄진다.</li>
 * </ul>
 *
 * <p><b>보안</b>:
 * <ul>
 *   <li>채널 격리: 컨트롤러 {@code @PreAuthorize(PORTAL_USER)} + SecurityConfig {@code /v1/portal/**}
 *       (CHANNEL_PORTAL) 로 내부 토큰 진입 물리 차단.</li>
 *   <li>IDOR (CWE-639): srcSn 이 데이터마트 노출(검수 완료=APPROVED) 영상 소속인지 서버 재검증.
 *       클라이언트 신뢰 금지 — 비APPROVED/비존재 srcSn 은 403/404.</li>
 *   <li>자원 격리 (HIGH #4): 포털 전용 {@code portalSam2} Bulkhead 로 동시 호출 제한 — 초과 시 429.
 *       내부 aiOnline 경로와 격리되어 외부 트래픽이 내부 AI 자원을 잠식하지 못한다.</li>
 *   <li>입력 검증 (CWE-20): ai-server 응답 폴리곤 좌표 음수/형식 차단 (외부 응답 불신).</li>
 *   <li>Info Leak (CWE-209): 예외 원문·내부 경로 비노출(LogSanitizer + 일반화 메시지).</li>
 * </ul>
 */
@Slf4j
@Service
public class PortalSam2Service {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final FrameImageEncoder frameImageEncoder;
    /** 포털 SAM2 경로 전용 동시 호출 제한 (HIGH #4) — 내부 aiOnline 과 격리. */
    private final Bulkhead portalSam2Bulkhead;
    /**
     * 이슈3 (CWE-770) — 포털 SAM2 사용자별 요청량 제한 레지스트리. per-user(portalUserNo) 이름으로
     * {@code portalSam2} config 를 공유하는 RateLimiter 를 lazy 생성 — 한 사용자가 track/segment 를
     * 연속 폭주시켜 Tomcat 스레드를 장시간 점유(slowloris)하지 못하게 한다. 초과 시 429.
     */
    private final RateLimiterRegistry portalRateLimiterRegistry;

    /** 이슈3 — RateLimiter config 이름 (application.yml resilience4j.ratelimiter.configs 키와 일치). */
    private static final String PORTAL_SAM2_RL_CONFIG = "portalSam2";

    /**
     * 이슈3 — track 루프 전체 wall-clock 예산. 초과 시 조기 종료(429) — 최대 50프레임 × 블록 호출이
     * 스레드를 무한정 점유하는 것을 방지한다(per-frame block timeout 70s 와 별개의 총량 상한).
     */
    private static final Duration TRACK_WALL_CLOCK_BUDGET = Duration.ofSeconds(60);

    public PortalSam2Service(AiServerClient aiServerClient,
                             LsDataSrcRepository srcRepository,
                             LsRawDataStatusRepository rawDataStatusRepository,
                             FrameImageEncoder frameImageEncoder,
                             @Qualifier("portalSam2Bulkhead") Bulkhead portalSam2Bulkhead,
                             RateLimiterRegistry portalRateLimiterRegistry) {
        this.aiServerClient = aiServerClient;
        this.srcRepository = srcRepository;
        this.rawDataStatusRepository = rawDataStatusRepository;
        this.frameImageEncoder = frameImageEncoder;
        this.portalSam2Bulkhead = portalSam2Bulkhead;
        this.portalRateLimiterRegistry = portalRateLimiterRegistry;
    }

    /**
     * 포털 SAM2 클릭/박스 분할 — 좌표만 반환(persist 없음).
     *
     * @throws CustomException FORBIDDEN(비APPROVED 영상), NOT_FOUND(프레임 없음),
     *                         TOO_MANY_REQUESTS(bulkhead 초과), EXTERNAL_API_ERROR(ai 실패/빈 응답)
     */
    public Sam2SegmentResponse segment(Sam2SegmentRequest req, TokenClaims actor) {
        requireActor(actor);
        acquireUserPermit(actor);
        LsDataSrc src = requireExposedFrame(req.srcSn());

        String imageB64 = frameImageEncoder.encodeToBase64(src.getSrcFilePathNm());
        Sam2Request aiReq = new Sam2Request(imageB64, req.points(), req.box());

        Sam2Response aiRes = callWithBulkhead(
                aiServerClient.segment(aiReq), "segment", req.srcSn());
        if (aiRes == null || aiRes.polygon() == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 segment 응답이 비어있습니다.");
        }
        // mock 안전장치(내부 경로와 동일): 내부 mock 응답은 빈 폴리곤으로 반환 → FE 자동적용 차단.
        // 컨트롤러가 ApiResponse.message 에 안내를 세팅한다.
        if (aiRes.mock()) {
            log.warn("[Portal][Sam2Segment] mock response — return empty srcSn={}", req.srcSn());
            return Sam2SegmentResponse.empty();
        }
        validatePolygon(aiRes.polygon());

        log.info("[Portal][Sam2Segment] srcSn={} points={}",
                req.srcSn(), aiRes.polygon().size());
        return new Sam2SegmentResponse(aiRes.polygon(), aiRes.score());
    }

    /**
     * 포털 SAM2 자동 추적 — 후속 프레임 폴리곤을 좌표로만 반환(persist 없음).
     *
     * <p>내부 {@link kr.co.cudo.authoring.label.service.Sam2TrackService#track} 과 동일하게
     * DB 저장을 하지 않고 좌표만 반환한다(Phase 3 — 내부·포털 모두 미저장, LS_DATA_LBL 불변).
     */
    public Sam2TrackResponseDto track(Sam2TrackRequest req, TokenClaims actor) {
        requireActor(actor);
        acquireUserPermit(actor);
        // 시작 프레임 IDOR 재검증(APPROVED 소속).
        LsDataSrc startSrc = requireExposedFrame(req.srcSn());
        validatePolygon(req.prevPolygon());

        List<Sam2TrackResponseDto.TrackedItem> tracked = new ArrayList<>();
        List<List<Double>> currentPolygon = req.prevPolygon();
        String prevImageB64 = frameImageEncoder.encodeToBase64(startSrc.getSrcFilePathNm());

        // 이슈3 (CWE-770) — track 루프 전체 wall-clock 예산. 초과 시 조기 종료(429).
        long deadlineNanos = System.nanoTime() + TRACK_WALL_CLOCK_BUDGET.toNanos();

        for (Long nextSrcSn : req.nextSrcSns()) {
            if (System.nanoTime() > deadlineNanos) {
                log.warn("[Portal][Sam2Track] wall-clock budget exceeded — abort processed={}/{}",
                        tracked.size(), req.nextSrcSns().size());
                throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                        "SAM2 추적 처리 시간이 초과되었습니다. 프레임 수를 줄여 다시 시도해 주세요.");
            }
            // 후속 프레임 각각도 APPROVED 소속 재검증(IDOR).
            LsDataSrc nextSrc = requireExposedFrame(nextSrcSn);
            String nextImageB64 = frameImageEncoder.encodeToBase64(nextSrc.getSrcFilePathNm());

            kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest aiReq =
                    new kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest(
                            req.trackId(), prevImageB64, nextImageB64, currentPolygon);

            var aiRes = callWithBulkhead(aiServerClient.track(aiReq), "track", nextSrcSn);
            if (aiRes == null || aiRes.polygon() == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 track 응답이 비어있습니다.");
            }
            validatePolygon(aiRes.polygon());

            // ★ persist 없음 — 좌표만 누적(내부 LS_DATA_LBL 불변).
            tracked.add(new Sam2TrackResponseDto.TrackedItem(
                    nextSrcSn, aiRes.trackId(), req.label(), aiRes.polygon(), aiRes.score()));

            currentPolygon = aiRes.polygon();
            prevImageB64 = nextImageB64;
        }
        // CWE-117 — trackId 는 클라이언트 원문(CRLF 삽입 가능)이므로 로그 출력 전 정제(같은 서비스 ai-오류 경로와 정합).
        log.info("[Portal][Sam2Track] trackId={} startSrc={} count={} (no persist)",
                LogSanitizer.sanitize(req.trackId()), req.srcSn(), tracked.size());
        return new Sam2TrackResponseDto(tracked);
    }

    /**
     * ai-server 블로킹 호출을 포털 전용 Bulkhead permit 하에서 수행한다(HIGH #4).
     * 초과 시 429, 그 외 실패는 502(내부 원문 미노출).
     */
    private <T> T callWithBulkhead(reactor.core.publisher.Mono<T> call, String op, Long srcSn) {
        try {
            return call
                    .transformDeferred(BulkheadOperator.of(portalSam2Bulkhead))
                    .block(Duration.ofSeconds(70));
        } catch (BulkheadFullException e) {
            log.warn("[Portal][Sam2] bulkhead full — reject op={} srcSn={}", op, srcSn);
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "SAM2 동시 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("[Portal][Sam2] ai-server 호출 실패 op={} srcSn={} err={}",
                    op, srcSn, LogSanitizer.sanitize(e.getMessage()));
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 " + op + " 호출에 실패했습니다.");
        }
    }

    /**
     * IDOR 재검증 — srcSn 프레임이 존재하고 그 영상이 데이터마트 노출(APPROVED)인지 확인.
     * 비존재 → 404, 비APPROVED → 403.
     */
    private LsDataSrc requireExposedFrame(Long srcSn) {
        LsDataSrc src = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        if (!isDatamartExposed(src.getRawSn())) {
            log.warn("[Portal][Sam2] denied — video not approved srcSn={} rawSn={}", srcSn, src.getRawSn());
            throw new CustomException(ErrorCode.FORBIDDEN, "데이터마트에 노출되지 않은 영상입니다.");
        }
        return src;
    }

    /** 데이터마트 노출 조건 — 검수 완료(APPROVED) 영상만 true. row 부재/타 상태는 false(fail-closed). */
    private boolean isDatamartExposed(Long rawSn) {
        return rawDataStatusRepository.findById(rawSn)
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }

    /** 좌표 검증 (CWE-20) — 각 원소 [x, y] 이고 0 이상. 외부(ai) 응답도 불신. */
    private void validatePolygon(List<List<Double>> polygon) {
        if (polygon == null || polygon.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "폴리곤 좌표가 비어있습니다.");
        }
        for (List<Double> pair : polygon) {
            if (pair == null || pair.size() != 2) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "폴리곤 좌표는 [x, y] 두 값이어야 합니다.");
            }
            Double x = pair.get(0);
            Double y = pair.get(1);
            // Phase 9 이슈4 — NaN/Infinity 거부 (Phase3 autolabel Double.isFinite 패턴). 외부(ai) 응답도 불신.
            if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "폴리곤 좌표는 유한한 숫자여야 합니다.");
            }
            if (x < 0 || y < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "폴리곤 좌표는 0 이상이어야 합니다.");
            }
        }
    }

    private void requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }

    /**
     * 이슈3 (CWE-770) — 사용자별(portalUserNo) 요청량 제한. per-user 이름으로 {@code portalSam2} config 를
     * 공유하는 RateLimiter permit 을 즉시(대기 없음) 획득한다. 획득 실패 시 429 — 한 사용자의 폭주가
     * 포털 SAM2 자원을 잠식하지 못하게 격리한다. 내부 aiOnline 경로와 무관(포털 전용).
     */
    private void acquireUserPermit(TokenClaims actor) {
        RateLimiter limiter =
                portalRateLimiterRegistry.rateLimiter("portalSam2-" + actor.sub(), PORTAL_SAM2_RL_CONFIG);
        if (!limiter.acquirePermission()) {
            log.warn("[Portal][Sam2] rate limit exceeded user={}", LogSanitizer.sanitize(actor.sub()));
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "SAM2 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
