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
 *   <li>경로 순회(CWE-22): {@link #resolveSafe} 로 기준 디렉토리 외부 접근 차단.</li>
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
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    /** POLYGON_SIMPLIFY_TOLERANCE 조회 실패 시 폴백 epsilon(px). */
    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 1.0;

    /** 폴리곤 최소 정점 수 (폐곡선). */
    private static final int MIN_POLYGON_POINTS = 3;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /** b64 인코딩 전 이미지 파일 크기 상한 (CWE-770/API4). 기본 20MB. */
    @Value("${authoring.sam2.max-image-bytes:20971520}")
    private long maxImageBytes;

    public Sam2SegmentResponse segment(Sam2SegmentRequest req, TokenClaims actor) {
        // IDOR 차단: 대상 프레임 접근 권한 검증 (LabelService 와 동일 규칙).
        accessGuard.verifyAccess(req.srcSn(), actor);

        LsDataSrc src = srcRepository.findById(req.srcSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));

        Path baseDir = Path.of(storageRawPath).toAbsolutePath().normalize();
        // 원본 프레임 이미지(srcFilePathNm) — 정책상 SAM2 는 원본에만 실행.
        Path imagePath = resolveSafe(baseDir, src.getSrcFilePathNm());
        if (!Files.exists(imagePath)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일을 찾을 수 없습니다.");
        }
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
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "SAM2 segment 호출 실패: " + e.getMessage());
        }
        if (aiRes == null || aiRes.polygon() == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 segment 응답이 비어있습니다.");
        }
        // 외부 응답 신뢰 금지 — 정점 수 + 좌표 상한 검증 (CWE-20).
        validatePolygon(aiRes.polygon(), imgWidth, imgHeight);

        // FEAT-007: 경계 세밀함 — sysconfig epsilon 으로 폴리곤 단순화(Douglas-Peucker).
        double simplifyTolerance = readSimplifyTolerance();
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
        log.info("[Sam2Segment] segmented srcSn={} points={} score={} mock={}",
                req.srcSn(), outPolygon.size(), score, aiRes.mock());
        return new Sam2SegmentResponse(outPolygon, score, aiRes.mock());
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

    /** Path Traversal (CWE-22) 방어 — 기준 디렉토리 외부 접근 차단. */
    private Path resolveSafe(Path baseDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 경로가 비어있습니다.");
        }
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 경로입니다.");
        }
        return resolved;
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
