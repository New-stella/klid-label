package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.common.util.PolygonSimplifier;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.label.dto.Sam2SegmentRequest;
import kr.co.cudo.authoring.label.dto.Sam2SegmentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Phase 4 — SAM2 클릭/박스 분할 프록시 서비스 (RQ-SFR-08-02).
 *
 * <p>사용자가 캔버스에서 클릭(포인트) 또는 드래그(박스)로 객체를 지목하면 본 서비스가 ai-server
 * {@code POST /infer/sam2/segment} 로 프록시하여 폴리곤 + 신뢰도를 반환한다. 결과는 FE 에서
 * 라벨 폴리곤으로 적용한다 (BE 는 DB 저장하지 않는 stateless 프록시 — 저장은 라벨 임시저장 흐름).
 *
 * <p>이미지 소스 정책: CLAUDE.md "오토라벨링" 규칙에 따라 SAM2 는 <b>원본 이미지에만 실행</b>한다.
 * 원본과 비식별본은 동일 해상도이므로 좌표를 공유하며, 별도 비식별 추론은 수행하지 않는다.
 * (기존 {@link Sam2TrackService} 와 동일하게 {@code srcFilePathNm} = 원본 프레임 경로 사용.)
 *
 * <p>보안:
 * <ul>
 *   <li>IDOR(CWE-639): 진입 시 {@link LabelAccessGuard#verifyAccess} 로 본인 배정 프레임만 허용.</li>
 *   <li>PII 유출(CWE-359): 이 영상이 비식별 누락 신고 구간이면
 *       {@link FrameImageEncoder#resolveFrameImageForInference} 가 412 로 끊는다 — 마스킹 실패 픽셀이
 *       base64 로 ai-server 에 전송되지 않도록 <b>파일을 읽기 전</b>에 차단한다.</li>
 *   <li>경로 순회(CWE-22): 이 서비스는 경로를 스스로 조립하지 않는다 —
 *       {@link FrameImageEncoder#resolveFrameImageForInference} 에 위임하며 기준 디렉토리 외부 접근
 *       차단은 그쪽의 {@code resolveSafe} 가 담당한다(판정 지점 단일화 — 여기에 사본을 두면
 *       정책 갱신 때 조용히 뒤처진다).</li>
 *   <li>입력 검증(CWE-20): points/box 배타(@AssertTrue DTO), 응답 폴리곤 좌표를 이미지 실측
 *       width/height 상한까지 검증.</li>
 *   <li>리소스 제한(API4): 이미지 파일 크기 상한(설정값) 초과 시 413.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class Sam2SegmentService {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LabelAccessGuard accessGuard;
    private final FrameImageEncoder frameImageEncoder;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    /** POLYGON_SIMPLIFY_TOLERANCE 조회 실패 시 폴백 epsilon(px). */
    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 1.0;

    /** 폴리곤 최소 정점 수 (폐곡선). */
    private static final int MIN_POLYGON_POINTS = 3;

    /** box 프롬프트 좌표 개수 — [x1, y1, x2, y2]. */
    private static final int BOX_COORD_COUNT = 4;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /** b64 인코딩 전 이미지 파일 크기 상한 (CWE-770/API4). 기본 20MB. */
    @Value("${authoring.sam2.max-image-bytes:20971520}")
    private long maxImageBytes;

    public Sam2SegmentResponse segment(Sam2SegmentRequest req, TokenClaims actor) {
        // IDOR 차단: 대상 프레임 접근 권한 검증 (LabelService 와 동일 규칙).
        accessGuard.verifyAccess(req.srcSn(), actor);
        // 입력 좌표 검증 (CWE-20) — Sam2TrackService 와 동일 규칙(Sam2CoordinateValidator 공유).
        // 전송 *전* 에 막아야 클라이언트 입력 오류가 ai-server 400 → BE 502 로 승격되지 않는다.
        // points/box 는 DTO @AssertTrue 로 배타 보장되므로 존재하는 쪽만 검증한다.
        if (req.points() != null && !req.points().isEmpty()) {
            Sam2CoordinateValidator.validatePolygon(req.points(), "points");
        }
        if (req.box() != null && !req.box().isEmpty()) {
            Sam2CoordinateValidator.validateCoords(req.box(), BOX_COORD_COUNT, "box");
        }

        LsDataSrc src = srcRepository.findById(req.srcSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));

        // M-6 — 비식별 우선 폴백(출처 컬럼에 맞는 base 로 검증). 해상도 파생 프레임은 원본 픽셀이
        //       실재하지 않아 SRC_FILE_PATH_NM 이 null 이므로, 원본 컬럼만 보면 파생 프레임 분할이
        //       "이미지 경로가 비어있습니다"(400)로 전면 실패한다.
        // S7 (CWE-359) — 외부 전송 단일 진입점. 이 영상이 비식별 누락 신고 구간이면 파일을 읽기도
        //       전에 412 로 끝난다(ai-server 호출 0건).
        Path imagePath = frameImageEncoder.resolveFrameImageForInference(src);
        // 이미지 크기 상한 검증 (b64 인코딩 전).
        long size = fileSize(imagePath);
        if (size > maxImageBytes) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "이미지 크기가 허용 상한을 초과했습니다.");
        }
        // 응답 좌표 상한 검증을 위해 이미지 실측 해상도 확보.
        int[] dim = readImageSize(imagePath);
        int imgWidth = dim[0];
        int imgHeight = dim[1];

        String imageB64 = encodeImageToBase64(imagePath);

        Sam2Request aiReq = new Sam2Request(imageB64, req.points(), req.box());

        Sam2Response aiRes;
        try {
            aiRes = aiServerClient.segment(aiReq).block();
        } catch (Exception e) {
            // CWE-209: 예외 원문(WebClientResponseException 은 내부 호스트:포트:경로를 포함)을
            // 클라이언트에 노출하지 않는다. 진단 정보는 서버 로그로만 — Sam2TrackService 와 동일 규약.
            log.error("[Sam2Segment] ai-server 호출 실패 srcSn={} err={}",
                    req.srcSn(), LogSanitizer.sanitize(e.getMessage()));
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "SAM2 분할 호출에 실패했습니다.", e);
        }
        if (aiRes == null || aiRes.polygon() == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 segment 응답이 비어있습니다.");
        }
        // mock 안전장치: 내부 mock 응답(모델 미로드)은 좌표를 신뢰할 수 없으므로 빈 폴리곤으로 반환한다.
        // FE 는 빈 결과를 받으면 자동 적용할 대상이 없어 차단되고, 컨트롤러가 안내 message 를 세팅한다.
        // 판정은 긍정 증명 기반(untrusted) — mock 메타 생략 응답도 신뢰하지 않는다(AiMockMeta).
        if (aiRes.untrusted()) {
            log.warn("[Sam2Segment] untrusted response — return empty srcSn={} source={}",
                    req.srcSn(), LogSanitizer.sanitize(aiRes.source()));
            return Sam2SegmentResponse.empty();
        }
        // 외부 응답 신뢰 금지 — 정점 수 + 좌표 상한 검증 (CWE-20).
        validatePolygon(aiRes.polygon(), imgWidth, imgHeight);

        // FEAT-007: 경계 세밀함 — 요청 override 우선, 없으면 sysconfig→상수 폴백(무회귀). Douglas-Peucker 단순화.
        double simplifyTolerance = req.simplifyTolerance() != null
                ? req.simplifyTolerance()
                : readSimplifyTolerance();
        List<Point> rawPoints = new ArrayList<>(aiRes.polygon().size());
        for (List<Double> p : aiRes.polygon()) {
            rawPoints.add(new Point(p.get(0), p.get(1)));
        }
        List<Point> simplified = PolygonSimplifier.simplify(rawPoints, simplifyTolerance);
        List<List<Double>> outPolygon = new ArrayList<>(simplified.size());
        for (Point p : simplified) {
            outPolygon.add(List.of(p.x(), p.y()));
        }
        // 단순화 결과가 3점 미만으로 줄어들면 원본 응답 폴리곤을 그대로 사용(형태 보존).
        if (outPolygon.size() < MIN_POLYGON_POINTS) {
            outPolygon = aiRes.polygon();
        }

        double score = clampScore(aiRes.score());
        log.info("[Sam2Segment] segmented srcSn={} points={} score={}",
                req.srcSn(), outPolygon.size(), score);
        return new Sam2SegmentResponse(outPolygon, score);
    }

    /**
     * 좌표 검증 (CWE-20) — 정점 최소 3개, 각 원소 [x, y], 0 이상이며 이미지 경계 이하.
     * 외부(ai-server) 응답 좌표를 신뢰하지 않고 이미지 실측 해상도 상한까지 검증한다.
     */
    private void validatePolygon(List<List<Double>> polygon, int imgWidth, int imgHeight) {
        if (polygon.size() < MIN_POLYGON_POINTS) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "SAM2 응답 폴리곤 정점이 " + MIN_POLYGON_POINTS + "개 미만입니다.");
        }
        for (List<Double> pair : polygon) {
            if (pair == null || pair.size() != 2) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "SAM2 응답 폴리곤 좌표는 [x, y] 두 값이어야 합니다.");
            }
            Double x = pair.get(0);
            Double y = pair.get(1);
            if (x == null || y == null || x < 0 || y < 0 || x > imgWidth || y > imgHeight) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "SAM2 응답 폴리곤 좌표가 이미지 경계를 벗어났습니다.");
            }
        }
    }

    private long fileSize(Path imagePath) {
        try {
            return Files.size(imagePath);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 크기 확인 실패: " + e.getMessage());
        }
    }

    /** 이미지 실측 해상도 [width, height]. 손상/미지원 이미지는 EXTERNAL_API_ERROR 가 아닌 400. */
    private int[] readImageSize(Path imagePath) {
        try {
            BufferedImage img = ImageIO.read(imagePath.toFile());
            if (img == null) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "이미지를 읽을 수 없습니다.");
            }
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기 실패: " + e.getMessage());
        }
    }

    /** 원본 프레임 이미지를 읽어 base64 인코딩. ai-server 입력용. */
    private String encodeImageToBase64(Path imagePath) {
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기 실패: " + e.getMessage());
        }
    }

    /** FEAT-007 경계 세밀함 epsilon 조회. 설정 누락/오류 시 기본값으로 폴백(fail-safe). */
    private double readSimplifyTolerance() {
        try {
            Double v = systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);
            return v != null ? v : DEFAULT_SIMPLIFY_TOLERANCE;
        } catch (Exception e) {
            log.warn("[Sam2Segment] POLYGON_SIMPLIFY_TOLERANCE 조회 실패 — 기본값 {} 사용", DEFAULT_SIMPLIFY_TOLERANCE);
            return DEFAULT_SIMPLIFY_TOLERANCE;
        }
    }

    private double clampScore(double raw) {
        if (Double.isNaN(raw)) return 0.0;
        double clamped = Math.max(0.0, Math.min(1.0, raw));
        return BigDecimal.valueOf(clamped).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
