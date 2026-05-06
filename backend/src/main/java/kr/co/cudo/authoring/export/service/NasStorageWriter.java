package kr.co.cudo.authoring.export.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Phase 10 — NAS 파일시스템 쓰기 (atomic write).
 *
 * <p>보안:
 * <ul>
 *   <li>CWE-22 (Path Manipulation): {@code relativePath} 가 baseStorage 밖으로 탈출하지 못하도록
 *       {@code Path.normalize()} 후 startsWith 검증.</li>
 *   <li>CWE-362 (Race Condition / TOCTOU): tmp 파일에 먼저 쓴 뒤 ATOMIC_MOVE 로 rename — 중간 상태 차단.</li>
 *   <li>실패 시 tmp 파일을 finally 에서 정리 (Resource Management).</li>
 * </ul>
 *
 * <p>baseStorage 는 {@code authoring.storage.raw-path} (default {@code ./storage/raw}). 운영은 NAS 마운트.
 */
@Slf4j
@Component
public class NasStorageWriter {

    private final Path baseStorage;

    public NasStorageWriter(@Value("${authoring.storage.raw-path:./storage/raw}") String baseStoragePath) {
        this.baseStorage = Path.of(baseStoragePath).toAbsolutePath().normalize();
    }

    /**
     * 텍스트 컨텐츠를 baseStorage/relativePath 에 atomic 하게 쓴다.
     * 부모 디렉토리는 자동 생성.
     *
     * @return 실제 쓴 절대 경로 (정규화)
     */
    public Path writeText(String relativePath, String content) {
        Path target = resolveAndValidate(relativePath);
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // NFS 등 ATOMIC_MOVE 미지원 환경 fallback
                log.warn("[NasStorage] ATOMIC_MOVE unsupported, fallback to REPLACE_EXISTING path={}", target);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[NasStorage] wrote bytes={} path={}", bytes.length, target);
            return target;
        } catch (IOException e) {
            log.error("[NasStorage] write failed path={} err={}", target, e.getMessage());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "NAS 쓰기 실패: " + e.getMessage(), e);
        } finally {
            // tmp 가 남아있으면 정리 (성공 시 move 로 사라졌으므로 NOOP)
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                log.warn("[NasStorage] tmp cleanup failed path={}", tmp);
            }
        }
    }

    /**
     * baseStorage 기준 절대 경로를 반환 (테스트/조회용).
     */
    public Path baseStorage() {
        return baseStorage;
    }

    private Path resolveAndValidate(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "저장 경로가 비어있습니다.");
        }
        Path resolved = baseStorage.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseStorage)) {
            log.warn("[NasStorage] path traversal blocked input={} resolved={}", relativePath, resolved);
            throw new CustomException(ErrorCode.FORBIDDEN, "잘못된 저장 경로입니다.");
        }
        return resolved;
    }
}
