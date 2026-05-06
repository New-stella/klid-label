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
import kr.co.cudo.authoring.common.util.Point;
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
    private final ObjectMapper objectMapper;

    public Sam2TrackResponseDto track(Sam2TrackRequest req, TokenClaims actor) {
        // IDOR 차단: 시작 프레임에 대한 접근 권한 검증 (LabelService 와 동일 규칙).
        accessGuard.verifyAccess(req.srcSn(), actor);
        // 입력 좌표 검증 (CWE-20).
        validatePolygon(req.prevPolygon(), "prevPolygon");

        // 시작 프레임 / 후속 프레임 모두 존재 검증.
        LsDataSrc startSrc = srcRepository.findById(req.srcSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "시작 프레임을 찾을 수 없습니다."));

        List<Sam2TrackResponseDto.TrackedItem> tracked = new ArrayList<>();
        List<List<Double>> currentPolygon = req.prevPolygon();

        for (Long nextSrcSn : req.nextSrcSns()) {
            // IDOR 차단: 후속 프레임 각각에 대해서도 권한 검증.
            accessGuard.verifyAccess(nextSrcSn, actor);

            LsDataSrc nextSrc = srcRepository.findById(nextSrcSn)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "후속 프레임을 찾을 수 없습니다: " + nextSrcSn));

            kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest aiReq =
                    new kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest(
                            req.trackId(),
                            "", // 이미지 base64 — 본 Phase 에서는 ai-server 가 file_path 로 로드 (placeholder)
                            "",
                            currentPolygon
                    );

            Sam2TrackResponse aiRes;
            try {
                aiRes = aiServerClient.track(aiReq).block();
            } catch (Exception e) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "SAM2 track 호출 실패: " + e.getMessage());
            }
            if (aiRes == null || aiRes.polygon() == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 track 응답이 비어있습니다.");
            }
            // 외부 시스템 응답도 신뢰하지 않음 — 동일 좌표 검증 후 저장.
            validatePolygon(aiRes.polygon(), "ai-server polygon");

            // DB 저장: POLYGON + AUTO_LBL_YN='Y' + confScore=ai 응답.
            List<Point> nextPoints = new ArrayList<>(aiRes.polygon().size());
            for (List<Double> p : aiRes.polygon()) {
                nextPoints.add(new Point(p.get(0), p.get(1)));
            }
            String pointsJson = LabelPointSerializer.toJson(nextPoints, objectMapper);
            BigDecimal score = clampScore(aiRes.score());
            labelRepository.save(LsDataLbl.createAutoPolygon(nextSrcSn, req.label(), pointsJson, score));

            tracked.add(new Sam2TrackResponseDto.TrackedItem(
                    nextSrcSn, aiRes.trackId(), req.label(), aiRes.polygon(), aiRes.score()));

            // 다음 루프의 prev → 이번 응답.
            currentPolygon = aiRes.polygon();
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

    private BigDecimal clampScore(double raw) {
        if (Double.isNaN(raw)) return null;
        double clamped = Math.max(0.0, Math.min(1.0, raw));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP);
    }
}
