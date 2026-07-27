package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.common.util.PolygonSimplifier;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6 / R12 — SAM2 트랙 서비스 (<b>DB 미저장 — 좌표만 반환</b>).
 *
 * <p><b>미저장 전환(사용자 확정)</b>: 이전에는 propagation 결과를 쓰기 트랜잭션 내 {@code labelRepository.save}
 * 로 즉시 저장했으나, AI 탐지({@link AutolabelOnlineService})·포털 추적({@link kr.co.cudo.authoring.portal.service.PortalSam2Service})
 * 과 동일하게 <b>좌표(draft)만 반환</b>하도록 통일한다. 클라이언트가 작업본에 병합 후 PUT /labels 로 확정한다.
 * 트랙 병합({@code TrackMergeService})·보간({@code TrackInterpolator})은 이미 저장된 라벨을 대상으로 하므로
 * 본 미저장 전환과 무관하다(회귀 없음).
 *
 * <p><b>비트랜잭셔널(F-1 커넥션풀 고갈 방지)</b>: DB write 가 없으므로 {@code @Transactional} 을 제거했다.
 * AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li>IDOR(CWE-639): 시작 프레임 + 모든 후속 프레임에 대해 {@link LabelAccessGuard#verifyAccess} 검증(AI 호출 전).</li>
 *   <li>좌표 검증(CWE-20): 요청 prevPolygon 및 ai-server 응답 polygon 둘 다 음수/형식 차단.</li>
 *   <li>정보노출(CWE-209): 예외 원문·내부 경로 비노출(LogSanitizer + 일반화 메시지).</li>
 * </ul>
 *
 * <p>형태(R12): {@code shape=POLYGON}(기본) 이면 폴리곤을 그대로, {@code shape=BBOX} 면 폴리곤의 외접 bbox
 * ([[minX,minY],[maxX,maxY]]) 를 산출해 반환한다. 퇴화 폴리곤(폭/높이 &lt; 1px)은 해당 프레임만 스킵(전체 추적 미중단).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Sam2TrackService {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LabelAccessGuard accessGuard;
    private final SystemConfigService systemConfigService;
    private final FrameImageEncoder frameImageEncoder;

    /** POLYGON_SIMPLIFY_TOLERANCE 조회 실패 시 폴백 epsilon(px). */
    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 1.0;

    /** 외접 bbox 퇴화 판정 최소 폭/높이(px) — 미만이면 해당 프레임 스킵. */
    private static final double MIN_BBOX_EXTENT = 1.0;

    public Sam2TrackResponseDto track(Sam2TrackRequest req, TokenClaims actor) {
        // IDOR 차단: 시작 프레임에 대한 접근 권한 검증 (LabelService 와 동일 규칙).
        accessGuard.verifyAccess(req.srcSn(), actor);
        // 입력 좌표 검증 (CWE-20).
        validatePolygon(req.prevPolygon(), "prevPolygon");

        AutolabelShape shape = req.shapeOrDefault();

        // 시작 프레임 존재 검증.
        LsDataSrc startSrc = srcRepository.findById(req.srcSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "시작 프레임을 찾을 수 없습니다."));

        // FEAT-007: 경계 세밀함 — SAM2 응답 폴리곤을 sysconfig epsilon 으로 단순화(Douglas-Peucker).
        double simplifyTolerance = readSimplifyTolerance();

        List<Sam2TrackResponseDto.TrackedItem> tracked = new ArrayList<>();
        List<List<Double>> currentPolygon = req.prevPolygon();

        // 시작 프레임 이미지를 prev 로 사용.
        String prevImageB64 = frameImageEncoder.encodeFrame(startSrc);

        for (Long nextSrcSn : req.nextSrcSns()) {
            // IDOR 차단: 후속 프레임 각각에 대해서도 권한 검증.
            accessGuard.verifyAccess(nextSrcSn, actor);

            LsDataSrc nextSrc = srcRepository.findById(nextSrcSn)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "후속 프레임을 찾을 수 없습니다: " + nextSrcSn));

            String nextImageB64 = frameImageEncoder.encodeFrame(nextSrc);

            kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest aiReq =
                    new kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest(
                            req.trackId(),
                            prevImageB64,
                            nextImageB64,
                            currentPolygon
                    );

            Sam2TrackResponse aiRes;
            try {
                aiRes = aiServerClient.track(aiReq).block();
            } catch (Exception e) {
                // CWE-209: 예외 원문·내부 경로를 클라이언트에 노출하지 않음. 진단 정보는 서버 로그로만.
                log.error("[Sam2Track] ai-server 호출 실패 nextSrcSn={} err={}",
                        nextSrcSn, LogSanitizer.sanitize(e.getMessage()));
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "SAM2 track 호출에 실패했습니다.", e);
            }
            if (aiRes == null || aiRes.polygon() == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 track 응답이 비어있습니다.");
            }
            // 외부 시스템 응답도 신뢰하지 않음 — 동일 좌표 검증.
            validatePolygon(aiRes.polygon(), "ai-server polygon");

            // FEAT-007: 경계 세밀함 적용 — epsilon 으로 폴리곤 점 감소(형태 보존).
            List<List<Double>> simplifiedPolygon = simplify(aiRes.polygon(), simplifyTolerance);

            // 다음 루프의 prev → 이번(단순화 전) 응답. (전파 연속성은 원본 응답 폴리곤으로 유지)
            currentPolygon = aiRes.polygon();
            prevImageB64 = nextImageB64;

            if (shape == AutolabelShape.BBOX) {
                // R12: 외접 bbox 산출 — 퇴화(폭/높이 < 1px)면 해당 프레임만 스킵(전체 추적 미중단).
                List<List<Double>> bbox = toCircumscribedBbox(simplifiedPolygon);
                if (bbox == null) {
                    log.warn("[Sam2Track] degenerate bbox skipped nextSrcSn={}", nextSrcSn);
                    continue;
                }
                tracked.add(new Sam2TrackResponseDto.TrackedItem(
                        nextSrcSn, aiRes.trackId(), req.label(), bbox, aiRes.score(),
                        AutolabelShape.BBOX.name()));
            } else {
                tracked.add(new Sam2TrackResponseDto.TrackedItem(
                        nextSrcSn, aiRes.trackId(), req.label(), simplifiedPolygon, aiRes.score(),
                        AutolabelShape.POLYGON.name()));
            }
        }
        // CWE-117 — trackId 는 클라이언트 원문(CRLF 삽입 가능)이므로 로그 출력 전 정제.
        log.info("[Sam2Track] propagated trackId={} startSrc={} shape={} count={} (no persist)",
                LogSanitizer.sanitize(req.trackId()), startSrc.getSrcSn(), shape, tracked.size());
        return new Sam2TrackResponseDto(tracked);
    }

    /** epsilon 으로 폴리곤 단순화. 3점 미만으로 줄면 원본 유지(형태 보존). */
    private List<List<Double>> simplify(List<List<Double>> polygon, double tolerance) {
        List<Point> rawPoints = new ArrayList<>(polygon.size());
        for (List<Double> p : polygon) {
            rawPoints.add(new Point(p.get(0), p.get(1)));
        }
        List<Point> simplified = PolygonSimplifier.simplify(rawPoints, tolerance);
        if (simplified.size() < 3) {
            return polygon;
        }
        List<List<Double>> out = new ArrayList<>(simplified.size());
        for (Point p : simplified) {
            out.add(List.of(p.x(), p.y()));
        }
        return out;
    }

    /**
     * 폴리곤 외접 bbox([[minX,minY],[maxX,maxY]]) 산출(R12).
     * 폭/높이가 {@link #MIN_BBOX_EXTENT} 미만인 퇴화 폴리곤은 {@code null} (호출자가 프레임 스킵).
     */
    private List<List<Double>> toCircumscribedBbox(List<List<Double>> polygon) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (List<Double> p : polygon) {
            double x = p.get(0), y = p.get(1);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if ((maxX - minX) < MIN_BBOX_EXTENT || (maxY - minY) < MIN_BBOX_EXTENT) {
            return null;
        }
        return List.of(List.of(minX, minY), List.of(maxX, maxY));
    }

    /** 좌표 검증 — 각 원소가 [x, y] 두 개이고 모두 0 이상인지. CWE-20. */
    private void validatePolygon(List<List<Double>> polygon, String fieldName) {
        if (polygon == null || polygon.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, fieldName + " 가 비어있습니다.");
        }
        for (List<Double> pair : polygon) {
            if (pair == null || pair.size() != 2) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        fieldName + " 좌표는 [x, y] 두 값이어야 합니다.");
            }
            Double x = pair.get(0);
            Double y = pair.get(1);
            if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        fieldName + " 좌표는 유한한 0 이상의 수여야 합니다.");
            }
        }
    }

    /**
     * FEAT-007 경계 세밀함 epsilon 조회. 설정 누락/오류 시 기본값으로 폴백(fail-safe).
     */
    private double readSimplifyTolerance() {
        try {
            Double v = systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);
            return v != null ? v : DEFAULT_SIMPLIFY_TOLERANCE;
        } catch (Exception e) {
            log.warn("[Sam2Track] POLYGON_SIMPLIFY_TOLERANCE 조회 실패 — 기본값 {} 사용", DEFAULT_SIMPLIFY_TOLERANCE);
            return DEFAULT_SIMPLIFY_TOLERANCE;
        }
    }
}
