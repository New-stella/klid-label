package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.common.util.PolygonSimplifier;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6 — SAM2 트랙 서비스.
 *
 * 보안:
 *  - IDOR (CWE-639): 시작 프레임 + 모든 후속 프레임에 대해 LabelAccessGuard 검증.
 *  - 좌표 검증 (CWE-20): 요청 prevPolygon 및 ai-server 응답 polygon 둘 다 음수/형식 차단.
 *
 * 동작:
 *  1) 시작 프레임 폴리곤 + trackId 를 ai-server `/infer/sam2/track` 에 전달.
 *  2) 응답으로 받은 폴리곤을 다음 프레임의 새 라벨로 INSERT (POLYGON, AUTO_LBL_YN='Y').
 *  3) 응답 폴리곤을 다시 prevPolygon 으로 사용하여 그 다음 프레임에 같은 trackId 로 전파.
 *
 * trackId 전파:
 *  - LS_DATA_LBL 에 별도 TRCK_ID 컬럼이 없으므로 본 Phase 에서는 LABEL 만 동일 라벨명으로 묶음.
 *  - 응답 DTO 에는 trackId 를 포함해 호출자가 클라이언트 캔버스에서 세션 동안 유지 가능.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager")
public class Sam2TrackService {

    private final AiServerClient aiServerClient;
    private final LsDataLblRepository labelRepository;
    private final LsDataSrcRepository srcRepository;
    private final LabelAccessGuard accessGuard;
    private final LabelMasterService labelMasterService;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final FrameImageEncoder frameImageEncoder;

    /** POLYGON_SIMPLIFY_TOLERANCE 조회 실패 시 폴백 epsilon(px). */
    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 1.0;

    public Sam2TrackResponseDto track(Sam2TrackRequest req, TokenClaims actor) {
        // IDOR 차단: 시작 프레임에 대한 접근 권한 검증 (LabelService 와 동일 규칙).
        accessGuard.verifyAccess(req.srcSn(), actor);
        // 입력 좌표 검증 (CWE-20).
        validatePolygon(req.prevPolygon(), "prevPolygon");

        // 시작 프레임 / 후속 프레임 모두 존재 검증.
        LsDataSrc startSrc = srcRepository.findById(req.srcSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "시작 프레임을 찾을 수 없습니다."));

        // FEAT-007: 경계 세밀함 — SAM2 응답 폴리곤을 sysconfig epsilon 으로 단순화(Douglas-Peucker).
        double simplifyTolerance = readSimplifyTolerance();

        List<Sam2TrackResponseDto.TrackedItem> tracked = new ArrayList<>();
        List<List<Double>> currentPolygon = req.prevPolygon();

        // 시작 프레임 이미지를 prev 로 사용.
        String prevImageB64 = frameImageEncoder.encodeToBase64(startSrc.getSrcFilePathNm());

        for (Long nextSrcSn : req.nextSrcSns()) {
            // IDOR 차단: 후속 프레임 각각에 대해서도 권한 검증.
            accessGuard.verifyAccess(nextSrcSn, actor);

            LsDataSrc nextSrc = srcRepository.findById(nextSrcSn)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "후속 프레임을 찾을 수 없습니다: " + nextSrcSn));

            String nextImageB64 = frameImageEncoder.encodeToBase64(nextSrc.getSrcFilePathNm());

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
            // 외부 시스템 응답도 신뢰하지 않음 — 동일 좌표 검증 후 저장.
            validatePolygon(aiRes.polygon(), "ai-server polygon");

            // DB 저장: POLYGON + AUTO_LBL_YN='Y' + confScore=ai 응답.
            List<Point> rawPoints = new ArrayList<>(aiRes.polygon().size());
            for (List<Double> p : aiRes.polygon()) {
                rawPoints.add(new Point(p.get(0), p.get(1)));
            }
            // FEAT-007: 경계 세밀함 적용 — epsilon 으로 폴리곤 점 감소(형태 보존).
            List<Point> nextPoints = PolygonSimplifier.simplify(rawPoints, simplifyTolerance);
            String pointsJson = LabelPointSerializer.toJson(nextPoints, objectMapper);
            BigDecimal score = clampScore(aiRes.score());
            // Phase 6: 요청 라벨명을 LS_LABEL 마스터 PK 로 매핑 (미매칭 시 null).
            Long labelId = labelMasterService.findLabelIdByName(req.label()).orElse(null);
            log.info("[Batch][Sam2Track] mapped label name={} labelId={}",
                    LogSanitizer.sanitize(req.label()), labelId);
            labelRepository.save(LsDataLbl.createAutoPolygon(nextSrcSn, labelId, req.label(), pointsJson, score));

            tracked.add(new Sam2TrackResponseDto.TrackedItem(
                    nextSrcSn, aiRes.trackId(), req.label(), aiRes.polygon(), aiRes.score()));

            // 다음 루프의 prev → 이번 응답.
            currentPolygon = aiRes.polygon();
            prevImageB64 = nextImageB64;
        }
        log.info("[Sam2Track] propagated trackId={} startSrc={} count={}",
                req.trackId(), startSrc.getSrcSn(), tracked.size());
        return new Sam2TrackResponseDto(tracked);
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
            if (x == null || y == null || x < 0 || y < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        fieldName + " 좌표는 0 이상이어야 합니다 (x=" + x + ", y=" + y + ")");
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

    private BigDecimal clampScore(double raw) {
        if (Double.isNaN(raw)) return null;
        double clamped = Math.max(0.0, Math.min(1.0, raw));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }
}
