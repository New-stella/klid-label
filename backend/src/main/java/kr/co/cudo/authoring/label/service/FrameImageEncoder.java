package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 프레임 원본 이미지를 base64 로 인코딩하는 공용 컴포넌트.
 *
 * <p>Sam2TrackService / YoloTrackService 등 ai-server 로 프레임 이미지를 송신하는 경로에서
 * 중복되던 경로 가드 + 인코딩 로직을 단일화한다.
 *
 * <p>보안:
 * <ul>
 *   <li>Path Traversal (CWE-22): storage.raw-path 기준 디렉토리 범위 밖 접근 차단
 *       ({@code resolve().normalize()} + {@code startsWith}).</li>
 *   <li>Info Leak (CWE-209): 클라이언트로 나가는 예외 메시지에 내부 파일 경로·원문을 노출하지 않는다.</li>
 * </ul>
 */
@Component
public class FrameImageEncoder {

    private final Path baseDir;
    private final Path deidBaseDir;

    public FrameImageEncoder(@Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
                             @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String storageDeidPath) {
        this.baseDir = Path.of(storageRawPath).toAbsolutePath().normalize();
        this.deidBaseDir = Path.of(storageDeidPath).toAbsolutePath().normalize();
    }

    /**
     * M-6 — 프레임 엔티티에서 <b>비식별 우선 폴백</b>으로 이미지 경로를 해석한다.
     *
     * <p>해상도 파생 프레임은 원본 픽셀이 실재하지 않아 {@code SRC_FILE_PATH_NM} 이 null 이다(정책 A).
     * 원본 컬럼만 보던 구 구현은 파생 프레임에서 AI 탐지·분할·추적이 전부
     * {@code "이미지 경로가 비어있습니다"}(400)로 실패했다. 비식별 경로를 먼저 쓰되, 검증 base 를
     * <b>출처 컬럼에 맞춰</b> 고른다(비식별=deid base + 비식별 서브트리, 원본=raw base).
     *
     * @return 존재하는 이미지 절대경로
     * @throws CustomException 두 컬럼 모두 결측/해석 불가(NOT_FOUND·INVALID_INPUT)
     */
    public Path resolveFrameImage(LsDataSrc frame) {
        if (frame == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다.");
        }
        String deid = frame.getDeidFilePath();
        if (deid != null && !deid.isBlank()) {
            StorageSubtreePolicy.Verification v =
                    StorageSubtreePolicy.verifyDeidentifiedFile(deidBaseDir, deid);
            if (v.ok()) {
                return v.path();
            }
        }
        String original = frame.getSrcFilePathNm();
        if (original == null || original.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "프레임 이미지를 찾을 수 없습니다.");
        }
        Path resolved = resolveSafe(original);
        if (!Files.exists(resolved)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일을 찾을 수 없습니다.");
        }
        return resolved;
    }

    /** 프레임 엔티티 기준(비식별 우선) 이미지를 base64 로 인코딩한다. */
    public String encodeFrame(LsDataSrc frame) {
        Path imagePath = resolveFrameImage(frame);
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기에 실패했습니다.");
        }
    }

    /**
     * 기준 디렉토리 하위의 상대 경로 이미지를 읽어 base64 문자열로 반환한다.
     *
     * @param relativePath storage.raw-path 기준 상대 경로
     * @return base64 인코딩 문자열
     * @throws CustomException INVALID_INPUT(경로 비었거나 범위 밖) / NOT_FOUND(파일 없음) / INTERNAL_ERROR(읽기 실패)
     */
    public String encodeToBase64(String relativePath) {
        Path imagePath = resolveSafe(relativePath);
        if (!Files.exists(imagePath)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일을 찾을 수 없습니다.");
        }
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기에 실패했습니다.");
        }
    }

    /** Path Traversal (CWE-22) 방어 — 기준 디렉토리 외부 접근 차단. */
    private Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 경로가 비어있습니다.");
        }
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "허용되지 않은 경로입니다.");
        }
        return resolved;
    }
}
