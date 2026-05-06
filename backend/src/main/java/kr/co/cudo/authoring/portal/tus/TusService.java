package kr.co.cudo.authoring.portal.tus;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11 — TUS 1.0 비즈니스 로직.
 *
 * 보안 (Critical):
 *  - IDOR (CWE-639): {@link #find} 가 fileId 의 userId 와 토큰 userId 일치 검증 + .meta 의 ownerUserId 재검증.
 *  - CWE-22: TusFileId.validateFormat 으로 fileId 자체 path traversal 차단.
 *  - PATCH offset 불일치 → 409 (TUS 표준).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TusService {

    public static final String TUS_VERSION = "1.0.0";

    private final TusStorage storage;
    private final TusFileSecurityValidator securityValidator;

    /**
     * fileId 단위 동시성 잠금 (MEDIUM-1 — 동일 fileId 에 대한 동시 PATCH 직렬화).
     *
     * <p>같은 fileId 의 동시 PATCH 시 offset 검증 → writeChunk → saveMeta 사이에
     * race condition 이 발생하면 데이터 손상/오버랩 가능 → fileId 별 lock 으로 직렬화.
     *
     * <p>단일 노드 가정. 분산 환경에서는 외부 lock (Redis/DB) 으로 대체 필요.
     * 메모리 누수 방지: delete 시 lock 제거.
     */
    private final Map<String, Object> fileLocks = new ConcurrentHashMap<>();

    /** POST /uploads — 업로드 세션 생성. */
    public TusFile create(String userId, long uploadLength, String uploadMetadataHeader) {
        securityValidator.validateSize(uploadLength);
        Map<String, String> md = parseUploadMetadata(uploadMetadataHeader);
        TusFileSecurityValidator.ValidatedMeta v = securityValidator.validateAndNormalize(
                md.get("filename"), md.get("filetype"));

        String fileId = TusFileId.generate(userId);
        TusMetaFile meta = new TusMetaFile(uploadLength, 0L, v.fileName(), v.mimeType(), userId);
        storage.initFile(userId, fileId, meta);
        log.info("[Tus] created fileId={} userId={} size={} mime={}", fileId, userId, uploadLength, v.mimeType());
        return toView(fileId, userId, meta);
    }

    /** HEAD/GET 등 조회 — 본인 영상만 (IDOR 방어). */
    public TusFile find(String fileId, String tokenUserId) {
        // 1차: fileId 형식 검증 (path traversal 차단)
        try {
            TusFileId.validateFormat(fileId);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.NOT_FOUND, "fileId 형식 오류");
        }
        // 2차: fileId 의 userId 와 토큰 userId 비교 (IDOR 1차)
        if (!TusFileId.ownerMatches(fileId, tokenUserId)) {
            log.warn("[Tus] IDOR blocked fileId={} tokenUser={}", fileId, tokenUserId);
            throw new CustomException(ErrorCode.FORBIDDEN, "본인 업로드만 접근 가능합니다.");
        }
        // 3차: .meta 의 ownerUserId 재검증 (IDOR 2차 — fileId 위조 방어)
        TusMetaFile meta = storage.loadMeta(tokenUserId, fileId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        if (meta.ownerUserId() == null || !meta.ownerUserId().equals(tokenUserId)) {
            log.warn("[Tus] meta owner mismatch fileId={} tokenUser={} metaOwner={}",
                    fileId, tokenUserId, meta.ownerUserId());
            throw new CustomException(ErrorCode.FORBIDDEN, "본인 업로드만 접근 가능합니다.");
        }
        return toView(fileId, tokenUserId, meta);
    }

    /** PATCH — 청크 추가. offset 불일치 시 409 (TUS 표준). */
    public TusFile patch(String fileId, String tokenUserId, long uploadOffset, byte[] body) {
        // body / 권한 검증은 lock 외부에서 1차 (빠른 거부 + IDOR 방어).
        if (body == null || body.length == 0) {
            // 권한 검증 전에도 input 검증 가능 (권한과 무관).
            throw new CustomException(ErrorCode.INVALID_INPUT, "PATCH body 가 비어있습니다.");
        }
        // IDOR + 형식 검증 (lock 외부 — 잘못된 fileId 로 lock 만 만드는 것 방지)
        find(fileId, tokenUserId);

        // 동일 fileId 동시 PATCH 직렬화 (MEDIUM-1).
        Object lock = fileLocks.computeIfAbsent(fileId, k -> new Object());
        synchronized (lock) {
            // lock 안에서 최신 상태 재조회 (다른 PATCH 가 offset 진행시켰을 수 있음)
            TusFile current = find(fileId, tokenUserId);
            if (uploadOffset != current.offset()) {
                throw new CustomException(ErrorCode.CONFLICT,
                        "Upload-Offset 불일치 (server=" + current.offset() + ", client=" + uploadOffset + ")");
            }
            long newEnd = (long) uploadOffset + body.length;
            if (newEnd > current.size()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Length 초과");
            }
            TusChunk chunk = new TusChunk(uploadOffset, body);
            long newOffset = storage.writeChunk(tokenUserId, fileId, chunk);

            // .meta 갱신
            TusMetaFile meta = new TusMetaFile(current.size(), newOffset,
                    current.fileName(), current.mimeType(), tokenUserId);
            storage.saveMeta(tokenUserId, fileId, meta);
            return toView(fileId, tokenUserId, meta);
        }
    }

    /** DELETE — 업로드 취소 (본인 소유 검증 후 데이터+.meta 삭제). */
    public void delete(String fileId, String tokenUserId) {
        // 권한 검증 (find 가 IDOR 방어 수행)
        find(fileId, tokenUserId);
        storage.deleteFile(tokenUserId, fileId);
        // lock 메모리 누수 방지 — 진행 중 PATCH 가 있으면 다음 PATCH 가 새 lock 을 만들지만
        // 이 시점엔 file 이 이미 삭제됐으므로 find 가 404 → PATCH 진입 자체가 차단됨.
        fileLocks.remove(fileId);
        log.info("[Tus] deleted fileId={} userId={}", fileId, tokenUserId);
    }

    /**
     * Upload-Metadata 헤더 파싱.
     *  형식: {@code "key1 base64Value1,key2 base64Value2"}
     */
    static Map<String, String> parseUploadMetadata(String header) {
        Map<String, String> out = new HashMap<>();
        if (header == null || header.isBlank()) {
            return out;
        }
        for (String kv : header.split(",")) {
            String trimmed = kv.trim();
            int sp = trimmed.indexOf(' ');
            if (sp <= 0 || sp == trimmed.length() - 1) {
                continue;
            }
            String key = trimmed.substring(0, sp);
            String b64 = trimmed.substring(sp + 1).trim();
            try {
                String value = new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
                out.put(key, value);
            } catch (IllegalArgumentException ignored) {
                // 잘못된 base64 → 무시 (필수 키는 후속 검증에서 거부)
            }
        }
        return out;
    }

    private TusFile toView(String fileId, String userId, TusMetaFile meta) {
        return new TusFile(
                fileId,
                userId,
                meta.fileSize(),
                meta.offset(),
                meta.filename(),
                meta.filetype(),
                storage.dataPath(userId, fileId),
                storage.metaPath(userId, fileId)
        );
    }
}
