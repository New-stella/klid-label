package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 비식별 제외(출처유형 제외) 처리 — 원본 영상을 비식별 영상 쓰기 위치에 <b>일반 파일로 복사</b>해
 * 외부 위탁 없이 비식별을 완료한다.
 *
 * <h3>규약</h3>
 * <ul>
 *   <li>원본({@code RAW_FILE_PATH_NM})은 실재하는 정규 파일이어야 한다 — <b>외부 위탁 전 원본 실재 검증
 *       ({@code KpstDeidentService.verifySourceOrFail})과 같은 규칙</b>으로 링크를 따라가 판정하고, 읽을 때도
 *       링크를 따라간다(원본 경로가 NAS 심링크여도 링크 대상의 내용을 복사한다). 규칙이 갈리면 같은 원본이
 *       위탁 영상은 통과하고 제외 영상만 실패한다.</li>
 *   <li>대상 디렉터리는 {@link VideoArtifactRootResolver#deidVideoDir} — <b>쓰기 직전</b>에 계산하고,
 *       생성 후 base 를 다시 계산해 실경로가 여전히 그 하위인지 재확인한다(TOCTOU, CWE-367/59).
 *       파일명은 원본 파일명 그대로다.</li>
 *   <li>같은 디렉터리의 임시 파일에 쓴 뒤 원자적으로 옮긴다. <b>만든 복사본은</b> 심링크·하드링크가 아닌
 *       일반 파일이어야 하며, 원본과 같은 파일로 해석되면 거부한다 — 원본을 가리키는
 *       링크는 비식별 영상과 원본의 격리를 깨고 재생 경로의 실경로·NOFOLLOW 규약과 어긋난다.</li>
 *   <li>복사는 <b>DB 트랜잭션 밖</b>에서 하고, 기록은 {@link DeidentExclusionTxService} 의 짧은 독립
 *       트랜잭션으로 한다.</li>
 *   <li>실패하면 임시 파일을 정리하고 'F' + 이력 FAILED(EXCLUDED)를 커밋한 뒤 예외를 전파한다.
 *       <b>원본은 삭제하거나 수정하지 않는다.</b></li>
 *   <li>로그·예외에 경로 원문·파일명을 남기지 않는다(CWE-209/532).</li>
 * </ul>
 *
 * @design ADR-066
 * @design DFEAT-041
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeidentExclusionService {

    /** 원본 부재(또는 정규 파일 아님) — ERR_CD(varchar 50) 이내. */
    public static final String SOURCE_MISSING_CODE = "DEID_EXCLUDED_SOURCE_MISSING";
    /** 경로 위반·입출력 오류로 복사 실패 — ERR_CD(varchar 50) 이내. */
    public static final String COPY_FAILED_CODE = "DEID_EXCLUDED_COPY_FAILED";
    /** 복사는 끝났으나 성공 기록 트랜잭션이 실패 — ERR_CD(varchar 50) 이내. */
    public static final String RECORD_FAILED_CODE = "DEID_EXCLUDED_RECORD_FAILED";

    /** 임시 파일 접두/접미 — 원자 이동 전 같은 디렉터리에 둔다. */
    static final String TEMP_PREFIX = ".deid-excluded-";
    static final String TEMP_SUFFIX = ".tmp";

    private final VideoArtifactRootResolver artifactRootResolver;
    private final DeidentExclusionTxService txService;

    /**
     * 제외 처리를 수행하고 복사본 경로를 돌려준다.
     *
     * @return 비식별 영상 경로로 기록된 복사본의 절대경로
     * @throws CustomException 원본 부재(INVALID_INPUT) / 복사 실패(INTERNAL_ERROR) — 실패 기록은 이미 커밋됨
     */
    public String complete(LsDataRaw raw) {
        Long rawSn = raw.getRawSn();
        String rawFilePathNm = raw.getRawFilePathNm();

        Path source = regularSourceOrNull(rawFilePathNm);
        if (source == null) {
            txService.recordFailure(rawSn, SOURCE_MISSING_CODE, "source not found");
            log.warn("[Deident] excluded copy rejected — source video missing rawSn={}", rawSn);
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 원본 영상이 존재하지 않습니다.");
        }

        Path copied;
        try {
            copied = copyIntoDeidDir(rawSn, rawFilePathNm, source);
        } catch (IOException | RuntimeException e) {
            txService.recordFailure(rawSn, COPY_FAILED_CODE, e.getClass().getSimpleName());
            log.warn("[Deident] excluded copy failed rawSn={} errType={}", rawSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 제외 영상 복사에 실패했습니다.");
        }

        String copiedPath = copied.toString();
        try {
            txService.recordSuccess(rawSn, rawFilePathNm, copiedPath);
        } catch (RuntimeException e) {
            // 복사는 끝났는데 성공 기록이 실패했다(영상 동시 삭제·보류 해제 예외·DB 오류 등). 외부 위탁의
            // 「제출 이전 실패는 'F' 를 커밋한다」 규칙과 같게 실패 기록을 시도한 뒤 원래 예외를 전파한다.
            // 복사본은 지우지 않는다 — 원본이 아니라 대상 파일이고, DE_IDENT_YN 이 'Y' 가 아니면 서빙되지
            // 않으며, 재실행 시 원자 이동으로 교체된다.
            try {
                txService.recordFailure(rawSn, RECORD_FAILED_CODE, e.getClass().getSimpleName());
            } catch (RuntimeException recordError) {
                log.error("[Deident] excluded failure record also failed rawSn={} errType={}",
                        rawSn, recordError.getClass().getSimpleName());
            }
            log.warn("[Deident] excluded success record failed rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
            throw e;
        }
        log.info("[Deident] excluded srcType completed by copy rawSn={} srcType={}",
                rawSn, LogSanitizer.sanitize(raw.getSrcType()));
        return copiedPath;
    }

    /**
     * 원본이 실재하는 정규 파일이면 그 경로, 아니면 null — 외부 위탁 전 원본 실재 검증과 같은 규칙으로
     * <b>링크를 따라가</b> 판정한다(심링크 원본은 링크 대상이 정규 파일이면 통과).
     */
    private static Path regularSourceOrNull(String rawFilePathNm) {
        if (rawFilePathNm == null || rawFilePathNm.isBlank()) {
            return null;
        }
        try {
            Path source = Paths.get(rawFilePathNm).toAbsolutePath().normalize();
            return Files.isRegularFile(source) ? source : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private Path copyIntoDeidDir(long rawSn, String rawFilePathNm, Path source) throws IOException {
        // 쓰기 직전 계산 — 고정 allowlist + 실경로 검증이 현재 파일시스템 상태로 재수행된다(위반 시 예외).
        Path dir = artifactRootResolver.deidVideoDir(rawSn, rawFilePathNm).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        VideoArtifactRootResolver.verifyRealPathUnder(dir, artifactRootResolver.deidVideoDir(rawSn, rawFilePathNm));

        Path target = dir.resolve(source.getFileName().toString()).normalize();
        if (!dir.equals(target.getParent())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 영상 파일명이 올바르지 않습니다.");
        }
        // 대상이 원본 그 자체로 해석되면(디렉터리 바꿔치기 등) 원본을 덮어쓰게 되므로 거부한다.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(target, source)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 영상 위치가 원본과 같습니다.");
        }

        Path tmp = dir.resolve(TEMP_PREFIX + UUID.randomUUID() + TEMP_SUFFIX);
        try {
            // 원본은 링크를 따라가 읽는다(판정 규칙과 동일) — 복사본에는 링크 대상의 바이트가 담긴다.
            try (InputStream in = Files.newInputStream(source)) {
                // 기본 옵션은 대상이 이미 있으면 실패 — 새 임시 파일에만 쓴다.
                Files.copy(in, tmp);
            }
            VideoArtifactRootResolver.verifyRealPathUnder(tmp, artifactRootResolver.deidVideoDir(rawSn, rawFilePathNm));
            moveAtomically(tmp, target);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }

        // 옮긴 결과가 여전히 base 하위의 정규 파일인지 재확인한다.
        VideoArtifactRootResolver.verifyRealPathUnder(target, artifactRootResolver.deidVideoDir(rawSn, rawFilePathNm));
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("copied artifact is not a regular file");
        }
        return target;
    }

    private static void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            log.warn("[Deident] excluded temp cleanup failed errType={}", e.getClass().getSimpleName());
        }
    }
}
