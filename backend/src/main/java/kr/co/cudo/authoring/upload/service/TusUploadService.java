package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.upload.dto.InternalUploadCreateRequest;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import kr.co.cudo.authoring.upload.service.UploadMediaProbe.MediaMeta;
import kr.co.cudo.authoring.video.dto.InternalUploadIngestCommand;
import kr.co.cudo.authoring.video.dto.ResolvedIngestMeta;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.InternalUploadIngestWriter;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * TUS 1.0 재개 가능 업로드 코어 서비스 (CVAT 포팅 Phase 3 — 관리 화면 대용량 영상 적재).
 *
 * <p>운영(prd) 에서도 관리 화면 업로드는 필요하므로 {@code @Profile} 미적용 — 전 환경 활성.
 * 권한(REVIEWER, INTERNAL 채널)은 컨트롤러 {@code @PreAuthorize} + SecurityConfig 로 차단.
 *
 * <h3>합류 대상은 {@code LS_DATA_INGEST}(인입) 다 — 그리고 <b>세션 생성 시점</b>에 합류한다</h3>
 * <p>적재 주체 반전 이후 영상 적재의 단일 통로는 <b>인입 폴링</b>이다. 이 서비스는 관제가 하는 일
 * (파일 배치 + 인입 행 INSERT)까지만 하고, {@code LS_DATA_RAW} 생성·{@code VideoIngestedEvent} 발행은
 * {@code TrainingVideoIngestTx} 에 맡긴다({@link #complete} 참조).
 *
 * <p><b>Phase 3</b>: 인입 행은 <b>{@link #createSession} 시점</b>에 만들어지고 파일은 청크 업로드가
 * 끝나야 도착한다. 인입 테이블이 "행 먼저, 파일 나중"을 이미 견디기 때문이다(미도착은 실패가 아니라
 * 대기 — {@code TrainingVideoIngestTx#handleNotArrived}). 그래서 관제가 보내는 29컬럼을 화면에서 받아
 * 그대로 실을 수 있고({@code Upload-Metadata} 헤더 1KB 상한에 갇히지 않는다), 완료 시점에는 파일 이동과
 * <b>backoff 해제</b>만 남는다. 업로드 저장 경로가 적재 allowlist
 * 밖이면 그 인입 행은 매 건 영구 종결되므로 {@link InternalUploadWiringGuard} 가 배포 형상에서
 * <b>업로드 기능을 닫는다</b>(기동은 정상, 엔드포인트만 503 — 오설정 1건이 라벨링·검수·배치까지
 * 멈추지 않게 실패 범위를 기능 단위로 한정한다).
 *
 * <p>시나리오 방어 (HIGH 9):
 * <ol>
 *   <li>동시 PATCH 오프셋 충돌 → {@code @Version} 낙관적 잠금 → {@code OptimisticLockingFailureException} → 409.</li>
 *   <li>Upload-Length &gt; maxFileSize → POST 시 413.</li>
 *   <li>완료 중복 → 조건부 UPDATE({@code markCompletedIfInProgress}) 원자 전이 + 멱등 응답.
 *       affectedRows==1 인 호출만 인입 행을 만든다.</li>
 *   <li>부분 쓰기 → FileChannel position write 성공 후에만 offset 전진, 실패 시 truncate.</li>
 *   <li>TTL — EXPIRES_AT + 만료 시 410.</li>
 *   <li>완료 시 매직바이트 검증 실패 → 임시파일 삭제 + 409.</li>
 *   <li>경로 순회 — filename 표시용, 저장 UUID 강제.</li>
 *   <li>소유자 — USER_NO 본인만 HEAD/PATCH/DELETE (403).</li>
 *   <li>offset 불일치 409 / 음수·초과 400 / 빈 청크 400 / 동시 IN_PROGRESS 3 상한 429.</li>
 * </ol>
 */
@Slf4j
@Service
public class TusUploadService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("mp4", "webm", "mov", "avi");
    private static final String UPLOAD_SUBDIR = "tus-uploads";
    private static final int MAX_DURATION_SEC = 7200;
    /** 사용자별 동시 진행 세션 상한 (HIGH-9). */
    private static final int MAX_CONCURRENT_IN_PROGRESS = 3;
    /** HIGH-2: 청크당 상한 기본값 (16MB) — 설정 미지정 시 사용. */
    static final long DEFAULT_MAX_CHUNK_BYTES = 16L * 1024 * 1024;
    /** PostgreSQL 유니크 제약 위반 SQLState — 409(클립 ID 중복) 판정을 이 코드로만 좁힌다(F6). */
    static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

    private final LsTusUploadRepository uploadRepository;
    private final VideoRepository videoRepository;
    /** Phase 1: 인입 행 기준 중복(UK) 사전 판정 — 인입 행은 삭제되지 않아 과거 행도 UK 를 점유한다. */
    private final LsDataIngestRepository ingestRepository;
    /** Phase 1: 완료 시 인입 행(PENDING) 을 남기는 <b>유일한</b> 비-관제 INSERT 통로. */
    private final InternalUploadIngestWriter ingestWriter;
    /** Phase 1: 업로드 파일이 놓일 인입 영역 경로 결정(적재 allowlist 정합 보장). */
    private final InternalUploadPathResolver pathResolver;
    /** 인입 행 종결 판정 단일 통로 — <b>파일이 도착하지 않은 행만</b> 종결한다(H2-b, 정리 잡과 공유). */
    private final InternalUploadIngestTerminator ingestTerminator;
    private final Path storageRawPath;
    private final long maxFileSize;
    /** HIGH-2: 단일 PATCH 청크 크기 상한 (authoring.upload.tus.max-chunk-bytes). */
    private final long maxChunkBytes;
    /**
     * Phase 2: 완료 시점 미디어 측정 — <b>재생 가능성 게이트</b>와 <b>인입 back-fill</b> 이 이 한 번의
     * 측정 결과를 공유한다(완료당 ffprobe 1회 — 호출 횟수를 늘리지 않는다는 비기능 요건).
     */
    private final UploadMediaProbe mediaProbe;

    /**
     * 프로덕션 생성자 — Spring 컴포넌트 스캔이 주입한다.
     *
     * <p>MED-2: 본 클래스는 테스트 전용 보조 생성자({@code UploadMediaProbe} 인터페이스를 받는 아래
     * 2개)를 보유하므로 생성자가 복수다. Spring 은 복수 생성자에서 {@code @Autowired} 가 없으면 주입
     * 생성자를 자동 결정하지 못해 기본(no-arg) 생성자로 폴백→실패하므로, 주입 대상인 본 프로덕션
     * 생성자에만 {@code @Autowired} 를 명시한다(테스트 생성자는 테스트가 직접 호출 — 컨테이너 주입
     * 대상 아님).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TusUploadService(
            LsTusUploadRepository uploadRepository,
            VideoRepository videoRepository,
            LsDataIngestRepository ingestRepository,
            InternalUploadIngestWriter ingestWriter,
            InternalUploadPathResolver pathResolver,
            InternalUploadIngestTerminator ingestTerminator,
            @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
            @Value("${authoring.upload.tus.max-file-size:524288000}") long maxFileSize,
            @Value("${authoring.upload.tus.max-chunk-bytes:16777216}") long maxChunkBytes,
            UploadMediaProbeFfprobe ffprobeProbe) {
        this(uploadRepository, videoRepository, ingestRepository, ingestWriter,
                pathResolver, ingestTerminator, storageRawPath, maxFileSize, maxChunkBytes,
                (UploadMediaProbe) ffprobeProbe);
    }

    /** 테스트용 — UploadMediaProbe 직접 주입 (청크 상한 기본값 적용). */
    public TusUploadService(
            LsTusUploadRepository uploadRepository,
            VideoRepository videoRepository,
            LsDataIngestRepository ingestRepository,
            InternalUploadIngestWriter ingestWriter,
            InternalUploadPathResolver pathResolver,
            InternalUploadIngestTerminator ingestTerminator,
            String storageRawPath,
            long maxFileSize,
            UploadMediaProbe mediaProbe) {
        this(uploadRepository, videoRepository, ingestRepository, ingestWriter,
                pathResolver, ingestTerminator, storageRawPath, maxFileSize, DEFAULT_MAX_CHUNK_BYTES,
                mediaProbe);
    }

    /** 테스트용 — UploadMediaProbe + 청크 상한 직접 주입. */
    public TusUploadService(
            LsTusUploadRepository uploadRepository,
            VideoRepository videoRepository,
            LsDataIngestRepository ingestRepository,
            InternalUploadIngestWriter ingestWriter,
            InternalUploadPathResolver pathResolver,
            InternalUploadIngestTerminator ingestTerminator,
            String storageRawPath,
            long maxFileSize,
            long maxChunkBytes,
            UploadMediaProbe mediaProbe) {
        this.uploadRepository = uploadRepository;
        this.videoRepository = videoRepository;
        this.ingestRepository = ingestRepository;
        this.ingestWriter = ingestWriter;
        this.pathResolver = pathResolver;
        this.ingestTerminator = ingestTerminator;
        this.storageRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.maxChunkBytes = maxChunkBytes > 0 ? maxChunkBytes : DEFAULT_MAX_CHUNK_BYTES;
        this.mediaProbe = mediaProbe;
    }

    // ======================== POST — 세션 생성 ========================

    /**
     * 업로드 세션 생성. 임시 파일(0바이트) 생성 + LS_TUS_UPLOAD 행 INSERT.
     *
     * @return 생성된 uploadId (Location 헤더로 노출)
     */
    @Transactional("controlTransactionManager")
    public UUID createSession(String userNo, long uploadLength, InternalUploadCreateRequest req) {
        if (userNo == null || userNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "세션 소유자를 확인할 수 없습니다.");
        }
        if (uploadLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Length 가 유효하지 않습니다.");
        }
        // HIGH-2: 전체 길이 사전 차단 — maxFileSize 초과 즉시 413.
        if (uploadLength > maxFileSize) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Upload-Length 가 허용 한도(" + maxFileSize + " bytes) 를 초과했습니다.");
        }
        // HIGH-6 사전: 확장자 allowlist (완료 시 매직바이트 2차 검증).
        String extension = resolveExtension(req.fileName());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 확장자입니다. 허용: " + ALLOWED_EXTENSIONS);
        }
        validateMeta(req);
        // HIGH-9: 사용자별 동시 진행 세션 상한 → 429.
        long inProgress = uploadRepository.countByUserNoAndStatus(userNo, LsTusUpload.STATUS_IN_PROGRESS);
        if (inProgress >= MAX_CONCURRENT_IN_PROGRESS) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "동시 진행 가능한 업로드 세션 수(" + MAX_CONCURRENT_IN_PROGRESS + ") 를 초과했습니다.");
        }

        // ★ 인입 행이 가리킬 <최종> 경로를 지금 확정한다 — 배선 오설정(allowlist 밖)이면 여기서
        //   즉시 실패한다(임시 파일·세션을 만들기 전에 끝내 스토리지 누수를 만들지 않는다).
        Path target = pathResolver.resolveUploadTarget(req.vmsClipId(), extension);
        // M1 — 같은 클립 ID 의 과거 행을 <되살릴 수 있는가>. 되살릴 수 없으면 여기서 409 다.
        Long reusableIngestSn = resolveReusableIngestSn(req.vmsClipId());

        UUID uploadId = UUID.randomUUID();
        // HIGH-7: 저장 파일명은 UUID 강제 — filename 은 표시용만.
        String safeFileName = uploadId + "." + extension;
        Path absolutePath = resolveSafeStoragePath(safeFileName);
        try {
            // 0바이트 임시 파일 생성 — 이후 PATCH 가 position write 로 누적(파일 I/O 코어 위임).
            TusChunkStore.createEmptyFile(absolutePath);
        } catch (IOException e) {
            log.error("[Tus] temp file create failed uploadId={} causeType={}",
                    uploadId, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 임시 파일 생성에 실패했습니다.");
        }

        LsTusUpload session = LsTusUpload.create(uploadId, userNo, uploadLength,
                absolutePath.toString(), req.fileName(), req.vmsClipId(), req.cctvId(),
                req.lclgvCd(), req.shtDt());
        uploadRepository.save(session);

        Long rcptnSn = persistIngestRow(req, target, extension, uploadLength, absolutePath,
                uploadId, reusableIngestSn);
        log.info("[Tus] session created uploadId={} userNo={} length={} ext={} rcptnSn={} revived={}",
                uploadId, LogSanitizer.sanitize(userNo, 64), uploadLength, extension, rcptnSn,
                reusableIngestSn != null);
        return uploadId;
    }

    /**
     * 인입 행(PENDING) 을 남긴다 — <b>파일보다 먼저</b> (Phase 3).
     *
     * <p>{@code reusableSn} 이 있으면 <b>그 행을 새 메타로 되살리고</b>(M1), 없으면 INSERT 한다.
     * 되살리기는 행을 지우지 않으므로 UK 위반도 감사 추적 손실도 없다.
     *
     * <p>실패하면 방금 만든 임시 파일을 회수하고 예외를 던진다. 이 트랜잭션은 함께 롤백되므로 세션
     * 행은 남지 않지만, 임시 파일은 트랜잭션 밖의 비가역 산출물이라 <b>직접</b> 되돌려야 한다
     * (0바이트 파일이 쌓이면 정리 잡 대상만 늘고 원인은 남지 않는다).
     *
     * <p>반대로 INSERT 가 커밋된 뒤 이 트랜잭션이 커밋에 실패하면 파일이 영영 오지 않는 인입 행이
     * 남는데, 그것은 <b>미도착 대기 상한</b>이 종결시킨다(설계 §6-0-1 ① — 보류에는 끝이 있다).
     */
    private Long persistIngestRow(InternalUploadCreateRequest req, Path target, String extension,
                                  long uploadLength, Path tempPath, UUID uploadIdForLog,
                                  Long reusableSn) {
        InternalUploadIngestCommand command = toIngestCommand(req, target, extension, uploadLength);
        try {
            if (reusableSn == null) {
                return ingestWriter.insertPending(command);
            }
            if (ingestWriter.reviveForUpload(reusableSn, command) != 1) {
                // 되살리기 술어(FAILED + RAW_SN IS NULL)를 그 사이 다른 실행이 깨뜨렸다.
                log.warn("[Tus] ingest row revive lost uploadId={} rcptnSn={}",
                        uploadIdForLog, reusableSn);
                deleteQuietly(tempPath.toString());
                throw new CustomException(ErrorCode.CONFLICT,
                        "동일한 영상 클립 ID 의 인입 정보가 처리 중입니다. 잠시 후 다시 시도하세요.");
            }
            return reusableSn;
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(tempPath.toString());
            if (isUniqueViolation(e)) {
                // UK(VMS_CLIP_ID) 위반 — 사전 조회 이후 같은 클립이 인입된 race.
                log.warn("[Tus] duplicate ingest row on session create uploadId={}", uploadIdForLog);
                throw new CustomException(ErrorCode.CONFLICT,
                        "동일한 영상 클립 ID 의 인입 정보가 이미 존재합니다.");
            }
            // 그 외 무결성/DB 오류를 "클립 ID 중복"으로 오진단하면 원인 규명이 막힌다(F6).
            log.error("[Tus] ingest insert failed uploadId={} causeType={}",
                    uploadIdForLog, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 인입 정보 저장에 실패했습니다.");
        }
    }

    /**
     * 같은 클립 ID 의 과거 인입 행을 <b>되살릴 수 있는가</b> 판정한다 (DEV_FIX M1).
     *
     * <h3>왜 되살리는가 — 취소한 클립 ID 를 회수할 수단이 없었다</h3>
     * <p>인입 행은 <b>삭제 금지</b>(감사 추적)이고 {@code VMS_CLIP_ID} 는 UK 다. 그래서 세션 생성만
     * 하고 취소해도 <b>그 클립 ID</b> 는 영구히 잠겼다.
     *
     * <p>계약서 §8 의 "인입 행 보존"은 <b>관제가 넣은 행의 감사 추적</b>이 취지다. 우리가 만들었고
     * 파일이 한 번도 도착한 적 없는 종결분을 <b>같은 PK 그대로 갱신</b>하는 것은 삭제가 아니며 그
     * 취지에 어긋나지 않는다.
     *
     * <h3>★ 되살리기가 해소한 범위 — <b>같은 clipId 반복</b>뿐이다 (DEV_FIX 2차 [E])</h3>
     * <p>구 주석은 "반복 취소만으로 무제한 증식이 해소됐다"고 적었는데 <b>실제보다 넓은 주장</b>이다.
     * 해소된 것은 <b>같은 clipId 를 반복</b>할 때 행이 1건으로 유지된다는 것뿐이다. <b>서로 다른
     * clipId</b> 로 세션 생성·취소를 반복하면 인입 행은 여전히 무제한 증식한다
     * ({@code MAX_CONCURRENT_IN_PROGRESS}=3 은 <b>동시</b> 세션 수만 제한하고 누적은 제한하지 않는다).
     *
     * <p><b>★ 관제 clipId 선점 축은 그대로 열려 있다(미해소).</b> 되살리기는 <b>우리 세션 생성
     * 경로에서만</b> 발동하므로, REVIEWER 가 "관제가 앞으로 쓸 clipId" 로 세션을 만들었다가 취소하면
     * 그 행({@code FAILED})이 UK 를 계속 점유해 <b>관제 INSERT 가 UK 위반으로 실패</b>한다. 관제에는
     * 그 사실을 관측할 수단이 없다(우리 로그에만 남는다). 이 잔여 위험은 <b>내부 REVIEWER 권한 보유자</b>
     * 로 한정되며, 해소하려면 clipId 네임스페이스 분리(예: 업로드분 접두)나 관제 측 실패 관측 통로가
     * 필요하다 — 둘 다 관제 계약 변경이라 별도 트랙이다.
     *
     * <h3>되살리기 4조건 (모두 만족해야 한다 — fail-closed)</h3>
     * <ol>
     *   <li><b>우리가 만든 행</b> — {@code RAW_FILE_PATH_NM} 이 인입 영역 하위다. 관제 행은 관제 NAS
     *       경로를 가리키므로 절대 매칭되지 않는다({@code SRC_TYPE} 은 폼에서 고를 수 있어 판별자가
     *       될 수 없다). <b>SQL 술어({@code FAILED} + {@code RAW_SN IS NULL})만으로는 관제 실패 행과
     *       구분되지 않으므로, 이 Java 판정이 유일한 신뢰 경계</b>다(가드 테스트가 호출부 단일성을 고정).</li>
     *   <li><b>종결됐고 적재된 적 없음</b> — {@code FAILED} + {@code RAW_SN IS NULL}. 실제 강제는
     *       {@code InternalUploadIngestWriter#reviveForUpload} 의 UPDATE 술어가 한다(TOCTOU 방어).</li>
     *   <li><b>파일 미도착</b> — 그 경로에 파일이 있으면 그 파일의 <b>주인 행</b>을 덮어쓰는 셈이라
     *       고아 PII 가 생긴다.</li>
     *   <li><b>진행 중 세션 없음</b> (DEV_FIX 2차 [A]) — 아래 참조.</li>
     * </ol>
     *
     * <h3>왜 "진행 중 세션 없음" 이 필요한가 — 종결 경로의 비대칭</h3>
     * <p>세션을 종결하는 경로(취소·TTL 만료·완료 검증 실패·도착 인계 실패)는 모두 세션과 인입 행을
     * <b>함께</b> 종결한다. 그런데 <b>미도착 대기 상한 종결</b>({@code TrainingVideoIngestTx})만은
     * 인입 행만 {@code FAILED} 로 내리고 세션을 모른다 — 세션 TTL(24h)보다 상한이 짧은 형상이면
     * <b>세션이 살아 있는 채로 행이 종결</b>된다. 그 행을 되살리면 같은 clipId 의 세션 <b>두 개</b>가
     * 동시에 살아 완료 순서에 따라 "S2 메타 + S1 파일" 같은 뒤섞임이 생긴다. 파일 대체 자체는 원자
     * 예약이 막지만(F3), 애초에 두 세션이 같은 이름을 노리는 상태를 만들지 않는 편이 옳다.
     *
     * @return 되살릴 행의 PK, 또는 과거 행이 아예 없으면 null(신규 INSERT)
     * @throws CustomException 되살릴 수 없는 행이 UK 를 점유 중이면 409
     */
    private Long resolveReusableIngestSn(String vmsClipId) {
        LsDataIngest existing = ingestRepository.findByVmsClipId(vmsClipId).orElse(null);
        if (existing == null) {
            return null;
        }
        if (isRevivableUploadRow(existing)) {
            if (hasLiveSession(vmsClipId)) {
                throw new CustomException(ErrorCode.CONFLICT,
                        "동일한 영상 클립 ID 의 업로드가 아직 진행 중입니다."
                                + " 기존 업로드를 완료하거나 취소한 뒤 다시 시도하세요.");
            }
            return existing.getRcptnSn();
        }
        throw new CustomException(ErrorCode.CONFLICT,
                "동일한 영상 클립 ID 의 인입 정보가 이미 존재합니다."
                        + " (처리 중이거나 이미 적재된 클립 ID 는 재사용할 수 없습니다)");
    }

    /** 그 클립 ID 로 <b>아직 살아 있는</b>(IN_PROGRESS) 업로드 세션이 있는가 — 되살리기 가드. */
    private boolean hasLiveSession(String vmsClipId) {
        return uploadRepository.countByVmsClipIdAndStatus(
                vmsClipId, LsTusUpload.STATUS_IN_PROGRESS) > 0;
    }

    private boolean isRevivableUploadRow(LsDataIngest row) {
        return pathResolver.isUploadAreaPath(row.getRawFilePathNm())
                && LsDataIngest.PRCS_STTS_FAILED.equals(row.getPrcsSttsCd())
                && row.getRawSn() == null
                && !ingestTerminator.fileArrived(row.getRawFilePathNm());
    }

    /**
     * 화면 입력 → 인입 수신 29컬럼 매핑.
     *
     * <p><b>서버가 이미 아는 값만</b> 기본값을 채운다: {@code VDO_FILE_NM}·{@code RAW_FILE_PATH_NM}
     * (저장 규약이 정한다) · {@code FILE_SZ}({@code Upload-Length}) · {@code FILE_FMT}(확장자).
     * 나머지 기술메타는 <b>사용자가 비우면 null 로 둔다</b> — 대용값을 넣으면 "틀린 값으로 확정"되고,
     * null 이어야 적재 후 {@code VideoMetaService} 가 그 키만 ffprobe 로 채운다(폴백은 키 단위).
     */
    private static InternalUploadIngestCommand toIngestCommand(InternalUploadCreateRequest req,
                                                               Path target, String extension,
                                                               long uploadLength) {
        return InternalUploadIngestCommand.builder()
                .vmsClipId(req.vmsClipId())
                .vmsCctvId(req.cctvId())
                .vdoFileNm(target.getFileName().toString())
                .rawFilePathNm(target.toString())
                .srcType(req.srcTypeOrDefault())
                .shtDt(req.shtDt())
                .vdoLenSec(req.vdoLenSec())
                .fileSz(req.fileSz() != null ? req.fileSz() : uploadLength)
                .fileFmt(StringUtils.hasText(req.fileFmt()) ? req.fileFmt() : extension)
                .vdoCdc(req.vdoCdc())
                .fps(req.fps())
                .frmeCnt(req.frmeCnt())
                .wdth(req.wdth())
                .vrtc(req.vrtc())
                .resl(req.resl())
                .asprtRt(req.asprtRt())
                .bit(req.bit())
                .pxl(req.pxl())
                .ogCd(req.ogCd())
                .cctvNm(req.cctvNm())
                .cctvHgt(req.cctvHgt())
                .mainSurvPanAng(req.mainSurvPanAng())
                .wgs84Lat(req.wgs84Lat())
                .wgs84Lot(req.wgs84Lot())
                .lclgvNm(req.lclgvNm())
                .evntId(req.evntId())
                .evntNm(req.evntNm())
                .mntrCn(req.mntrCn())
                .lclgvCd(req.lclgvCd())
                .build();
    }

    // ======================== HEAD — offset 조회 ========================

    /**
     * 세션 조회 (HEAD). 만료(410)/부재(404)/소유자 아님(403) 검증 후 반환.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public LsTusUpload getForOwner(UUID uploadId, String userNo) {
        LsTusUpload session = uploadRepository.findById(uploadId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        // HIGH-8: 소유자 검증.
        if (!session.isOwnedBy(userNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인의 업로드 세션이 아닙니다.");
        }
        // HIGH-5: 만료 세션 → 410.
        if (session.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.GONE, "업로드 세션이 만료되었습니다.");
        }
        return session;
    }

    // ======================== PATCH — 청크 append ========================

    /**
     * 청크 append. 동일 세션 동시 PATCH 는 PESSIMISTIC_WRITE 행 잠금으로 직렬화하고(HIGH-1),
     * 잠금 우회 경로의 동시 갱신은 @Version 으로 409 처리(이중 방어).
     *
     * @param uploadId      세션 ID
     * @param userNo        호출자 (소유자 검증)
     * @param expectedOffset 클라이언트가 보낸 Upload-Offset
     * @param chunk         청크 입력 스트림
     * @param contentLength 청크 길이 (Content-Length)
     * @return 청크 기록 후의 새 오프셋
     */
    @Transactional("controlTransactionManager")
    public TusPatchResult appendChunk(UUID uploadId, String userNo, long expectedOffset,
                                      InputStream chunk, long contentLength) {
        // HIGH-1: PATCH 진입 시 세션 행을 PESSIMISTIC_WRITE 로 잠가 동일 세션의 동시 PATCH 를
        // 직렬화한다. 잠금 보유 구간에서 offset 검사 + 파일 write + offset 갱신을 원자적으로
        // 수행해 두 청크가 같은 위치에 교차 write 하거나 충돌 측 truncate 가 정상 바이트를 자르는
        // 파일 오염을 차단한다.
        LsTusUpload session = uploadRepository.findByUploadIdForUpdate(uploadId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        if (!session.isOwnedBy(userNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인의 업로드 세션이 아닙니다.");
        }
        if (session.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.GONE, "업로드 세션이 만료되었습니다.");
        }
        // HIGH-3: 이미 완료된 세션에 마지막 청크 재전송 → 멱등 응답.
        //   Phase 1 이후 완료 산출물은 인입 행이고 그 PK 는 세션에 보관하지 않으므로(LS_TUS_UPLOAD 에
        //   컬럼이 없다) 재전송 응답에는 식별자를 싣지 않는다. TUS 클라이언트는 Upload-Offset 만 본다.
        if (session.isCompleted()) {
            return new TusPatchResult(session.getUploadLength(), true, null);
        }
        // HIGH-9: 빈 청크 거부 (Content-Length 0 → 400).
        if (contentLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "청크 본문이 비어 있습니다.");
        }
        // HIGH-2: 단일 PATCH 청크 크기 상한 → 413 (Content-Length 선검증, 본문 스트리밍은 별도 누적 추적).
        if (contentLength > maxChunkBytes) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "청크 크기가 허용 한도(" + maxChunkBytes + " bytes) 를 초과했습니다.");
        }
        // HIGH-9: 음수/초과 오프셋 → 400.
        if (expectedOffset < 0 || expectedOffset > session.getUploadLength()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Offset 값이 범위를 벗어났습니다.");
        }
        // HIGH-9: offset 불일치 → 409.
        if (expectedOffset != session.getUploadOffset()) {
            throw new CustomException(ErrorCode.CONFLICT, "Upload-Offset 이 서버 상태와 일치하지 않습니다.");
        }
        // HIGH-2(완료): 누적이 전체 길이를 넘으면 거부.
        if (expectedOffset + contentLength > session.getUploadLength()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "청크 길이가 잔여 용량을 초과합니다.");
        }

        // HIGH-1: 잠금 보유 구간에서 write → offset 전진 → 영속. truncate 도 잠금 보유 중에만 발생.
        long newOffset = writeChunkAtomically(session, expectedOffset, chunk, contentLength);
        session.advanceOffset(newOffset);

        try {
            // @Version 은 이중 방어 — 잠금 우회 경로가 있더라도 동시 갱신은 409.
            uploadRepository.saveAndFlush(session);
        } catch (OptimisticLockingFailureException e) {
            // 오프셋은 디스크에 기록됐으나 행 충돌 → truncate 로 일관성 복원 후 409.
            truncateTo(session.getFilePath(), expectedOffset);
            log.warn("[Tus] concurrent PATCH conflict uploadId={}", uploadId);
            throw new CustomException(ErrorCode.CONFLICT, "동시 업로드 요청이 충돌했습니다. 재시도하세요.");
        }

        if (session.isFullyUploaded()) {
            Long rcptnSn = complete(session);
            return new TusPatchResult(newOffset, true, rcptnSn);
        }
        return new TusPatchResult(newOffset, false, null);
    }

    // ======================== DELETE — 세션 취소 ========================

    /**
     * 세션 취소 — 임시 파일·세션 행을 지우고 <b>인입 행을 종결</b>한다 (Phase 3).
     *
     * <h3>왜 인입 행까지 종결하는가</h3>
     * <p>인입 행이 세션 생성 시점에 만들어지므로 취소하면 <b>파일이 영영 오지 않는</b> 행이 남는다.
     * 방치하면 미도착 대기 상한(기본 24시간) 동안 매 주기 재시도하다 결국 {@code FAILED} 로 쌓인다.
     * 관제 인입과 달리 <b>취소는 오류가 아니라 일상적 사용자 동선</b>이라 즉시 사유를 남기고 종결한다.
     *
     * <p><b>이미 완료된 세션은 인입 행을 건드리지 않는다</b> — 그 행은 파일이 실제로 도착해 적재를
     * 기다리는 정상 대기분이다. 여기서 종결시키면 업로드에 성공한 영상이 뒤늦게 사라진다.
     *
     * <h3>완료 <b>플래그</b>만으로는 부족하다 (DEV_FIX H2-b)</h3>
     * <p>완료 트랜잭션이 파일 이동 <b>뒤에</b> 롤백되면 세션은 {@code IN_PROGRESS} 인데 파일은 이미
     * 인입 영역에 있다. 그 상태로 행만 종결하면 <b>행은 죽고 파일은 남아</b> 아무도 지우지 않는
     * 비식별 전 원본이 된다. 그래서 종결 판정은 세션 플래그가 아니라 <b>파일 실재</b>를 진실원으로
     * 삼는 {@link InternalUploadIngestTerminator} 에 위임한다.
     */
    @Transactional("controlTransactionManager")
    public void cancel(UUID uploadId, String userNo) {
        // 시나리오 #11: cancel 도 PESSIMISTIC_WRITE 로 세션을 잠가 동일 세션의 PATCH 와 직렬화한다.
        // (PATCH 는 findByUploadIdForUpdate 로 잠금 획득 — 락 경로 통일로 취소·청크쓰기 교차를 차단.)
        LsTusUpload session = uploadRepository.findByUploadIdForUpdate(uploadId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        if (!session.isOwnedBy(userNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인의 업로드 세션이 아닙니다.");
        }
        deleteQuietly(session.getFilePath());
        if (!session.isCompleted()) {
            terminateIngestRow(session.getVmsClipId(), CANCEL_REASON, uploadId);
        }
        uploadRepository.delete(session);
        log.info("[Tus] session cancelled uploadId={}", uploadId);
    }

    /** 인입 행 종결 사유 — 사용자 취소(실패 아님). {@code ERR_MSG} 에 그대로 저장된다. */
    static final String CANCEL_REASON = "업로드 취소 — 파일이 도착하지 않아 종결";
    /** 인입 행 종결 사유 — 완료 시점 파일 검증 실패(영상 컨테이너 아님·재생 불가). */
    static final String INVALID_MEDIA_REASON = "업로드 파일 검증 실패 — 유효한 영상이 아님";

    /**
     * 인입 행을 사유와 함께 종결한다(best-effort) — <b>파일이 아직 도착하지 않은 경우에만</b>.
     *
     * <p>판정·실행은 {@link InternalUploadIngestTerminator} 한 곳이 담당한다(TTL 만료 정리 잡과 공유 —
     * 조건을 호출처마다 판단하면 반드시 갈라진다). 실패해도 원 흐름(취소 204 / 검증 실패 409)을
     * 가리지 않으며, 종결이 안 되면 미도착 대기 상한이 대신 종결시킨다(늦어질 뿐이다).
     */
    private void terminateIngestRow(String vmsClipId, String reason, UUID uploadIdForLog) {
        ingestTerminator.terminateIfFileAbsent(vmsClipId, reason, uploadIdForLog);
    }

    /** 세션의 클립 ID 로 인입 행 PK 를 역참조한다({@code VMS_CLIP_ID} 는 UK 라 최대 1행). */
    private Long findIngestSn(String vmsClipId) {
        if (!StringUtils.hasText(vmsClipId)) {
            return null;
        }
        return ingestRepository.findByVmsClipId(vmsClipId)
                .map(LsDataIngest::getRcptnSn)
                .orElse(null);
    }

    // ======================== 완료 — 파일 도착 확정 ========================

    /**
     * 업로드 완료 처리 — 파일을 <b>인입 행이 이미 가리키는 최종 경로</b>로 옮기고 backoff 를 푼다.
     *
     * <h3>Phase 3 — 인입 행은 여기서 만들지 않는다 (세션 생성 시점에 이미 있다)</h3>
     * <p>인입 테이블은 <b>"행 먼저, 파일 나중"</b>을 이미 견딘다 —
     * {@code TrainingVideoIngestTx#verifyPath} 가 {@code NOT_ARRIVED} 를 내면 실패로 종결하지 않고
     * {@code PENDING} 복귀 + backoff 로 다시 본다(파일 대기는 실패가 아니다). 그래서 세션 생성
     * 시점에 인입 행을 만들어 두고 여기서는 <b>파일을 그 자리에 놓는 일</b>만 한다.
     *
     * <h3>왜 {@code NXTM_RTRY_DT} 를 지금으로 당기는가 (필수)</h3>
     * <p>미도착 backoff 는 <b>"지금까지 기다린 만큼 더"</b>(경과 기반, 1분~1시간)다. 20분짜리 업로드는
     * 그동안 backoff 가 20분까지 벌어져 있어, <b>파일이 도착한 뒤에도 최대 20분을 더 기다린다</b>.
     * 도착 사실을 아는 주체는 여기뿐이므로 예정 시각을 당겨 다음 tick 이 곧바로 집게 한다.
     *
     * <h3>실패 모델 — 회수 여부는 "<b>살아 있는 주인 행이 있는가</b>"로 갈린다</h3>
     * <p>Phase 1 은 인입 행을 <b>나중에</b> 만들었기 때문에 "행 없는 파일"(= 아무도 지우지 않는 비식별
     * 전 원본)이 항상 가능했고 그래서 무조건 보상 삭제했다. Phase 3 에서는 보통 그 파일을 가리키는
     * 인입 행이 이미 커밋돼 있으므로 <b>지우는 쪽이 결함</b>이다(폴링이 영원히 미도착 대기 → 상한
     * 초과 FAILED). 그러나 <b>항상 그런 것은 아니다</b> — 도착을 알리는 순간 그 행이 이미 종결
     * ({@code FAILED})돼 있으면 다시 "주인 없는 파일"이 된다. 그래서 회수는
     * {@link #handleArrivalNotAcknowledged} 가 행 상태를 보고 결정한다(DEV_FIX H2).
     *
     * <p><b>검증 실패</b>(매직바이트·재생 불가)는 파일이 영영 오지 않을 것이 확정되므로 인입 행을
     * 즉시 종결한다(파일은 임시 영역에서 삭제되고 인입 영역에는 애초에 놓이지 않는다).
     *
     * @return 이 업로드의 인입 행 PK({@code RCPTN_SN}). 찾지 못하면 null
     */
    private Long complete(LsTusUpload session) {
        Path temp = Paths.get(session.getFilePath());
        // HIGH-6: 완료 시 매직바이트 검증 — 실패 시 임시파일 삭제 + 409.
        if (!VideoMagicByteValidator.isVideoContainer(temp)) {
            deleteQuietly(session.getFilePath());
            failCompletion(session, "magic-byte");
            log.warn("[Tus] magic byte check failed uploadId={}", session.getUploadId());
            throw new CustomException(ErrorCode.CONFLICT,
                    "업로드된 파일이 유효한 영상 컨테이너가 아닙니다.");
        }
        // 재생 가능성 검증 — 손상·미지원 파일을 적재 대기열에 넣지 않기 위한 하드 게이트다.
        //   ★Phase 2: 그 측정 결과를 <버리지 않고> 인입 back-fill 에 재사용한다(ffprobe 1회).
        MediaMeta measured = verifyPlayable(temp, session);

        String extension = resolveExtension(session.getFileName());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            // 세션 생성 시 이미 걸러진 값이라 도달 불가 — 도달했다면 세션 데이터 변조 신호다(fail-closed).
            log.error("[Tus] stored session has unsupported extension uploadId={}", session.getUploadId());
            throw new CustomException(ErrorCode.CONFLICT, "업로드 세션의 파일 형식이 유효하지 않습니다.");
        }
        Path target = pathResolver.resolveUploadTarget(session.getVmsClipId(), extension);
        // ★파일 이동 <이전>에 채운다 — 두 가지 이유가 겹친다.
        //   ①기능: 옮긴 뒤에 채우면 폴링이 먼저 파일을 보고 LS_DATA_RAW 를 만들어 NULL 인 채로
        //     복사해 가므로 back-fill 이 무의미해진다(순서 회귀 가드 있음).
        //   ②잠금 순서(CWE-833): back-fill 은 REQUIRES_NEW 로 인입 행 락을 새로 잡으므로
        //     markUploadArrived(같은 행, 호출자 트랜잭션) <뒤>로 밀면 교착한다(그쪽 Javadoc 참조).
        Long backfilledRcptnSn = backfillIngestMeta(session, measured);
        moveIntoIngestArea(temp, target, session.getUploadId(), backfilledRcptnSn);

        // MED-1: 완료 전이를 DB 조건부 UPDATE(WHERE STATUS='IN_PROGRESS')로 강제.
        //   affectedRows==0 이면 다른 트랜잭션이 이미 완료시킨 것이므로 인입 행을 건드리지 않고
        //   멱등 응답한다(예정 시각을 두 번 당기지 않는다).
        int transitioned = uploadRepository.markCompletedIfInProgress(
                session.getUploadId(), null, LocalDateTime.now());
        Long rcptnSn = findIngestSn(session.getVmsClipId());
        if (transitioned != 1) {
            log.info("[Tus] completion already claimed uploadId={}", session.getUploadId());
            return rcptnSn;
        }
        markIngestArrived(session, rcptnSn, target);
        return rcptnSn;
    }

    /**
     * 파일 도착을 인입 행에 알린다 — 미도착 backoff 해제(그 외 컬럼은 건드리지 않는다).
     *
     * <h3>0행은 <b>정상이 아니다</b> (DEV_FIX H2 — 구 javadoc 정정)</h3>
     * <p>구 구현은 0행을 INFO 로 흘리며 "파일은 이미 제자리에 있으므로 정상 처리된다"고 적었는데
     * <b>사실이 아니다</b>. {@code markUploadArrived} 의 술어는 {@code PRCS_STTS_CD='PENDING'} 이라
     * 0행의 의미가 셋으로 갈린다.
     * <ul>
     *   <li>{@code PROCESSING} — 폴링이 그 순간 클레임 중. <b>행은 살아 있다</b>(파일도 이미 있으니
     *       다음 주기에 적재된다). 이 실행의 backoff 리셋은 유실될 수 있으나, 폴링의 미도착 복귀는
     *       같은 tick 안(수 ms)에 커밋되므로 <b>짧게 재시도</b>하면 대부분 회수된다(M5 — 아래 참조).</li>
     *   <li>{@code DONE} — 폴링이 그 사이 파일을 보고 이미 적재했다. 정상 종착지다.</li>
     *   <li>{@code FAILED}(또는 행 소멸) — <b>아무도 이 파일을 적재하지 않는다.</b> 인입 영역에
     *       비식별 전 원본만 남고 사용자에게는 204 가 나간다(구 동작). 이 경우만 보상이 필요하다.</li>
     * </ul>
     */
    private void markIngestArrived(LsTusUpload session, Long rcptnSn, Path target) {
        UUID uploadId = session.getUploadId();
        if (rcptnSn == null) {
            // 인입 행 없이 완료된 세션 — 세션 생성 시 INSERT/되살리기가 유일 통로라 도달 불가.
            //   도달했다면 그 파일을 참조하는 행이 <아예 없다>는 뜻이라 가장 나쁜 형태의 고아다.
            log.error("[Tus] completed upload has no ingest row — 적재되지 않는다 uploadId={}", uploadId);
            failArrival(session, target, "ingest-row-missing");
            return;
        }
        int rows;
        try {
            rows = ingestRepository.markUploadArrived(rcptnSn, LocalDateTime.now());
        } catch (RuntimeException e) {
            // UPDATE 자체가 실패했다 — 행 상태는 그대로이므로 회수 대상이 아니다.
            //   다음 backoff 만료 시 픽업된다(늦어질 뿐 유실이 아니다).
            log.warn("[Tus] ingest retry reset failed uploadId={} rcptnSn={} causeType={}",
                    uploadId, rcptnSn, e.getClass().getSimpleName());
            return;
        }
        if (rows == 1) {
            log.info("[Tus] ingest row ready for polling uploadId={} rcptnSn={}", uploadId, rcptnSn);
            return;
        }
        handleArrivalNotAcknowledged(session, rcptnSn, target);
    }

    /**
     * 도착 통지가 0행일 때의 분기 — <b>살아 있는 주인이 있는가</b>로만 판단한다.
     *
     * <p>회수(파일 삭제)와 행 종결은 <b>한 짝</b>이다. 행이 살아 있는데 파일을 지우면 그 인입은 영원히
     * 미도착 대기하다 상한 초과로 종결되고(정상 업로드 소실), 반대로 행이 종결됐는데 파일을 남기면
     * 아무도 지우지 않는 비식별 전 원본이 된다(CWE-359).
     */
    private void handleArrivalNotAcknowledged(LsTusUpload session, Long rcptnSn, Path target) {
        String state = ingestStateOf(rcptnSn);
        if (LsDataIngest.PRCS_STTS_PROCESSING.equals(state)) {
            state = retryArrivalWhileProcessing(session.getUploadId(), rcptnSn);
        }
        if (LsDataIngest.PRCS_STTS_PENDING.equals(state)) {
            // 재시도로 회수됐거나(대개) 폴링이 그 사이 미처리로 되돌렸다 — 둘 다 살아 있는 정상 대기분.
            log.info("[Tus] ingest row ready for polling after retry uploadId={} rcptnSn={}",
                    session.getUploadId(), rcptnSn);
            return;
        }
        if (LsDataIngest.PRCS_STTS_PROCESSING.equals(state)) {
            // 살아 있다 — 파일도 제자리에 있으므로 적재된다. 다만 backoff 리셋은 유실됐다(M5).
            log.warn("[Tus] arrival reset lost — 폴링이 클레임 중이라 예정 시각이 다시 밀릴 수 있다"
                    + "(적재는 되며 최대 backoff 상한만큼 늦어진다) uploadId={} rcptnSn={}",
                    session.getUploadId(), rcptnSn);
            return;
        }
        if (LsDataIngest.PRCS_STTS_DONE.equals(state)) {
            // 폴링이 그 사이 파일을 보고 이미 적재했다 — 정상 종착지. 파일은 LS_DATA_RAW 가 참조한다.
            log.info("[Tus] ingest row already ingested on arrival uploadId={} rcptnSn={}",
                    session.getUploadId(), rcptnSn);
            return;
        }
        // FAILED(미도착 상한 초과·취소 등) 또는 행 소멸 — 이 파일을 적재할 주체가 없다.
        log.warn("[Tus] ingest row not accepting arrival uploadId={} rcptnSn={} state={}",
                session.getUploadId(), rcptnSn, state);
        failArrival(session, target, "ingest-row-terminated");
    }

    /** {@code PROCESSING} 재시도 횟수 — 폴링의 미도착 복귀가 커밋되기를 기다리는 짧은 창(M5). */
    private static final int ARRIVAL_RESET_RETRIES = 3;
    /** {@code PROCESSING} 재시도 간격(ms) — 세션 행 잠금 보유 구간이라 총 대기를 100ms 내로 묶는다. */
    private static final long ARRIVAL_RESET_RETRY_DELAY_MS = 30L;

    /**
     * {@code PROCESSING} 관측 시 <b>짧은 bounded-retry</b> 로 도착 통지를 회수한다 (DEV_FIX 2차 [E]).
     *
     * <h3>"스키마 없이는 불가"는 과장이었다 — 확률적으로 회수된다</h3>
     * <p>구 주석은 이 유실을 구조적으로 회수 불가라고 적었지만 사실이 아니다. 폴링이 클레임한 뒤
     * 파일을 확인하고 {@code revertToPendingForRetry} 로 {@code PENDING} 을 커밋하기까지는 <b>같은
     * tick 안의 수 ms</b> 이고, 우리 UPDATE 는 술어({@code PENDING})가 있어 <b>멱등</b>하다. 즉 몇십
     * 밀리초만 다시 시도하면 대부분 회수되고, 실패해도 결과는 구 동작과 <b>동일</b>하다(비용이 작고
     * 더 나빠지지 않는다). 다만 <b>보장</b>은 아니다 — 회수 확률을 올릴 뿐이다.
     *
     * <p>재시도 중 행이 {@code DONE}/{@code FAILED} 로 바뀌면 즉시 그 상태를 돌려주어 호출부가 정상
     * 분기(적재 완료 / 파일 회수)를 타게 한다.
     *
     * @return 재시도 후 관측된 인입 상태(회수 성공 시 {@code PENDING})
     */
    private String retryArrivalWhileProcessing(UUID uploadId, Long rcptnSn) {
        String state = LsDataIngest.PRCS_STTS_PROCESSING;
        for (int attempt = 1; attempt <= ARRIVAL_RESET_RETRIES; attempt++) {
            if (!sleepQuietly(ARRIVAL_RESET_RETRY_DELAY_MS)) {
                return state;
            }
            try {
                if (ingestRepository.markUploadArrived(rcptnSn, LocalDateTime.now()) == 1) {
                    log.info("[Tus] ingest row ready for polling after retry uploadId={} rcptnSn={}"
                            + " attempt={}", uploadId, rcptnSn, attempt);
                    return LsDataIngest.PRCS_STTS_PENDING;
                }
                state = ingestStateOf(rcptnSn);
            } catch (RuntimeException e) {
                // UPDATE/조회 실패 — 행 상태는 그대로다. 더 시도하지 않고 관측된 상태로 돌아간다.
                log.warn("[Tus] arrival retry failed uploadId={} rcptnSn={} causeType={}",
                        uploadId, rcptnSn, e.getClass().getSimpleName());
                return state;
            }
            if (!LsDataIngest.PRCS_STTS_PROCESSING.equals(state)) {
                return state;
            }
        }
        return state;
    }

    /** 인입 행의 현재 처리상태(행이 없으면 null). */
    private String ingestStateOf(Long rcptnSn) {
        return ingestRepository.findById(rcptnSn)
                .map(LsDataIngest::getPrcsSttsCd)
                .orElse(null);
    }

    /** @return 정상 대기했으면 true, 인터럽트되면 false(플래그 복원 후 재시도 중단) */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 도착 인계 실패의 뒷정리 — <b>옮긴 파일을 회수하고 사용자에게 성공을 주지 않는다</b>.
     *
     * <p>세션 종결은 {@link #terminateSessionAfterCompletion} 으로 미룬다 — 이 경로는 곧 예외로
     * 롤백되므로 같은 트랜잭션에서 상태를 바꾸면 함께 되돌아가고, 임시 파일은 이미 옮겨져 없기
     * 때문에 재시도가 500 으로 맴돈다.
     */
    private void failArrival(LsTusUpload session, Path target, String reasonTag) {
        discardMovedFile(target, session.getUploadId(), reasonTag);
        terminateSessionAfterCompletion(session.getUploadId());
        throw new CustomException(ErrorCode.CONFLICT,
                "업로드 인입 정보가 이미 종결되어 이 업로드를 인계할 수 없습니다."
                        + " 새 영상 클립 ID 로 다시 시도하세요.");
    }

    /**
     * 완료 시점 검증 실패의 뒷정리 — 세션과 인입 행을 <b>둘 다</b> 종결한다.
     *
     * <p>이 경로는 곧 예외로 롤백되므로 세션 종결은 트랜잭션 완료 후 별도 트랜잭션으로 미루고
     * ({@link #terminateSessionAfterCompletion} — 같은 행을 잠그고 있어 지금 하면 교착),
     * 인입 행 종결은 세션 행과 무관한 다른 테이블이라 {@code REQUIRES_NEW} 로 지금 커밋한다.
     */
    private void failCompletion(LsTusUpload session, String reasonTag) {
        terminateSessionAfterCompletion(session.getUploadId());
        terminateIngestRow(session.getVmsClipId(), INVALID_MEDIA_REASON, session.getUploadId());
        log.warn("[Tus] completion rejected uploadId={} reason={}", session.getUploadId(), reasonTag);
    }

    /**
     * 재생 가능성 검증 <b>+ 측정값 조달</b> — ffprobe 를 <b>1회</b> 돌려 게이트와 back-fill 이 함께 쓴다.
     *
     * <p>손상·미지원 파일이 적재 대기열에 들어가면 비식별·프레임추출이 모두 실패하므로 입구에서
     * 걸러낸다. 실패는 파일이 영영 유효해지지 않는다는 뜻이라 인입 행도 함께 종결한다.
     *
     * <h3>게이트 축은 <b>길이 하나</b>다 (기존 동작 보존)</h3>
     * <p>측정 실패(예외·전량 미상)와 길이 범위 이탈만 409 로 떨어뜨린다. 해상도·코덱·프레임수 등
     * <b>부가 필드가 미상인 것은 게이트를 건드리지 않는다</b> — 컨테이너가 신고하지 않는 값이 있다고
     * 정상 영상의 업로드를 실패시키면 안 되기 때문이다("부가 필드가 null 이면 예외" 같은 검사를
     * 새로 넣지 말 것).
     *
     * <p>★측정 대상은 <b>{@code temp}</b>(우리가 UUID 로 만들어 소유한 임시 파일)다. 인입 영역으로
     * 옮긴 뒤의 {@code target} 을 다시 probe 하면 공유 마운트에서 최종 컴포넌트를 심링크로 바꿔치기할
     * 창이 열린다(CWE-59/367). {@code temp} 에는 그 표면이 없다.
     *
     * @return 측정 원시값(개별 필드 미상은 null — 채택 판정은 {@link InternalUploadMetaResolver})
     */
    private MediaMeta verifyPlayable(Path temp, LsTusUpload session) {
        try {
            MediaMeta measured = probeMedia(temp, session.getUploadId());
            int durationSec = requireValidDurationSec(measured);
            log.debug("[Tus] media verified uploadId={} durationSec={}",
                    session.getUploadId(), durationSec);
            return measured;
        } catch (CustomException e) {
            deleteQuietly(session.getFilePath());
            failCompletion(session, "probe");
            throw e;
        }
    }

    /**
     * 인입 행의 <b>비어 있는</b> 기술메타를 측정값으로 채운다 (Phase 2 — best-effort).
     *
     * <h3>흡수하는 실패는 <b>writer 호출(DB 쓰기)</b> 하나다 (범위 정정)</h3>
     * <p>구 Javadoc 은 "실패해도 업로드 완료를 막지 않는다"라고 <b>메서드 전체</b>를 주장했으나
     * 사실이 아니다 — {@code try} 가 감싸는 것은 {@code backfillMeasuredMeta} 한 줄이고, 그 앞의
     * 인입 행 조회·경로 판정·측정값 해석에서 예외가 나면 <b>완료 자체가 실패</b>한다.
     *
     * <p><b>범위를 넓히지 않고 서술을 좁힌 이유</b>(DEV_FIX 선택 근거) — 앞단 조회는 <b>호출자
     * 트랜잭션</b>에서 도는데, 거기서 DB 예외가 나는 순간 PostgreSQL 이 그 트랜잭션 <b>전체를
     * abort</b> 시킨다. 그 예외를 여기서 삼켜도 커넥션은 이미 오염돼 뒤따르는
     * {@code markCompletedIfInProgress}·{@code markUploadArrived} 가 어차피 실패하므로, 삼키는 것은
     * 완료를 구제하지 못하고 <b>원인만 가린다</b>(뒤늦게 무관한 지점에서 500 이 난다). 반대로 writer
     * 호출은 {@code REQUIRES_NEW} 로 격리돼 있어(그쪽 Javadoc 참조) 실패해도 <b>inner 트랜잭션만</b>
     * 죽고 호출자 커넥션은 멀쩡하다 — 그래서 <b>그 한 줄만</b> 흡수하는 것이 옳다.
     *
     * <h3>★관제 행 보호 — 신뢰 경계는 여기에만 있다 (CWE-915)</h3>
     * <p>SQL 술어({@code PENDING} + {@code RAW_SN IS NULL})는 <b>관제가 넣은 미처리 행에도 맞는다</b>.
     * 우리 행과 관제 행을 가르는 것은 오직 {@link InternalUploadPathResolver#isUploadAreaPath}
     * (관제 행은 관제 NAS 경로를 가리킨다) 판정이며, {@code reviveForUpload} 와 <b>동일한</b> 기준이다.
     * 이 판정 없이 back-fill 을 부르는 통로가 생기면 관제 수신 원장을 우리가 갱신하게 된다 —
     * 구조 가드가 호출부 단일성을 고정한다.
     *
     * <h3>★잠금 순서 — 이 호출은 {@code markUploadArrived} <b>앞</b>에만 있어야 한다 (CWE-833)</h3>
     * <p>writer 는 {@code REQUIRES_NEW}(별도 커넥션)로 {@code LS_DATA_INGEST} <b>행 락</b>을 새로
     * 잡는다. 지금은 호출자 트랜잭션이 그 테이블을 아직 건드리지 않은 구간이라 안전하지만, 이 호출을
     * {@code markUploadArrived}(호출자 트랜잭션, <b>같은 행</b> UPDATE) <b>뒤로</b> 옮기면 inner 가
     * outer 가 쥔 행 락을 기다리고 outer 는 inner 의 완료를 기다려 <b>멈춘다</b>.
     *
     * <p><b>★이것은 PostgreSQL 이 검출해 주는 교착이 아니다 (실측 정정 — 구 서술 폐기)</b>. 구 Javadoc 은
     * "{@code deadlock_timeout} 후 한쪽 abort" 라고 적었으나, 두 트랜잭션이 <b>같은 애플리케이션
     * 스레드</b>에 중첩돼 있어 DB 는 순환을 보지 못한다. 실측(back-fill 을 {@code markIngestArrived}
     * 뒤로 옮긴 mutation): 해당 테스트가 <b>30.4초</b> 걸린 뒤 락 타임아웃으로 예외
     * ({@code causeType=UncategorizedSQLException})가 나고, 그것을 best-effort catch 가 <b>조용히
     * 삼켜</b> 업로드는 성공하는데 <b>메타는 영구 {@code null}</b> 로 끝났다. 그동안 커넥션 2개를
     * 점유하므로 운영 형상에서는 <b>업로드마다 30초 커넥션 점유</b> = 풀 고갈 경로다.
     *
     * <p>{@link #terminateSessionAfterCompletion} 이 세션 테이블에 대해 같은 함정을 다루는 것과 동일한
     * 축이며, {@code complete()} 의 배선 순서(back-fill → 파일 이동 → 완료 전이 → 도착 통지)는
     * <b>기능적 이유와 이 잠금 순서를 함께</b> 만족시킨다. 순서 회귀 가드
     * ({@code backfillRunsBeforeFileIsMovedIntoIngestArea})가 고정한다.
     *
     * @return 이 세션의 인입 행 PK — <b>상관관계 로그 전용</b>(조회 전 조기 반환·행 부재면 null).
     * 뒤따르는 파일 이동이 실패했을 때 back-fill 로그와 이어 붙이기 위한 것이며 흐름 판단에 쓰지 않는다.
     * @req R1
     */
    private Long backfillIngestMeta(LsTusUpload session, MediaMeta measured) {
        UUID uploadId = session.getUploadId();
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(measured);
        if (resolved.isEmpty()) {
            // 측정에서 채택된 값이 하나도 없다 — UPDATE 를 돌려도 모든 인자가 null 이라 무의미하다.
            log.debug("[Tus] no measured meta to back-fill uploadId={}", uploadId);
            return null;
        }
        LsDataIngest row = StringUtils.hasText(session.getVmsClipId())
                ? ingestRepository.findByVmsClipId(session.getVmsClipId()).orElse(null)
                : null;
        if (row == null) {
            // 인입 행 부재는 완료 흐름 자체의 이상 신호다 — markIngestArrived 가 뒤에서 다룬다.
            log.info("[Tus] ingest row not found for back-fill uploadId={}", uploadId);
            return null;
        }
        if (!pathResolver.isUploadAreaPath(row.getRawFilePathNm())) {
            // 우리가 만든 행이 아니다(관제 행) — 관제 수신 원장은 우리가 갱신하지 않는다.
            log.warn("[Tus] ingest row is not ours — back-fill skipped uploadId={} rcptnSn={}",
                    uploadId, row.getRcptnSn());
            return row.getRcptnSn();
        }
        try {
            int rows = ingestWriter.backfillMeasuredMeta(row.getRcptnSn(), resolved);
            // 0행 = 폴링 클레임 중·종결·기적재. 셋 다 "이번엔 채우지 않는다"가 옳은 결과다.
            log.info("[Tus] ingest meta back-fill uploadId={} rcptnSn={} rows={}",
                    uploadId, row.getRcptnSn(), rows);
        } catch (RuntimeException e) {
            log.warn("[Tus] ingest meta back-fill failed uploadId={} rcptnSn={} causeType={}",
                    uploadId, row.getRcptnSn(), e.getClass().getSimpleName());
        }
        return row.getRcptnSn();
    }

    /**
     * 인입 UK({@code VMS_CLIP_ID}) 위반만 409 로 좁힌다 — PostgreSQL 유니크 위반 SQLState {@code 23505}.
     *
     * <p>구 구현은 모든 {@code DataIntegrityViolationException} 을 "클립 ID 중복"으로 응답해, 경로 길이
     * 초과·NOT NULL 위반({@code 23502})·체크 제약 위반까지 같은 메시지로 덮어 원인 규명을 막았다(F6).
     */
    private static boolean isUniqueViolation(RuntimeException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        if (!(e instanceof DataAccessException dae)) {
            return false;
        }
        Throwable specific = dae.getMostSpecificCause();
        return specific instanceof SQLException sql
                && SQL_STATE_UNIQUE_VIOLATION.equals(sql.getSQLState());
    }

    /**
     * 인입 영역으로 <b>이미 옮겨진</b> 파일을 회수한다(best-effort) — 참조할 인입 행이 없는 고아 방지.
     *
     * <p>삭제 실패는 조용히 넘기지 않고 WARN 으로 남긴다 — 그 순간부터 그 파일은 <b>아무도 지우지
     * 않는 비식별 전 원본</b>이므로 운영자가 알아야 한다(개인정보 보존기간). 삭제 대상은 우리가 방금
     * 만든 인입 영역 경로뿐이며, 삭제 직전 고정 allowlist 로 다시 판정한다(CWE-22 심층방어).
     */
    private void discardMovedFile(Path target, UUID uploadIdForLog, String reason) {
        try {
            pathResolver.verifyIngestable(target);
            boolean deleted = Files.deleteIfExists(target);
            log.warn("[Tus] moved file discarded uploadId={} reason={} deleted={}",
                    uploadIdForLog, reason, deleted);
        } catch (IOException | RuntimeException e) {
            log.warn("[Tus] moved file discard FAILED — 인입 영역에 비식별 전 원본이 남았을 수 있다."
                            + " 수동 확인 필요 uploadId={} reason={} causeType={}",
                    uploadIdForLog, reason, e.getClass().getSimpleName());
        }
    }

    /**
     * 세션을 <b>트랜잭션 완료 후</b> 별도 트랜잭션으로 종결한다 (F2).
     *
     * <p>이 메서드가 불리는 경로는 모두 곧 롤백된다 — 같은 트랜잭션에서 상태를 바꿔 봐야 함께 되돌아가
     * 세션이 {@code IN_PROGRESS} 로 부활하고, 클라이언트 재시도가 <b>이미 사라진 임시 파일</b>에
     * 이어쓰기를 시도한다(현재는 500, 원래 의도는 410 종결).
     *
     * <p>그렇다고 여기서 바로 {@code REQUIRES_NEW} UPDATE 를 날리면 <b>교착</b>한다 — 현재 트랜잭션이
     * 같은 행을 {@code PESSIMISTIC_WRITE} 로 잡고 있는데 별도 커넥션이 그 행을 UPDATE 하려 들기
     * 때문이다. 그래서 락이 풀리는 <b>트랜잭션 완료 직후</b>로 미룬다. 트랜잭션이 없는 문맥(단위
     * 테스트·직접 호출)에서는 즉시 수행한다.
     */
    private void terminateSessionAfterCompletion(UUID uploadId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            terminateSessionQuietly(uploadId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                terminateSessionQuietly(uploadId);
            }
        });
    }

    /** 세션 종결 — 실패해도 원 예외(409/500)를 가리지 않도록 삼키고 WARN 만 남긴다. */
    private void terminateSessionQuietly(UUID uploadId) {
        try {
            int terminated = uploadRepository.terminateSession(uploadId, LocalDateTime.now());
            log.info("[Tus] session terminated after failed completion uploadId={} rows={}",
                    uploadId, terminated);
        } catch (RuntimeException e) {
            log.warn("[Tus] session terminate failed uploadId={} causeType={}",
                    uploadId, e.getClass().getSimpleName());
        }
    }

    /**
     * 임시 파일 → 인입 영역 이동. 같은 파일시스템이면 원자적 rename, 다른 마운트면 <b>staging 복사 후
     * 원자 rename</b>.
     *
     * <h3>★ 인입 경로에는 <b>완성된 파일만</b> 나타나야 한다 (DEV_FIX H1)</h3>
     * <p>인입 행이 이 경로를 가리킨 채 <b>먼저 커밋</b>돼 있고 폴링은 최대 60초마다 그 경로를 본다.
     * 그래서 이 경로에 잠깐이라도 <b>미완성 파일</b>이 보이면 폴링이 그것을 영상으로 적재한다
     * ({@code LS_DATA_RAW} 생성 + {@code VideoIngestedEvent} → 비식별 파이프라인이 빈 파일로 기동,
     * 인입 행은 {@code DONE} 이라 재큐 통로조차 없다).
     *
     * <h3>★ 이름 선점은 <b>원자적</b>이어야 한다 (DEV_FIX 2차 [A] — F3 복원)</h3>
     * <p>{@code Files.exists(target)} 로 검사한 뒤 옮기는 구현(1차)은 <b>검사 후 사용</b>이었다 —
     * {@code ATOMIC_MOVE} 는 대상이 있으면 조용히 대체하므로, 검사와 이동 사이에 다른 주체가 그 이름을
     * 만들면 <b>남의 영상을 말없이 덮고 양쪽 다 204</b> 다. "인입 UK 가 두 번째 세션을 막으니 경합이
     * 없다"는 근거도 성립하지 않았다: 미도착 대기 상한 종결은 인입 행만 {@code FAILED} 로 내리고
     * <b>세션은 살려 두므로</b>, 같은 clipId 의 되살리기로 세션 두 개가 동시에 살아 있을 수 있었다
     * (그 비대칭은 {@link #resolveReusableIngestSn} 의 진행 중 세션 가드로 함께 닫았다).
     *
     * <p>그래서 선점은 {@link InternalUploadPathResolver#reserveIngestTarget}({@code O_EXCL})로 되돌린다.
     * H1(0바이트 노출)과 양립한다 — 예약이 만드는 0바이트는 적재 측 <b>완결성 게이트</b>
     * ({@code TrainingVideoIngestTx#isEmptyFile})가 {@code NOT_ARRIVED} 로 떨어뜨리고, 회수 실패로
     * 잔존해도 그 게이트가 계속 막는다. 이동 실패 시에는 예약을 <b>즉시 회수</b>한다.
     *
     * <p><b>copy 폴백도 원자적으로</b>: 다른 마운트면 rename 이 불가하므로 같은 디렉터리의 <b>은닉
     * staging 이름</b>으로 복사한 뒤 그 안에서 rename 한다(같은 디렉터리 = 같은 파일시스템이라 원자).
     * 폴링은 인입 행이 가리키는 <b>정확한 경로</b>만 보므로 staging 파일은 보이지 않는다.
     *
     * <h3>{@code rcptnSnForLog} — 부분 쓰기 상관관계용 (로그 전용)</h3>
     * <p>직전 back-fill 은 {@code REQUIRES_NEW} 라 <b>이미 커밋</b>돼 있다. 그래서 여기서 실패하면
     * 인입 행에는 <b>파일이 끝내 놓이지 않은 영상의 측정 메타</b>가 남는다(재업로드 시 되살리기가
     * 덮으므로 기능상 무해). 실패 로그에 그 행의 PK 가 없으면 back-fill 성공 로그와 이어 붙일 수 없어
     * 사후 조사가 막히므로 상관관계 키만 싣는다 — 판단에는 쓰지 않으며({@code null} 일 수 있다),
     * 경로·PII 는 종전대로 남기지 않는다({@code causeType} 관례 유지).
     */
    private void moveIntoIngestArea(Path temp, Path target, UUID uploadIdForLog,
                                    Long rcptnSnForLog) {
        // ① 이름 선점(원자) — 여기서부터 이 이름은 이 실행의 것이다.
        //   CWE-367/59 — 예약도 쓰기이므로 <고정 allowlist(raw-mount-roots)> 기준 실경로 재판정이
        //   선행된다(resolver 내부). 구 구현은 target.getParent() 를 uploadDir(=자기 자신) 기준으로
        //   검사해 항등식이었고, 경로 중간 디렉터리를 심링크로 교체하면 통과했다(F1).
        try {
            Files.createDirectories(target.getParent());
            pathResolver.reserveIngestTarget(target);
        } catch (FileAlreadyExistsException e) {
            // 이미 다른 주체(다른 세션·수기 파일·잔여물)가 그 이름을 점유했다 — 조용한 대체 금지.
            log.warn("[Tus] ingest target already reserved uploadId={} rcptnSn={}",
                    uploadIdForLog, rcptnSnForLog);
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일한 영상 클립 ID 의 파일이 이미 저장되어 있습니다.");
        } catch (IOException e) {
            log.error("[Tus] ingest target reservation failed uploadId={} rcptnSn={} causeType={}",
                    uploadIdForLog, rcptnSnForLog, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 파일 저장에 실패했습니다.");
        }
        // ② 예약분을 <내용이 든 파일>로 원자 교체. POSIX rename 은 대상(=우리 예약)을 대체한다.
        try {
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                moveAcrossMounts(temp, target, uploadIdForLog);
            }
        } catch (IOException | RuntimeException e) {
            // ★예약분 회수 — 남기면 0바이트 잔여물이 그 clipId 를 잠근다(재업로드가 완료 시점에 409).
            //   회수까지 실패하면 잔여물은 <0바이트>이므로 적재 완결성 게이트가 계속 막는다(H1).
            discardMovedFile(target, uploadIdForLog, "move-failed");
            if (e instanceof RuntimeException runtime) {
                log.warn("[Tus] move into ingest area failed uploadId={} rcptnSn={} causeType={}",
                        uploadIdForLog, rcptnSnForLog, runtime.getClass().getSimpleName());
                throw runtime;
            }
            log.error("[Tus] move into ingest area failed uploadId={} rcptnSn={} causeType={}",
                    uploadIdForLog, rcptnSnForLog, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 파일 저장에 실패했습니다.");
        }
    }

    /**
     * 다른 마운트 폴백 — <b>staging 복사 → 같은 디렉터리 내 원자 rename</b> (DEV_FIX H1).
     *
     * <p>{@code Files.copy} 를 최종 경로에 직접 하면 복사가 진행되는 동안(대용량은 수십 초)
     * <b>부분 복사본</b>이 인입 경로에 노출된다. staging 이름은 인입 행이 가리키는 경로가 아니므로
     * 폴링에 보이지 않고, 복사가 끝난 뒤의 rename 만이 최종 경로를 <b>완성된 상태로</b> 등장시킨다.
     *
     * <p>staging 이름은 점(.) 으로 시작해 은닉하고 {@code uploadId} 로 유일성을 준다. 실패 시 그
     * 잔여물을 회수한다(최종 경로의 <b>예약분</b> 회수는 호출부가 한다).
     *
     * <p>최종 rename 대상에는 이 실행이 이미 <b>예약(O_EXCL)</b> 해 둔 0바이트 파일이 있으므로
     * {@code ATOMIC_MOVE} 가 그것을 대체한다 — 즉 이 경로에서도 이름 선점은 여전히 원자적이다.
     */
    private void moveAcrossMounts(Path temp, Path target, UUID uploadIdForLog) throws IOException {
        Path staging = target.resolveSibling("." + target.getFileName() + ".part-" + uploadIdForLog);
        try {
            pathResolver.verifyIngestable(staging);
            Files.copy(temp, staging, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(temp);
        } catch (IOException | RuntimeException e) {
            discardMovedFile(staging, uploadIdForLog, "cross-mount-copy-failed");
            throw e;
        }
    }

    // ======================== 내부 헬퍼 ========================

    /**
     * HIGH-2/HIGH-4: 청크 원자적 기록을 파일 I/O 코어({@link TusChunkStore})에 위임한다.
     * 트랜잭션/락/도메인 검증은 본 서비스가 담당하고, 순수 파일 I/O(스트리밍 write·상한·truncate
     * 롤백)만 코어가 처리한다(관제/포털 공유).
     *
     * @return 기록 완료 후의 누적 오프셋
     */
    private long writeChunkAtomically(LsTusUpload session, long offset, InputStream chunk, long contentLength) {
        return TusChunkStore.writeChunkAtomically(
                Paths.get(session.getFilePath()), offset, chunk, contentLength, maxChunkBytes);
    }

    private void truncateTo(String path, long size) {
        TusChunkStore.truncateTo(path, size);
    }

    /**
     * 보안 LOW(CWE-22 심층방어): 삭제 직전 대상 경로가 storageRawPath 하위인지 재검증한다(코어 위임).
     * 저장 경로는 생성 시 UUID 강제(HIGH-7)로 storage 내부가 보장되나, 데이터 변조 등으로
     * filePath 가 baseDir 밖을 가리키면 임의 파일 삭제로 이어질 수 있으므로 root 게이트를 둔다.
     */
    private void deleteQuietly(String path) {
        TusChunkStore.deleteQuietly(path, storageRawPath);
    }

    /** 보안 LOW: 코드성 메타 사전 검증 패턴 (AutolabelTestRequest 의 @Pattern 과 동일 SoT). */
    private static final java.util.regex.Pattern LOCAL_GOV_CD_PATTERN =
            java.util.regex.Pattern.compile("^[0-9]{1,10}$");
    /**
     * cctvId allowlist — 인입 {@code VMS_CCTV_ID VARCHAR(64)} 와 정합 (F6, CWE-20).
     *
     * <p>구 구현은 공백 여부만 봐서 65자 이상이 세션 생성(400)이 아니라 <b>완료 시점 INSERT</b> 에서
     * 값 초과로 터졌다 — 응답은 500 이고 0바이트 임시 파일이 남았다. {@code vmsClipId} 와 같은 문자
     * 집합으로 좁힌다(관제 인입도 같은 컬럼에 담는 값이다).
     */
    private static final java.util.regex.Pattern CCTV_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /**
     * 메타 사전 검증 — 컨트롤러의 {@code @Valid}(Bean Validation) <b>이후</b>의 2차 방어선이다.
     *
     * <p>중복(409)처럼 DB 를 봐야 아는 규칙은 여기서만 판정할 수 있고, 나머지 형식 규칙은 서비스를
     * 직접 호출하는 경로(배치·테스트)에서도 지켜져야 하므로 심층방어로 남긴다(CWE-20/22).
     */
    private void validateMeta(InternalUploadCreateRequest req) {
        // Phase 1: vmsClipId 는 인입 UK 이자 <저장 파일명>이다. NOT NULL 이며 경로 문자를 허용하지
        //   않는다(CWE-22). 완료 시점에 늦게 터지지 않도록 세션 생성 단에서 fail-fast.
        InternalUploadPathResolver.validateClipId(req.vmsClipId());
        videoRepository.findByVmsClipId(req.vmsClipId()).ifPresent(existing -> {
            throw new CustomException(ErrorCode.CONFLICT, "동일한 vmsClipId 가 이미 존재합니다.");
        });
        // 인입 UK(VMS_CLIP_ID) 중복 판정은 <되살리기 가능 여부>와 한 몸이라
        //   resolveReusableIngestSn 이 담당한다(여기서 무조건 409 를 내면 취소분 회수가 막힌다).
        // 인입 VMS_CCTV_ID 는 NOT NULL VARCHAR(64) — 값 필수 + 문자·길이 allowlist (F6).
        if (!StringUtils.hasText(req.cctvId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "cctvId 는 필수입니다.");
        }
        if (!CCTV_ID_PATTERN.matcher(req.cctvId()).matches()) {
            // CWE-209 — 입력 원문을 사용자 메시지에 담지 않는다(허용 규칙만 알린다).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "cctvId 는 영문·숫자·'_'·'-' 조합 1~64자만 허용됩니다.");
        }
        // ★CCTV <존재> 검증은 없다 — 검증할 마스터가 없다.
        //   구 구현은 관제 공유 MNG_RESOURCE_CCTV 존재 여부를 보고 경고만 남겼는데(그 이전엔 400),
        //   그 테이블은 V167 로 제거됐다(관제 2차에서 저작도구가 남의 스키마를 읽지 않는다).
        //   위 형식 allowlist(CCTV_ID_PATTERN)가 입력 검증(CWE-20)을 담당하며, "관제 인입 경로
        //   (TrainingVideoIngestTx)도 존재 검증을 하지 않는다"는 동일 재현 원칙과도 일치한다.
        // 보안 LOW: 코드성 필드 사전 검증 — 완료 시점에 늦게 터지는 DataIntegrity 실패 대신
        //   세션 생성 단에서 fail-fast(400). 입력 검증(CWE-20).
        if (req.lclgvCd() == null || !LOCAL_GOV_CD_PATTERN.matcher(req.lclgvCd()).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "lclgvCd 는 숫자 1~10자리만 허용됩니다.");
        }
        // Phase 3: 출처유형은 "누가 만들었나"의 경계축이라 미지의 값이 들어가면 적재 시 조용히 null 이
        //   되어 파생 판별·화면 표시가 미정의가 된다(fail-closed).
        //   ★판정 목록은 <입력면>(UPLOAD_SRC_TYPES) 이다 — 적재면(ALLOWED_SRC_TYPES)과 달리
        //     AUGMENTED 를 뺀다. 증강 파생본은 저작도구가 직접 만들고 ORGNL_RAW_SN 으로 부모를
        //     가리키므로, 인입으로 받으면 부모 없는 "파생 출처" 행이 생겨 판별 축이 어긋난다(M3).
        if (!LsDataIngest.UPLOAD_SRC_TYPES.contains(req.srcTypeOrDefault())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 출처유형입니다. 허용: " + LsDataIngest.UPLOAD_SRC_TYPES);
        }
    }

    /**
     * ffprobe 측정 — 실패는 <b>기존과 동일하게</b> {@code INVALID_INPUT} 으로 접는다(호출부가 409).
     *
     * <p>측정 원문 값·경로는 로그에 남기지 않는다({@code causeType} 만 — CWE-209).
     */
    MediaMeta probeMedia(Path savedPath, UUID uploadIdForLog) {
        try {
            return mediaProbe.probe(savedPath);
        } catch (RuntimeException e) {
            log.warn("[Tus] ffprobe failed uploadId={} causeType={}",
                    uploadIdForLog, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이를 추출할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다.");
        }
    }

    /**
     * 재생 가능성 게이트 — 길이(초)가 {@code 0 < d <= }{@value #MAX_DURATION_SEC} 인지만 본다.
     *
     * <p>길이 <b>미상</b>은 "측정에 실패했다"와 같은 취급이다(구 {@code DurationProbe} 는 값을 못 뽑으면
     * 예외를 던졌고 그 경로가 409 였다 — 그 동작을 그대로 보존한다). 반대로 <b>부가 필드</b>의 미상은
     * 여기서 판정하지 않는다.
     */
    private static int requireValidDurationSec(MediaMeta measured) {
        Long durationMs = measured == null ? null : measured.durationMs();
        long durationSec = durationMs == null ? 0L : Math.round(durationMs / 1000.0);
        if (durationSec <= 0L || durationSec > MAX_DURATION_SEC) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 길이가 유효 범위를 벗어났습니다.");
        }
        return (int) durationSec;
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

    private Path resolveSafeStoragePath(String safeFileName) {
        Path candidate = storageRawPath.resolve(UPLOAD_SUBDIR).resolve(safeFileName).normalize();
        if (!candidate.startsWith(storageRawPath)) {
            log.warn("[Tus] path traversal attempt blocked");
            throw new CustomException(ErrorCode.FORBIDDEN, "저장 경로가 허용 범위를 벗어납니다.");
        }
        return candidate;
    }

    /**
     * PATCH 결과 — 새 오프셋 + 완료 여부 + (이번 호출이 완료를 확정했을 때) 인입 행 PK.
     *
     * <p>Phase 1 이후 완료 산출물은 {@code LS_DATA_RAW} 가 아니라 <b>인입 행</b>이다. 재전송·선점 등
     * 이번 호출이 완료를 확정하지 않은 경우 {@code rcptnSn} 은 null 이다(세션에 보관하지 않는다).
     * TUS 클라이언트는 {@code Upload-Offset} 헤더만 보므로 응답 계약에는 영향이 없다.
     */
    public record TusPatchResult(long newOffset, boolean completed, Long rcptnSn) {
    }

    /**
     * 영상 파일 → duration(초) 추출 추상화.
     *
     * <p><b>이 서비스는 더 이상 쓰지 않는다</b> (Phase 2 — {@link UploadMediaProbe} 로 교체). 구현체
     * {@link DurationProbeFfprobe} 가 이 타입으로 선언돼 있어 남겨 둔다(선언 제거는 후속 정리 사안).
     */
    @FunctionalInterface
    public interface DurationProbe {
        int probe(Path filePath);
    }
}
