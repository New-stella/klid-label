package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalUploadDetailResponse;
import kr.co.cudo.authoring.portal.dto.PortalUploadFrameResponse;
import kr.co.cudo.authoring.portal.dto.PortalUploadResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import kr.co.cudo.authoring.video.service.FrameImageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * V107 — 포털 전용 이미지 업로드/자산 관리 서비스 (PORTAL_USER).
 *
 * <p>관제 학습용 적재 파이프라인과 분리된 포털 전용 경로. 모든 조회/서빙/삭제는 소유자 스코프
 * 리포지토리 메서드만 사용해 IDOR(CWE-639)를 차단한다.
 *
 * <p>HIGH 시나리오 방어:
 * <ol>
 *   <li>#3 all-or-nothing: 전 파일 사전검증(확장자→크기→매직바이트/정합, 디스크 미기록) 후 하나라도
 *       실패하면 파일별 사유와 함께 400 — 아무것도 저장하지 않는다.</li>
 *   <li>#4 고아 파일 방지: write 후 DB INSERT 실패 시 이번 요청에 기록한 파일을 즉시 보상 삭제.</li>
 *   <li>#5 디스크 고갈: 저장 도중 IOException 시 이번 요청 파일 전부 롤백 삭제 + 표준 에러.</li>
 *   <li>#6 서빙 MIME/XSS: 매직바이트로 확정한 MIME 만 저장·서빙(확장자 추정 금지) + nosniff.</li>
 *   <li>#7 IDOR: 소유자 스코프 리포지토리만 사용 — 타 사용자 자산 접근 403.</li>
 *   <li>#8 삭제 순서: 소유권 검증 → 프레임/원본 파일 명시 삭제 → DB 행 삭제(CASCADE).
 *       파일 삭제 IOException 시 DB 삭제하지 않고 5xx(재시도 가능).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalUploadService {

    /** 이미지 업로드 대표 프레임 순번(단일 프레임). */
    private static final int IMAGE_FRAME_NO = 0;
    private static final String IMAGE_SUBDIR = "images";
    private static final int ORGNL_FILE_NM_MAX = 255;
    /** JPEG EOI 마커(FF D9) 판독용 말미 바이트 수. */
    private static final int JPEG_EOI_BYTES = 2;

    private final LsPortalUldRepository uldRepository;
    private final LsPortalUldFrmeRepository frmeRepository;
    private final PortalUploadProperties properties;

    // ======================== 업로드 ========================

    /**
     * 다중 이미지 업로드. 전 파일 사전검증 통과 시에만 저장+INSERT 하며, 어느 단계든 실패하면
     * 이번 요청에 기록한 파일을 전량 보상 삭제하고 트랜잭션을 롤백한다(all-or-nothing).
     *
     * @return 저장된 자산 응답(파일별 대표 프레임 식별자 포함)
     */
    @Transactional("controlTransactionManager")
    public List<PortalUploadResponse> uploadImages(String portalUserNo, List<MultipartFile> files) {
        requireOwner(portalUserNo);
        // #입력검증: 빈 목록/개수 상한.
        if (files == null || files.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "업로드할 이미지가 없습니다.");
        }
        if (files.size() > properties.maxImagesPerRequest()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "요청당 최대 " + properties.maxImagesPerRequest() + " 개까지 업로드할 수 있습니다.");
        }

        // #3: 디스크에 쓰기 전에 전 파일을 사전검증한다. 하나라도 실패면 파일별 사유와 함께 400.
        List<ValidatedImage> validated = new ArrayList<>(files.size());
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            try {
                validated.add(validate(file));
            } catch (CustomException e) {
                failures.add("[" + (i + 1) + "] " + LogSanitizer.sanitize(displayName(file)) + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "업로드할 수 없는 파일이 포함되어 있습니다.", failures);
        }

        Path baseDir = baseDir();
        Path imagesDir = baseDir.resolve(IMAGE_SUBDIR);
        List<Path> writtenThisRequest = new ArrayList<>();
        List<PortalUploadResponse> result = new ArrayList<>(validated.size());
        try {
            Files.createDirectories(imagesDir);
            for (ValidatedImage v : validated) {
                // #7/경로조작: 저장 파일명은 UUID 강제 — 원본명은 표시용(ORGNL_FILE_NM)만.
                String storedName = UUID.randomUUID() + "." + v.format().name().toLowerCase(Locale.ROOT);
                Path dst = resolveSafe(baseDir, imagesDir.resolve(storedName));
                // #5: write 실패(디스크 고갈 등)는 아래 catch 에서 이번 요청 파일 전량 롤백.
                writeToDisk(v.file(), dst);
                writtenThisRequest.add(dst);

                // #6: MIME 은 매직바이트 확정값만 저장(확장자 추정 금지).
                LsPortalUld uld = LsPortalUld.createImage(
                        portalUserNo, truncate(v.originalName()), dst.toString(),
                        v.size(), v.format().mimeType());
                uld.markReady(null, null, 1);
                LsPortalUld savedUld = uldRepository.save(uld);

                // #4: DB INSERT 실패 시 아래 catch 가 writtenThisRequest 를 보상 삭제.
                LsPortalUldFrme frme = frmeRepository.save(
                        LsPortalUldFrme.create(savedUld.getUldSn(), IMAGE_FRAME_NO, dst.toString()));

                result.add(PortalUploadResponse.of(savedUld, frme.getUldFrmeSn()));
            }
        } catch (CustomException e) {
            rollbackFiles(writtenThisRequest);
            throw e;
        } catch (Exception e) {
            // #4/#5: write 후 DB INSERT 실패, 디스크 IOException 등 — 기록 파일 전량 삭제 + 표준 에러.
            rollbackFiles(writtenThisRequest);
            log.error("[PortalUpload] upload failed userNo={} causeType={}",
                    LogSanitizer.sanitize(portalUserNo), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 저장에 실패했습니다.");
        }

        log.info("[PortalUpload] uploaded userNo={} count={}",
                LogSanitizer.sanitize(portalUserNo), result.size());
        return result;
    }

    // ======================== 조회 ========================

    /** 소유자 업로드 목록(페이징) + 선택적 타입 필터. 타입은 IMAGE/VIDEO 만 허용(그 외 400). */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Page<PortalUploadResponse> listUploads(String portalUserNo, String typeFilter, Pageable pageable) {
        requireOwner(portalUserNo);
        if (typeFilter == null || typeFilter.isBlank()) {
            return uldRepository.findAllByPortalUserNo(portalUserNo, pageable)
                    .map(PortalUploadResponse::from);
        }
        // #입력검증(CWE-20): 미지원 타입은 조용히 빈 결과 대신 400 으로 명확히 거부.
        String normalized = typeFilter.toUpperCase(Locale.ROOT);
        if (!LsPortalUld.TYPE_IMAGE.equals(normalized) && !LsPortalUld.TYPE_VIDEO.equals(normalized)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 type 입니다. 허용: IMAGE, VIDEO");
        }
        return uldRepository.findAllByPortalUserNoAndUldTypeCd(portalUserNo, normalized, pageable)
                .map(PortalUploadResponse::from);
    }

    /** 소유자 자산 상세 + 프레임 요약. 소유자 아님/부재 → 403(자원 열거 차단). */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public PortalUploadDetailResponse getUpload(Long uldSn, String portalUserNo) {
        requireOwner(portalUserNo);
        LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(uldSn, portalUserNo)
                .orElseThrow(this::forbidden);
        List<LsPortalUldFrme> frames = frmeRepository.findAllByUldSnOrderByFrmeNo(uld.getUldSn());
        return PortalUploadDetailResponse.of(uld, frames);
    }

    /** 소유자 자산 프레임 목록(페이징). 소유자 스코프 조인 쿼리로 IDOR 차단. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Page<PortalUploadFrameResponse> listFrames(Long uldSn, String portalUserNo, Pageable pageable) {
        requireOwner(portalUserNo);
        // 소유권 사전 검증 — 타 사용자/부재 자산은 403.
        uldRepository.findByUldSnAndPortalUserNo(uldSn, portalUserNo).orElseThrow(this::forbidden);
        return frmeRepository.findAllByUldSnAndOwnerOrderByFrmeNo(uldSn, portalUserNo, pageable)
                .map(PortalUploadFrameResponse::from);
    }

    /**
     * 프레임 이미지 바이너리 서빙 — 소유자 스코프 검증(#7). Content-Type 은 업로드 시 확정한 MIME(DB)만
     * 사용하고(확장자 추정 금지), {@code X-Content-Type-Options: nosniff} 로 sniffing 을 차단한다(#6).
     *
     * <p><b>비식별 누락 신고 게이트 대상이 아니다</b> — 여기서 나가는 건 <b>포털 사용자 본인이 업로드한
     * 자산</b>({@code LS_PORTAL_ULD_FRME})이라 비식별 처리 대상이 아니고 {@code LS_DATA_RAW.DE_IDNTF_YN}
     * 라이프사이클도 없다(ADR-013 예외, 내부 파이프라인·데이터마트와 완전 분리). 내부 파이프라인의
     * 비식별 프레임을 포털로 내보내는 {@code PortalLabelService#serveFrameImage} 와 혼동 금지 —
     * 그쪽은 게이트 대상이라 응답이 {@code no-store} 이고, 이 경로는 그 캐시 통일 대상이 아니다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public ResponseEntity<Resource> serveFrameImage(Long uldFrmeSn, String portalUserNo) {
        requireOwner(portalUserNo);
        LsPortalUldFrme frme = frmeRepository.findByUldFrmeSnAndOwner(uldFrmeSn, portalUserNo)
                .orElseThrow(this::forbidden);
        // MIME 은 소유 업로드 마스터에 저장된 확정값 사용(확장자 추정 금지).
        LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(frme.getUldSn(), portalUserNo)
                .orElseThrow(this::forbidden);

        Path baseDir = baseDir();
        Path resolved = resolveSafe(baseDir, Paths.get(frme.getFilePathNm()));
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[PortalUpload] image file missing uldFrmeSn={}", uldFrmeSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }
        // CWE-59/367 — lexical 검증(resolveSafe)만으로는 base 안의 심링크가 base 밖(예: 내부 파이프라인의
        // frames/raw/**)을 가리키는 경우를 막지 못한다. FileSystemResource·Files.size 는 링크를 따라가므로
        // 그대로 외부 채널로 나간다. 실경로 봉쇄 후 그 실경로를 NOFOLLOW 로 연다(다른 서빙 경로와 동일 규약).
        Path realFile = realWithinBase(baseDir, resolved, "uldFrmeSn=" + uldFrmeSn);
        MediaType mediaType = resolveStoredMediaType(uld.getMimeTypeNm());
        FrameImageService.OpenedFile opened;
        try {
            opened = FrameImageService.openNoFollow(realFile);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지를 읽을 수 없습니다.");
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(opened.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"frame_" + uldFrmeSn + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(opened.stream()));
    }

    /**
     * 실경로가 base 하위인지 재검증하고 <b>그 실경로</b>를 돌려준다 (CWE-59/22).
     *
     * <p>lexical 검증을 통과한 경로라도 심링크를 따라가면 base 밖 파일이 될 수 있다. 검증에 쓴 경로와
     * 여는 경로를 같게 만들어 검증~open 사이 교체(TOCTOU)도 함께 좁힌다. 로그·응답에 경로 원문은 남기지
     * 않는다(CWE-209).
     */
    private Path realWithinBase(Path baseDir, Path resolved, String logKey) {
        try {
            Path real = resolved.toRealPath();
            if (!real.startsWith(baseDir.toRealPath())) {
                log.warn("[PortalUpload] symlink escaping base rejected {}", logKey);
                throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
            }
            return real;
        } catch (IOException e) {
            log.warn("[PortalUpload] realpath resolution failed {}", logKey);
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }
    }

    // ======================== 삭제 ========================

    /**
     * 자산 삭제(#8). 소유권 검증 → 프레임/원본 파일 명시 삭제(DB CASCADE 는 파일을 지우지 못함) →
     * DB 행 삭제(CASCADE 로 FRME/LBL 정리). 파일 삭제 IOException 시 DB 를 건드리지 않고 5xx.
     */
    @Transactional("controlTransactionManager")
    public void deleteUpload(Long uldSn, String portalUserNo) {
        requireOwner(portalUserNo);
        LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(uldSn, portalUserNo)
                .orElseThrow(this::forbidden);

        // 시나리오 #12: 프레임 추출 중(PROCESSING)인 자산은 삭제 불가 → 409. 러너가 쓰는 프레임
        // 파일/행을 삭제 도중 제거하면 파일-DB 불일치·경합이 생기므로 처리 완료(READY/FAILED) 후 허용.
        if (LsPortalUld.STTS_PROCESSING.equals(uld.getUldSttsCd())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "프레임 추출이 진행 중인 자산은 삭제할 수 없습니다. 완료 후 다시 시도하세요.");
        }

        // 삭제 대상 물리 경로 수집(중복 제거) — 프레임 파일 + 원본 파일.
        Set<Path> targets = new LinkedHashSet<>();
        for (LsPortalUldFrme frme : frmeRepository.findAllByUldSnOrderByFrmeNo(uldSn)) {
            addTarget(targets, frme.getFilePathNm());
        }
        addTarget(targets, uld.getFilePathNm());

        // 파일 삭제를 DB 삭제보다 먼저 — IOException 시 DB 행 보존(재시도 가능한 5xx).
        for (Path p : targets) {
            deleteFileOrThrow(p);
        }
        uldRepository.delete(uld);
        log.info("[PortalUpload] deleted uldSn={} userNo={} files={}",
                uldSn, LogSanitizer.sanitize(portalUserNo), targets.size());
    }

    // ======================== 내부 헬퍼 ========================

    private ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "빈 파일입니다.");
        }
        if (file.getSize() > properties.maxImageSizeBytes()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "이미지 크기가 허용 한도를 초과했습니다.");
        }
        String originalName = file.getOriginalFilename();
        String ext = resolveExtension(originalName);
        if (!properties.allowedImageExtensions().contains(ext)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 확장자입니다. 허용: " + properties.allowedImageExtensions());
        }
        byte[] head = readHead(file);
        Optional<ImageMagicByteValidator.ImageFormat> detected = ImageMagicByteValidator.detect(head);
        if (detected.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 이미지 형식입니다(JPEG/PNG 만 허용).");
        }
        // #6: 확장자↔시그니처 불일치 거부(예: .png 인데 JPEG 시그니처).
        if (!detected.get().matchesExtension(ext)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "확장자와 실제 이미지 형식이 일치하지 않습니다.");
        }
        // #6/CWE-434: header-only truncated JPEG 차단 — 파일 끝 EOI(FF D9) 확인(전체 디코드 없이 저비용).
        // PNG 의 IHDR 청크 검사는 detect() 가 head 로 이미 수행(별도 tail 판독 불필요).
        if (detected.get() == ImageMagicByteValidator.ImageFormat.JPEG
                && !ImageMagicByteValidator.endsWithJpegEoi(readTail(file, JPEG_EOI_BYTES))) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "손상되었거나 완전하지 않은 이미지 파일입니다.");
        }
        return new ValidatedImage(file, originalName, file.getSize(), detected.get());
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ImageMagicByteValidator.HEADER_BYTES);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 헤더를 읽을 수 없습니다.");
        }
    }

    /**
     * 파일 말미 {@code n} 바이트 판독 — JPEG EOI(FF D9) 검사용. 전체 로드 없이 앞부분을 skip 해
     * 말미만 읽는다(대용량 비용 회피). 파일이 {@code n} 바이트보다 짧으면 있는 만큼 반환.
     */
    private static byte[] readTail(MultipartFile file, int n) {
        long size = file.getSize();
        long toSkip = Math.max(0, size - n);
        try (InputStream in = file.getInputStream()) {
            long skipped = 0;
            while (skipped < toSkip) {
                long s = in.skip(toSkip - skipped);
                if (s <= 0) {
                    if (in.read() < 0) {
                        break;
                    }
                    skipped++;
                } else {
                    skipped += s;
                }
            }
            return in.readNBytes(n);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 말미를 읽을 수 없습니다.");
        }
    }

    private void writeToDisk(MultipartFile file, Path dst) {
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dst);
        } catch (IOException e) {
            log.error("[PortalUpload] write failed causeType={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 저장에 실패했습니다.");
        }
    }

    private void rollbackFiles(List<Path> written) {
        for (Path p : written) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                // best-effort 보상 삭제 — 실패해도 흐름은 유지하되, 잔존 고아 파일 추적을 위해 WARN.
                // 경로 전체가 아닌 UUID 저장 파일명만(원본명·PII 없음) 정제 출력.
                log.warn("[PortalUpload] rollback delete failed file={} causeType={}",
                        LogSanitizer.sanitize(fileNameOf(p)), e.getClass().getSimpleName());
            }
        }
    }

    private static String fileNameOf(Path p) {
        Path name = p == null ? null : p.getFileName();
        return name == null ? "(unknown)" : name.toString();
    }

    private void addTarget(Set<Path> targets, String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return;
        }
        Path resolved = resolveSafe(baseDir(), Paths.get(pathStr));
        targets.add(resolved);
    }

    private void deleteFileOrThrow(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // #8: 파일 삭제 실패 → DB 삭제 중단(재시도 가능한 5xx). 원본 절대 유실 방지.
            log.error("[PortalUpload] file delete failed causeType={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "파일 삭제에 실패했습니다. 잠시 후 다시 시도하세요.");
        }
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
            log.warn("[PortalUpload] path traversal blocked");
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 경로입니다.");
        }
        return resolved;
    }

    private static MediaType resolveStoredMediaType(String mimeTypeNm) {
        if (ImageMagicByteValidator.ImageFormat.JPEG.mimeType().equals(mimeTypeNm)) {
            return MediaType.IMAGE_JPEG;
        }
        if (ImageMagicByteValidator.ImageFormat.PNG.mimeType().equals(mimeTypeNm)) {
            return MediaType.IMAGE_PNG;
        }
        // 저장 시 매직바이트로 JPEG/PNG 만 확정하므로 도달 불가 — fail-closed.
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static String resolveExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        String basename = originalFilename;
        int slash = Math.max(basename.lastIndexOf('/'), basename.lastIndexOf('\\'));
        if (slash >= 0) {
            basename = basename.substring(slash + 1);
        }
        int dot = basename.lastIndexOf('.');
        if (dot < 0 || dot == basename.length() - 1) {
            return "";
        }
        String ext = basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("^[a-z0-9]{1,8}$") ? ext : "";
    }

    private static String truncate(String name) {
        if (name == null) {
            return null;
        }
        return name.length() > ORGNL_FILE_NM_MAX ? name.substring(0, ORGNL_FILE_NM_MAX) : name;
    }

    private static String displayName(MultipartFile file) {
        String n = file == null ? null : file.getOriginalFilename();
        return (n == null || n.isBlank()) ? "(이름없음)" : n;
    }

    private void requireOwner(String portalUserNo) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }

    private CustomException forbidden() {
        return new CustomException(ErrorCode.FORBIDDEN, "본인 자산이 아니거나 존재하지 않습니다.");
    }

    /** 검증 통과 이미지 — 파일 핸들 + 원본명 + 크기 + 확정 포맷. */
    private record ValidatedImage(MultipartFile file, String originalName, long size,
                                  ImageMagicByteValidator.ImageFormat format) {
    }
}
