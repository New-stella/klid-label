package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * TUS 청크 파일 I/O 코어 — <b>순수 파일 I/O 만</b> 담당한다(트랜잭션/락/도메인 검증 미포함).
 *
 * <p>Phase 3 리팩터: 관제({@link TusUploadService})와 포털
 * ({@code PortalVideoUploadService}) 가 동일한 원자적 청크 append / truncate / offset 정합 로직을
 * 공유하도록 파일 I/O 코어를 추출했다. 상태 없는 static 유틸이라 각 서비스의 생성자 시그니처를
 * 바꾸지 않아 기존 동작(및 테스트)을 그대로 보존한다.
 *
 * <p>보안:
 * <ul>
 *   <li>OOM 방어(CWE-400): 고정 64KB 버퍼 스트리밍 — 전체 byte[] 메모리 적재 회피.</li>
 *   <li>부분 쓰기 정합: write 실패/한도 초과 시 기록 시작 오프셋으로 truncate, offset 미갱신.</li>
 *   <li>경로 삭제 심층방어(CWE-22): {@link #deleteQuietly(String, Path)} 는 storage root 하위만 삭제.</li>
 * </ul>
 */
@Slf4j
public final class TusChunkStore {

    /** 청크 스트리밍 고정 버퍼 크기(64KB) — 전체 byte[] 메모리 적재 회피. */
    static final int CHUNK_STREAM_BUFFER_BYTES = 64 * 1024;

    private TusChunkStore() {
    }

    /** 0바이트 임시 파일 생성(부모 디렉토리 포함). 이후 PATCH 가 position write 로 누적한다. */
    public static void createEmptyFile(Path absolutePath) throws IOException {
        if (absolutePath.getParent() != null) {
            Files.createDirectories(absolutePath.getParent());
        }
        Files.write(absolutePath, new byte[0]);
    }

    /**
     * 청크 입력을 고정 64KB 버퍼 루프로 스트리밍하며 FileChannel 의 정확한 오프셋에 기록한다.
     *
     * <p>누적 기록량이 {@code maxChunkBytes} 를 넘으면 즉시 413 + truncate 롤백한다(클라이언트가
     * Content-Length 보다 더 보내는 경우 방어). 부분 쓰기 실패/길이 불일치 시 기록 시작 오프셋으로
     * truncate 하고 offset 을 갱신하지 않아 재시도 시 동일 오프셋 재요청이 된다.
     *
     * @return 기록 완료 후의 누적 오프셋(offset + contentLength)
     */
    public static long writeChunkAtomically(Path file, long offset, InputStream chunk,
                                            long contentLength, long maxChunkBytes) {
        long written = 0L;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.position(offset);
            byte[] buf = new byte[CHUNK_STREAM_BUFFER_BYTES];
            int r;
            while (written < contentLength && (r = chunk.read(buf)) != -1) {
                int toWrite = (int) Math.min(r, contentLength - written);
                if (written + toWrite > maxChunkBytes) {
                    truncateTo(file.toString(), offset);
                    throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                            "청크 크기가 허용 한도(" + maxChunkBytes + " bytes) 를 초과했습니다.");
                }
                ch.write(ByteBuffer.wrap(buf, 0, toWrite));
                written += toWrite;
            }
            ch.force(true);
        } catch (CustomException e) {
            throw e;
        } catch (IOException e) {
            truncateTo(file.toString(), offset);
            log.error("[TusChunk] chunk write failed causeType={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "청크 저장에 실패했습니다.");
        }
        if (written != contentLength) {
            truncateTo(file.toString(), offset);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "청크 본문 길이가 Content-Length 와 일치하지 않습니다.");
        }
        return offset + written;
    }

    /** best-effort truncate — 부분 쓰기/충돌 시 일관성 복원. */
    public static void truncateTo(String path, long size) {
        try (FileChannel ch = FileChannel.open(Paths.get(path), StandardOpenOption.WRITE)) {
            ch.truncate(size);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * 심층방어 삭제(CWE-22): 삭제 직전 대상 경로가 {@code storageRoot} 하위인지 재검증한다.
     * 데이터 변조 등으로 filePath 가 root 밖을 가리키면 임의 파일 삭제로 이어질 수 있으므로 게이트한다.
     */
    public static void deleteQuietly(String path, Path storageRoot) {
        if (path == null || path.isBlank()) {
            return;
        }
        Path target = Paths.get(path).toAbsolutePath().normalize();
        if (storageRoot != null && !target.startsWith(storageRoot)) {
            log.warn("[TusChunk] delete blocked — path outside storage root");
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
