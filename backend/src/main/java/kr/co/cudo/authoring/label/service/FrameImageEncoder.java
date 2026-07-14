package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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

    public FrameImageEncoder(@Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.baseDir = Path.of(storageRawPath).toAbsolutePath().normalize();
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
