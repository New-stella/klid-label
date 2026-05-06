package kr.co.cudo.authoring.portal.tus;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Phase 11 — TUS 디스크 저장소.
 *
 * 디렉토리 구조:
 * <pre>
 *   {basePath}/{userId}/{fileId}        ← 데이터 (sparse-allocated)
 *   {basePath}/{userId}/{fileId}.meta   ← JSON 메타
 * </pre>
 *
 * 보안 (Critical):
 *  - CWE-22 Path Manipulation: 모든 경로는 basePath/{userId}/ 하위에서만 동작.
 *    Path.normalize() + startsWith(basePath) 로 escape 차단.
 *  - sparse hole 노출 방지: 미완료 파일에 대한 외부 read 는 컨트롤러가 차단해야 함 (본 클래스는 read 미제공).
 */
@Slf4j
@Component
public class TusStorage {

    private final Path basePath;
    private final ObjectMapper objectMapper;

    public TusStorage(@Value("${portal.upload.storage-path:${authoring.storage.raw-path:./storage/raw}/portal}") String basePath,
                      ObjectMapper objectMapper) {
        this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public Path basePath() {
        return basePath;
    }

    /** {basePath}/{userId} 사용자별 디렉토리. */
    public Path userDir(String userId) {
        Path dir = basePath.resolve(userId).normalize();
        if (!dir.startsWith(basePath)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 경로");
        }
        return dir;
    }

    /** 데이터 파일 경로 — userId 와 fileId.userId 가 일치해야 함 (호출자 책임). */
    public Path dataPath(String userId, String fileId) {
        Path data = userDir(userId).resolve(fileId).normalize();
        if (!data.startsWith(basePath)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 경로");
        }
        return data;
    }

    public Path metaPath(String userId, String fileId) {
        Path meta = userDir(userId).resolve(fileId + ".meta").normalize();
        if (!meta.startsWith(basePath)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 경로");
        }
        return meta;
    }

    /** 사용자 디렉토리 보장 + sparse 파일 사전할당 + .meta 저장. */
    public void initFile(String userId, String fileId, TusMetaFile meta) {
        try {
            Path dir = userDir(userId);
            Files.createDirectories(dir);
            Path data = dataPath(userId, fileId);
            // sparse pre-allocation: seek(size-1) + write 1 byte
            try (RandomAccessFile raf = new RandomAccessFile(data.toFile(), "rw")) {
                if (meta.fileSize() > 0) {
                    raf.setLength(meta.fileSize());
                }
            }
            saveMeta(userId, fileId, meta);
        } catch (IOException e) {
            log.error("[Tus] initFile failed userId={} fileId={}", userId, fileId, e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 파일 초기화 실패");
        }
    }

    public void saveMeta(String userId, String fileId, TusMetaFile meta) {
        try {
            Path metaPath = metaPath(userId, fileId);
            byte[] json = objectMapper.writeValueAsBytes(meta);
            Files.write(metaPath, json);
        } catch (IOException e) {
            log.error("[Tus] saveMeta failed userId={} fileId={}", userId, fileId, e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, ".meta 저장 실패");
        }
    }

    public Optional<TusMetaFile> loadMeta(String userId, String fileId) {
        Path metaPath = metaPath(userId, fileId);
        if (!Files.exists(metaPath)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(metaPath);
            return Optional.of(objectMapper.readValue(bytes, TusMetaFile.class));
        } catch (IOException e) {
            log.error("[Tus] loadMeta failed userId={} fileId={}", userId, fileId, e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, ".meta 읽기 실패");
        }
    }

    /** offset 위치에 청크 기록 → 새 offset 반환. */
    public long writeChunk(String userId, String fileId, TusChunk chunk) {
        Path data = dataPath(userId, fileId);
        if (!Files.exists(data)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다.");
        }
        try (RandomAccessFile raf = new RandomAccessFile(data.toFile(), "rw")) {
            raf.seek(chunk.offset());
            raf.write(chunk.data());
            return chunk.offset() + chunk.length();
        } catch (IOException e) {
            log.error("[Tus] writeChunk failed userId={} fileId={} offset={}", userId, fileId, chunk.offset(), e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "청크 기록 실패");
        }
    }

    public void deleteFile(String userId, String fileId) {
        try {
            Files.deleteIfExists(dataPath(userId, fileId));
            Files.deleteIfExists(metaPath(userId, fileId));
        } catch (IOException e) {
            log.warn("[Tus] deleteFile failed userId={} fileId={}", userId, fileId, e);
        }
    }
}
