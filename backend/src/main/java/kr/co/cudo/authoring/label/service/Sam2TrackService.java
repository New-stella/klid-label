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
import kr.co.cudo.authoring.label.dto.Sam2TrackOutcome;
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
 * 로 즉시 저장했으나, AI 탐지({@link AutolabelOnlineService})
 * 와 동일하게 <b>좌표(draft)만 반환</b>하도록 통일한다. 클라이언트가 작업본에 병합 후 PUT /labels 로 확정한다.
 * 트랙 병합({@code TrackMergeService})·보간({@code TrackInterpolator})은 이미 저장된 라벨을 대상으로 하므로
 * 본 미저장 전환과 무관하다(회귀 없음).
 *
 * <p><b>비트랜잭셔널(F-1 커넥션풀 고갈 방지)</b>: DB write 가 없으므로 {@code @Transactional} 을 제거했다.
 * AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li>IDOR(CWE-639): 시작 프레임 + 모든 후속 프레임에 대해 {@link LabelAccessGuard#verifyAccess} 검증(AI 호출 전).</li>
 *   <li>PII 유출(CWE-359): 프레임 이미지 인코딩이 {@link FrameImageEncoder#encodeFrame} 단일 진입점을 거치며,
 *       이 영상이 비식별 누락 신고 구간이면 412 로 끊긴다. 추적은 N 프레임을 연속 전송하므로
 *       <b>프레임마다</b> 판정되어, 추적 도중 신고가 들어와도 그 이후 프레임 픽셀은 나가지 않는다.</li>
 *   <li>좌표 검증(CWE-20): 요청 prevPolygon 및 ai-server 응답 polygon 둘 다 음수/형식 차단(400).
 *       응답 폴리곤은 추가로 <b>최소 정점 수</b>({@link Sam2CoordinateValidator#MIN_POLYGON_POINTS})를
 *       강제하며 위반은 <b>502</b>(외부 시스템이 잘못 준 것) — 요청 축의 400 과 섞지 않는다.</li>
 *   <li>정보노출(CWE-209): 예외 원문·내부 경로 비노출(LogSanitizer + 일반화 메시지).</li>
 *   <li><b>데이터 진정성(CWE-345, C-ISSUE-81)</b>: ai-server mock 응답 프레임은 결과에서 제외한다.
 *       mock track 은 시드 폴리곤 복사본을 {@code score=0.9} 로 돌려주므로 걸러내지 않으면 "N 프레임 추적"이
 *       "시드 N개 복제"로 둔갑해 학습데이터가 오염된다. 제외 사실은 {@link Sam2TrackOutcome} 으로 컨트롤러에
 *       전달되어 안내 메시지가 된다(SAM2 세그·YOLO 오토라벨과 동일 규약).</li>
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

    public Sam2TrackOutcome track(Sam2TrackRequest req, TokenClaims actor) {
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
        // C-ISSUE-81 — mock 응답이 1건이라도 있었는지(안내 메시지 세팅용).
        boolean anyMock = false;

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
            // 외부 시스템 응답도 신뢰하지 않음 — 최소 정점 수(502) + 좌표 형식(400) 검증.
            validateResponsePolygon(aiRes.polygon(), "ai-server polygon");

            // mock 안전장치(C-ISSUE-81, CWE-345) — SAM2 세그/오토라벨과 동일 규약. ai-server 가 mock
            // 응답(모델 미로드·마스크 미검출)을 내면 그 좌표는 시드 폴리곤 복사본에 불과하므로 이 프레임을
            // 결과에서 제외한다(score 가 0.9 라 FE 저신뢰 분기로도 걸러지지 않는다).
            // 전파(currentPolygon)는 이어가되 — mock 폴리곤은 입력 시드와 동일하므로 새로 지어낸 좌표가
            // 유입되지 않는다 — 다음 프레임에서 실모델이 회복할 수 있게 한다.
            // 판정은 긍정 증명 기반(untrusted) — mock 메타 생략 응답도 신뢰하지 않는다(AiMockMeta).
            if (aiRes.untrusted()) {
                anyMock = true;
                log.warn("[Sam2Track] mock response — exclude frame nextSrcSn={} source={} reason={}",
                        nextSrcSn, LogSanitizer.sanitize(aiRes.source()),
                        LogSanitizer.sanitize(aiRes.mockReason()));
                currentPolygon = aiRes.polygon();
                prevImageB64 = nextImageB64;
                continue;
            }

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
        log.info("[Sam2Track] propagated trackId={} startSrc={} shape={} count={} mock={} (no persist)",
                LogSanitizer.sanitize(req.trackId()), startSrc.getSrcSn(), shape, tracked.size(), anyMock);
        return Sam2TrackOutcome.of(new Sam2TrackResponseDto(tracked), anyMock);
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

    /**
     * <b>요청</b> 좌표 검증 — 각 원소가 [x, y] 두 개이고 모두 유한한 0 이상인지. CWE-20 → 400.
     *
     * <p>규칙 본체는 {@link Sam2CoordinateValidator} 로 추출했다 — {@link Sam2SegmentService} 와
     * 동일 규칙을 공유해야 두 경로의 검증이 갈라지지 않는다(과거 segment 경로에 이 검증이 없어
     * 클라이언트 입력 오류가 502 로 승격됐다).
     */
    private void validatePolygon(List<List<Double>> polygon, String fieldName) {
        Sam2CoordinateValidator.validatePolygon(polygon, fieldName);
    }

    /**
     * <b>응답</b> 폴리곤 검증 (CWE-20) — 최소 정점 수(폐곡선) 위반은 <b>502</b>, 좌표 형식 위반은 400.
     *
     * <p>정점 수 위반을 502 로 내는 이유: 클라이언트가 잘못 보낸 것이 아니라 <b>외부 시스템이 잘못
     * 준 것</b>이라 400 은 의미가 틀리다({@link Sam2SegmentService} 의 응답 검증과 동일 규약).
     * 요청 축({@code prevPolygon})은 기존대로 400 이며 두 축을 섞지 않는다.
     *
     * <p>이 검증이 없으면 1~2 점 응답이 {@link #simplify} 를 그대로 통과해(단순화 결과가 3 점
     * 미만이면 원본 유지) 퇴화 폴리곤이 POLYGON 응답에 실린다.
     */
    private void validateResponsePolygon(List<List<Double>> polygon, String fieldName) {
        Sam2CoordinateValidator.validateResponseMinPoints(polygon, fieldName);
        Sam2CoordinateValidator.validatePolygon(polygon, fieldName);
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
