package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalUploadExportResponse;
import kr.co.cudo.authoring.portal.dto.PortalUploadLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUploadLabelResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.entity.LsPortalUldLbl;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldLblRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 4 — 포털 업로드 라벨 CRUD + 내보내기/다운로드 서비스 (PORTAL_USER 전용).
 *
 * <p>ADR-013 준수 — 포털 업로드 자산의 프레임별 수동 라벨 전체교체(PUT)/조회/export/원본 다운로드만
 * 담당한다. AC6: 데이터마트/내부 도메인(batch/video/label) 미참조 — 포털 3종 리포지토리
 * (ULD/FRME/LBL)만 의존한다.
 *
 * <p>HIGH 시나리오 방어:
 * <ol>
 *   <li>#1 동시 PUT 경합: 프레임 행 비관적 락({@code findByUldFrmeSnAndOwnerForUpdate}) 후 동일 tx 에서
 *       벌크 DELETE→saveAll — 병렬 PUT 을 프레임 스코프로 직렬화.</li>
 *   <li>#2 delete+insert 원자성: 입력 검증(타입·좌표 파싱·상한) 100% 를 DELETE 이전에 완료 —
 *       검증 실패는 DELETE 미실행 400. delete+insert 단일 트랜잭션(부분 실패 시 전체 롤백).</li>
 *   <li>#3 배열 상한 DoS: 라벨 수(500)·좌표 수(BBOX=2, POLYGON 3~200)·label 길이(80) 상한.</li>
 *   <li>#4 lblTypeCd allowlist: BBOX|POLYGON 만(그 외 400, fail-closed).</li>
 *   <li>#5 IDOR: 모든 진입을 소유자 스코프 리포지토리 경유 — 부재/타인 = 403(forbidden 통일).</li>
 *   <li>#6 READY 가드: 부모 자산 상태 READY 외 라벨 PUT 409.</li>
 *   <li>#7 Content-Disposition 인젝션(CWE-113): 제어문자 제거 + RFC 5987 filename* + ASCII fallback.</li>
 *   <li>#8 데이터마트 무접촉: 포털 3종 리포지토리만 의존 + 라벨 일괄 조회(N+1 금지).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalUploadLabelService {

    /** 프레임당 라벨 개수 상한(#3 DoS). */
    private static final int MAX_LABELS_PER_FRAME = 500;
    /** BBOX 좌표 개수(정확히 2점 — 좌상단·우하단). */
    private static final int BBOX_POINT_COUNT = 2;
    /** POLYGON 최소/최대 좌표 개수. */
    private static final int POLYGON_MIN_POINTS = 3;
    private static final int POLYGON_MAX_POINTS = 200;
    /** 좌표 원소 크기([x, y]). */
    private static final int POINT_TUPLE_SIZE = 2;
    /** 라벨명 최대 길이(LBL_NM 컬럼 길이와 동일 — #3). */
    private static final int MAX_LABEL_LENGTH = 80;

    private final LsPortalUldRepository uldRepository;
    private final LsPortalUldFrmeRepository frmeRepository;
    private final LsPortalUldLblRepository lblRepository;
    private final PortalUploadProperties properties;
    private final ObjectMapper objectMapper;

    // ======================== 라벨 전체교체(PUT) ========================

    /**
     * 프레임 라벨 전체교체(멱등). 입력 전량을 검증(#2)한 뒤 프레임 비관적 락(#1) → READY 가드(#6) →
     * 벌크 DELETE → saveAll 을 단일 트랜잭션으로 수행한다. 빈 배열은 전체 삭제(유효).
     *
     * @return 저장된 라벨 목록(교체 결과)
     */
    @Transactional("controlTransactionManager")
    public List<PortalUploadLabelResponse> replaceLabels(
            Long uldFrmeSn, String portalUserNo, List<PortalUploadLabelRequest> labels) {
        requireOwner(portalUserNo);
        List<PortalUploadLabelRequest> input = labels == null ? List.of() : labels;

        // #2/#3: DELETE 이전에 전 라벨을 검증하고 저장 JSON 을 미리 만든다(검증 실패 = DELETE 미실행 400).
        if (input.size() > MAX_LABELS_PER_FRAME) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "프레임당 라벨은 최대 " + MAX_LABELS_PER_FRAME + " 개까지 허용됩니다.");
        }
        List<PreparedLabel> prepared = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            prepared.add(validateAndPrepare(i, input.get(i)));
        }

        // #1: 프레임 비관적 락 + 소유자 스코프. 부재/타인 = 403(#5).
        LsPortalUldFrme frame = frmeRepository.findByUldFrmeSnAndOwnerForUpdate(uldFrmeSn, portalUserNo)
                .orElseThrow(this::forbidden);

        // #6: 부모 자산 상태 READY 외 라벨링 금지(409). 소유자 스코프 재확인(#5).
        LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(frame.getUldSn(), portalUserNo)
                .orElseThrow(this::forbidden);
        if (!LsPortalUld.STTS_READY.equals(uld.getUldSttsCd())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "라벨링 가능한(READY) 자산이 아닙니다. 현재 상태: " + uld.getUldSttsCd());
        }

        // 전체교체 — 소유자 스코프 벌크 DELETE 후 saveAll(동일 tx, 부분 실패 시 전체 롤백 #2).
        lblRepository.deleteAllByUldFrmeSnAndPortalUserNo(uldFrmeSn, portalUserNo);
        List<LsPortalUldLbl> toSave = new ArrayList<>(prepared.size());
        for (PreparedLabel p : prepared) {
            toSave.add(LsPortalUldLbl.create(portalUserNo, uld.getUldSn(), uldFrmeSn,
                    p.lblTypeCd(), p.label(), p.pointCn()));
        }
        List<LsPortalUldLbl> saved = lblRepository.saveAll(toSave);

        log.info("[PortalUploadLabel] replaced uldFrmeSn={} userNo={} count={}",
                uldFrmeSn, LogSanitizer.sanitize(portalUserNo), saved.size());
        return saved.stream().map(this::toResponse).toList();
    }

    // ======================== 라벨 조회 ========================

    /** 프레임 라벨 목록(소유자 스코프). 프레임 부재/타인 = 403. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<PortalUploadLabelResponse> listLabels(Long uldFrmeSn, String portalUserNo) {
        requireOwner(portalUserNo);
        frmeRepository.findByUldFrmeSnAndOwner(uldFrmeSn, portalUserNo).orElseThrow(this::forbidden);
        return lblRepository.findAllByUldFrmeSnAndPortalUserNo(uldFrmeSn, portalUserNo).stream()
                .map(this::toResponse).toList();
    }

    // ======================== 내보내기(export) ========================

    /**
     * 자산 export — 자산 메타 + 프레임 + 프레임별 라벨을 자기완결 JSON attachment 로 반환한다.
     * 라벨은 업로드 단위 일괄 조회 후 프레임별 그룹핑으로 N+1 을 회피한다(#8). 파일명은 서버 생성
     * 고정명({@code portal-upload-{uldSn}-labels.json}).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public ResponseEntity<byte[]> exportLabels(Long uldSn, String portalUserNo) {
        requireOwner(portalUserNo);
        LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(uldSn, portalUserNo)
                .orElseThrow(this::forbidden);

        // 소유 uldSn 하위 프레임(순번 오름차순). uld 소유권 확인 후이므로 하위 프레임은 소유자 것.
        List<LsPortalUldFrme> frames = frmeRepository.findAllByUldSnOrderByFrmeNo(uldSn);

        // #8: 업로드 단위 라벨 일괄 조회 후 프레임별 그룹핑(프레임별 N+1 금지).
        Map<Long, List<PortalUploadExportResponse.Label>> labelsByFrame = new LinkedHashMap<>();
        for (LsPortalUldLbl lbl : lblRepository.findAllByUldSnAndPortalUserNo(uldSn, portalUserNo)) {
            labelsByFrame.computeIfAbsent(lbl.getUldFrmeSn(), k -> new ArrayList<>())
                    .add(new PortalUploadExportResponse.Label(
                            lbl.getUldLblSn(), lbl.getLblTypeCd(), lbl.getLblNm(),
                            parsePoints(lbl.getPointCn())));
        }

        List<PortalUploadExportResponse.Frame> frameDtos = frames.stream()
                .map(f -> new PortalUploadExportResponse.Frame(
                        f.getUldFrmeSn(), f.getFrmeNo(),
                        labelsByFrame.getOrDefault(f.getUldFrmeSn(), List.of())))
                .toList();

        PortalUploadExportResponse export = new PortalUploadExportResponse(
                uld.getUldSn(), uld.getUldTypeCd(), uld.getOrgnlFileNm(), uld.getFileSz(),
                uld.getMimeTypeNm(), uld.getUldSttsCd(), uld.getFrmeCnt(), uld.getRegDt(), frameDtos);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(export);
        } catch (IOException e) {
            log.error("[PortalUploadLabel] export serialize failed uldSn={} causeType={}",
                    uldSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "내보내기에 실패했습니다.");
        }

        // 서버 생성 고정명 — 사용자 입력 미포함(인젝션 여지 없음).
        String fixedName = "portal-upload-" + uldSn + "-labels.json";
        log.info("[PortalUploadLabel] exported uldSn={} userNo={} frames={}",
                uldSn, LogSanitizer.sanitize(portalUserNo), frameDtos.size());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fixedName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    // ======================== 원본 파일 다운로드 ========================

    /**
     * 원본 업로드 파일 다운로드(본인만). DB 확정 MIME + nosniff(#6 계열) + Content-Disposition
     * attachment. 원본 파일명은 제어문자 제거 후 RFC 5987 {@code filename*} + ASCII fallback 병기로
     * CRLF 헤더 인젝션(CWE-113)을 차단한다(#7).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public ResponseEntity<Resource> downloadFile(Long uldSn, String portalUserNo) {
        requireOwner(portalUserNo);
        LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(uldSn, portalUserNo)
                .orElseThrow(this::forbidden);

        // 저장 경로 미확정(추출 미완료/이상 자산) — Paths.get(null) NPE 방지, 파일 부재와 동일 취급(404).
        String filePathNm = uld.getFilePathNm();
        if (filePathNm == null || filePathNm.isBlank()) {
            log.warn("[PortalUploadLabel] original file path missing uldSn={}", uldSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "원본 파일이 존재하지 않습니다.");
        }
        Path baseDir = baseDir();
        Path resolved = resolveSafe(baseDir, Paths.get(filePathNm));
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[PortalUploadLabel] original file missing uldSn={}", uldSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "원본 파일이 존재하지 않습니다.");
        }
        long contentLength;
        try {
            contentLength = Files.size(resolved);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "파일을 읽을 수 없습니다.");
        }
        MediaType mediaType = resolveStoredMediaType(uld.getMimeTypeNm());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachmentDisposition(uld.getOrgnlFileNm(), uld.getMimeTypeNm()))
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(resolved));
    }

    // ======================== 내부 헬퍼 ========================

    /** 라벨 1건 검증 + 저장 JSON 준비(#2/#3/#4). 실패 시 400(DELETE 이전에 전량 검증). */
    private PreparedLabel validateAndPrepare(int index, PortalUploadLabelRequest req) {
        if (req == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "[" + (index + 1) + "] 라벨이 비어 있습니다.");
        }
        String type = req.lblTypeCd() == null ? "" : req.lblTypeCd().toUpperCase(Locale.ROOT);
        // #4 allowlist — BBOX|POLYGON 외 거부(fail-closed).
        if (!LsPortalUldLbl.TYPE_BBOX.equals(type) && !LsPortalUldLbl.TYPE_POLYGON.equals(type)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "[" + (index + 1) + "] 허용되지 않는 lblTypeCd 입니다. 허용: BBOX, POLYGON");
        }
        // #3 label 길이 상한 — Javadoc(80자) · 컨트롤러 @Size(80) 와 일치하는 서비스 레벨 강제(DELETE 이전 400).
        if (req.label() != null && req.label().length() > MAX_LABEL_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "[" + (index + 1) + "] label 은 " + MAX_LABEL_LENGTH + "자 이하여야 합니다.");
        }
        List<List<Double>> points = req.points();
        if (points == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "[" + (index + 1) + "] points 는 필수입니다.");
        }
        // #3 좌표 개수 상한 — 타입별.
        if (LsPortalUldLbl.TYPE_BBOX.equals(type) && points.size() != BBOX_POINT_COUNT) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "[" + (index + 1) + "] BBOX 는 정확히 " + BBOX_POINT_COUNT + " 점이어야 합니다.");
        }
        if (LsPortalUldLbl.TYPE_POLYGON.equals(type)
                && (points.size() < POLYGON_MIN_POINTS || points.size() > POLYGON_MAX_POINTS)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "[" + (index + 1) + "] POLYGON 은 " + POLYGON_MIN_POINTS + "~"
                            + POLYGON_MAX_POINTS + " 점이어야 합니다.");
        }
        // 각 좌표 [x, y] 형식·유한성 검증(#2 파싱 상한 — NaN/Infinity 거부).
        List<List<Double>> normalized = new ArrayList<>(points.size());
        for (List<Double> p : points) {
            if (p == null || p.size() != POINT_TUPLE_SIZE || p.get(0) == null || p.get(1) == null) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "[" + (index + 1) + "] 좌표는 [x, y] 형식이어야 합니다.");
            }
            double x = p.get(0);
            double y = p.get(1);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "[" + (index + 1) + "] 좌표는 유한한 숫자여야 합니다.");
            }
            normalized.add(List.of(x, y));
        }
        String pointCn;
        try {
            pointCn = objectMapper.writeValueAsString(normalized);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "[" + (index + 1) + "] 좌표 직렬화에 실패했습니다.");
        }
        return new PreparedLabel(type, req.label(), pointCn);
    }

    private PortalUploadLabelResponse toResponse(LsPortalUldLbl lbl) {
        return PortalUploadLabelResponse.of(lbl, parsePoints(lbl.getPointCn()));
    }

    /** POINT_CN JSON → [[x,y], ...]. 손상 JSON 은 빈 리스트(fail-secure, 렌더 크래시 차단). */
    private List<List<Double>> parsePoints(String pointCn) {
        try {
            return LabelPointSerializer.fromJson(pointCn, objectMapper).stream()
                    .map(p -> List.of(p.x(), p.y()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("[PortalUploadLabel] points parse skipped reason={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * #7 CWE-113 — 원본 파일명 기반 Content-Disposition. 제어문자(CR/LF 등) 제거 후 RFC 5987
     * {@code filename*=UTF-8''<url-encoded>} 로 유니코드 파일명을 안전 전달하고, 구형 클라이언트용
     * ASCII fallback {@code filename="download.{ext}"} 를 병기한다(사용자 입력 미포함 고정명).
     */
    private static String attachmentDisposition(String orgnlFileNm, String mimeTypeNm) {
        String ext = safeExtension(orgnlFileNm, mimeTypeNm);
        String cleaned = sanitizeFileName(orgnlFileNm);
        if (cleaned.isBlank()) {
            cleaned = "download." + ext;
        }
        String encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"download." + ext + "\"; filename*=UTF-8''" + encoded;
    }

    /** 제어문자(CR/LF/탭/NULL 등) · 따옴표 · 역슬래시 · 경로 구분자 제거 — 헤더/경로 인젝션 차단. */
    private static String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '"' || c == '\\' || c == '/') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 파일명 확장자(allowlist 문자만) → 없으면 MIME 매핑 → 그래도 없으면 {@code bin}. */
    private static String safeExtension(String orgnlFileNm, String mimeTypeNm) {
        if (orgnlFileNm != null) {
            int dot = orgnlFileNm.lastIndexOf('.');
            if (dot >= 0 && dot < orgnlFileNm.length() - 1) {
                String ext = orgnlFileNm.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.matches("^[a-z0-9]{1,8}$")) {
                    return ext;
                }
            }
        }
        if (MediaType.IMAGE_JPEG_VALUE.equals(mimeTypeNm)) {
            return "jpg";
        }
        if (MediaType.IMAGE_PNG_VALUE.equals(mimeTypeNm)) {
            return "png";
        }
        return "bin";
    }

    private static MediaType resolveStoredMediaType(String mimeTypeNm) {
        if (MediaType.IMAGE_JPEG_VALUE.equals(mimeTypeNm)) {
            return MediaType.IMAGE_JPEG;
        }
        if (MediaType.IMAGE_PNG_VALUE.equals(mimeTypeNm)) {
            return MediaType.IMAGE_PNG;
        }
        // 알 수 없는 MIME 은 octet-stream + attachment + nosniff 로 서빙(fail-closed).
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private Path baseDir() {
        return Paths.get(properties.storagePath()).toAbsolutePath().normalize();
    }

    /** CWE-22 Path Traversal 가드 — baseDir 외부 경로 거부. */
    private Path resolveSafe(Path baseDir, Path candidate) {
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : baseDir.resolve(candidate).normalize();
        if (!resolved.startsWith(baseDir)) {
            log.warn("[PortalUploadLabel] path traversal blocked");
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 경로입니다.");
        }
        return resolved;
    }

    private void requireOwner(String portalUserNo) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }

    private CustomException forbidden() {
        return new CustomException(ErrorCode.FORBIDDEN, "본인 자산이 아니거나 존재하지 않습니다.");
    }

    /** 검증 통과 라벨 — 확정 타입 + label + 저장 JSON. */
    private record PreparedLabel(String lblTypeCd, String label, String pointCn) {
    }
}
