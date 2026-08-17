package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.batch.runner.DevPipelineRunner;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [개발/검수 전용] 영상 업로드 + 오토라벨 파이프라인 트리거 서비스.
 *
 * <p>{@code authoring.dev.upload.enabled=true} 일 때만 빈이 등록된다 (기본 false, fail-closed).
 *
 * <p>보안:
 * <ul>
 *   <li>CWE-434 Unrestricted File Upload — 확장자 allowlist + 크기 제한 + 메타 검증.</li>
 *   <li>CWE-22 Path Traversal — UUID 재명명 + storage 기준 경로 prefix 검증.</li>
 *   <li>CWE-117 Log Injection — 사용자 파일명 원본 로그 출력 금지 (UUID 만 출력).</li>
 *   <li>CWE-209 Information Leak — 응답에 절대경로 미노출 (상대경로만 반환).</li>
 *   <li>CWE-863 Improper Authorization — 컨트롤러에서 {@code @PreAuthorize} 로 차단.</li>
 * </ul>
 *
 * <p>업로드된 영상은 LS_DATA_RAW row 를 생성한 뒤, 트랜잭션 커밋 이후
 * {@link DevPipelineRunner#runAsync(Long)} 에 위임한다 (dev 업로드 단순화 — 운영 시나리오 1:1).
 * dev 경로는 단계 토글/마킹 분기 없이 선두 비식별만 수행하고 MARKING_READY 에서 정지한다.
 * 잔여 배치는 사용자가 마킹 화면에서 마킹→완료할 때만 트리거된다. afterCommit 으로 호출해야
 * 새 스레드에서 LS_DATA_RAW row 가 보인다(read-after-write 가시성).
 *
 * <p>또한 업로드된 파일에서 읽을 수 있는 <b>영상 기술메타</b>({@code LS_DATA_META} 의 {@code video.*})를
 * 같은 afterCommit 에서 적재한다({@link #storeTechnicalMeta} — 토글 {@code
 * authoring.dev.upload.extract-technical-meta}, 기본 켜짐). 이 경로는 {@code VideoIngestedEvent} 를
 * 발행하지 않아 그 이벤트에 물린 기존 메타 추출 배선이 트리거되지 않기 때문이다.
 *
 * <p>⚠ 사용자 <b>입력</b> 기술메타는 이 경로에서 전송되지 않는다 — 요청 계약({@code AutolabelTestRequest})
 * 은 6필드로 좁고, 위치·CCTV 제원 등을 {@code LS_DATA_RAW} 로 복사하지 않는 것이 확정 설계다. 여기서
 * 채우는 것은 <b>파일에서 측정한 값</b>뿐이며, 뽑을 수 없는 항목은 지어내지 않고 비운다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "authoring.dev.upload", name = "enabled", havingValue = "true")
public class DevAutolabelTestService {

    /** CWE-434 영상 확장자 allowlist (소문자 비교). */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("mp4", "webm", "mov", "avi");

    /**
     * 업로드된 파일을 저장할 storage 하위 디렉토리.
     *
     * <p>★이 상수를 읽는 곳은 <b>쓰기 경로 계산뿐</b>이다({@link #resolveSafeStoragePath}
     * → 호출처는 {@link #upload} 하나). {@code LS_DATA_RAW.FILE_PATH} 에는 <b>절대 경로</b>가
     * 적재되므로 하류 소비자(프레임 추출·export·스트리밍)는 DB 적재값을 읽고 이 상수를
     * 재조합하지 않는다. 따라서 값을 바꿔도 <b>새로 올리는 파일만</b> 새 디렉터리로 가고
     * 기존 영상은 적재된 절대 경로로 그대로 열린다 — 구 디렉터리와 공존할 뿐 유실이 아니다.
     *
     * <p>⚠ 그러므로 기존 파일을 옮기는 마이그레이션을 만들지 말 것. DB 경로가 구 디렉터리를
     * 가리키므로 파일을 옮기면 오히려 그 영상이 열리지 않는다.
     */
    private static final String UPLOAD_SUBDIR = "dev-upload";

    /** ffprobe 가 추출 가능한 최소 duration 상한 — `LS_DATA_RAW.DURATION_SEC` 유효 범위(2h). */
    private static final int MAX_DURATION_SEC = 7200;

    /** probe 의 {@code durationMs} → 초 환산 제수. 환산은 <b>반올림</b>이다(구 duration 프로브 동작 보존). */
    private static final int MILLIS_PER_SECOND = 1000;

    private final VideoRepository videoRepository;
    private final DevPipelineRunner devPipelineRunner;
    /** Phase 5: 이벤트 코드 검증을 관제 마스터 기반(상세 EV-코드 등록 여부)으로 전환 — TusUploadService 와 동일 SoT. */
    private final EventTypeService eventTypeService;
    /**
     * 영상 기술메타 추출 포트 — <b>업로드 1건당 정확히 1회</b> 호출한다.
     *
     * <p>{@code duration}(→ {@code LS_DATA_RAW.VDO_LEN_SEC}) 과 {@code video.*} 기술메타를 <b>같은
     * 결과에서</b> 뽑으므로 ffprobe 프로세스가 두 번 뜨지 않고, 두 적재값이 서로 갈리지도 않는다.
     * 구 구현은 이 서비스가 {@code net.bramp} FFprobe 를 직접 감싼 별도 duration 전용 프로브를
     * 갖고 있었는데, 기술메타까지 뽑으려면 그 래퍼가 두 번째 프로브가 된다.
     */
    private final VideoProbe videoProbe;
    /** {@code video.*} 기술메타 적재의 <b>유일한 통로</b> — 키 집합·null skip 판정은 이 서비스가 소유한다. */
    private final VideoMetaService videoMetaService;
    private final Path storageRawPath;
    private final long maxFileSize;
    /** {@code authoring.dev.upload.extract-technical-meta} — 기본 켜짐. {@link #storeTechnicalMeta} 참조. */
    private final boolean technicalMetaEnabled;

    public DevAutolabelTestService(
            VideoRepository videoRepository,
            DevPipelineRunner devPipelineRunner,
            EventTypeService eventTypeService,
            VideoProbe videoProbe,
            VideoMetaService videoMetaService,
            @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
            @Value("${authoring.dev.upload.max-file-size:524288000}") long maxFileSize,
            @Value("${authoring.dev.upload.extract-technical-meta:true}") boolean technicalMetaEnabled
    ) {
        this.videoRepository = videoRepository;
        this.devPipelineRunner = devPipelineRunner;
        this.eventTypeService = eventTypeService;
        this.videoProbe = videoProbe;
        this.videoMetaService = videoMetaService;
        this.storageRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.technicalMetaEnabled = technicalMetaEnabled;
        if (!technicalMetaEnabled) {
            // 요청마다 도배하지 않고 기동 시 1회만 알린다 — 끄면 조용히 아무것도 안 하는 것이 아니라,
            // 운영자가 "왜 이 영상엔 기술메타가 없지" 를 이 한 줄로 추적할 수 있어야 한다.
            log.info("[DevAutolabelTest] technical meta extraction disabled "
                    + "(authoring.dev.upload.extract-technical-meta=false) — video.* 메타를 적재하지 않는다");
        }
    }

    /**
     * 영상 업로드 + LS_DATA_RAW 등록 후 오토라벨 파이프라인을 백그라운드로 시작한다.
     *
     * @param file 업로드된 영상 파일 (multipart)
     * @param meta 검증된 메타데이터
     * @return rawSn + 저장된 상대 경로 + 파이프라인 상태
     */
    @Transactional("controlTransactionManager")
    public AutolabelTestResponse upload(MultipartFile file, AutolabelTestRequest meta) {
        validateFile(file);
        validateMeta(meta);

        String extension = resolveExtension(file.getOriginalFilename());
        String safeFileName = UUID.randomUUID().toString() + "." + extension;
        Path savedAbsolutePath = resolveSafeStoragePath(safeFileName);
        String relativePath = UPLOAD_SUBDIR + "/" + safeFileName;

        try {
            Files.createDirectories(savedAbsolutePath.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, savedAbsolutePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[DevAutolabelTest] file save failed vmsClipIdHash={}",
                    Integer.toHexString(meta.vmsClipId().hashCode()), e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 파일 저장에 실패했습니다.");
        }

        // ★기술메타 추출 — 업로드 1건당 ffprobe 1회. 이 한 결과에서 duration 과 video.* 를 함께 쓴다.
        VideoMeta probed = probeOnce(savedAbsolutePath, meta.vmsClipId());
        // duration 자동 추출 — 실패/미상/범위 위반은 400 으로 거절(기존 동작 유지).
        int durationSec = resolveDurationSec(probed, meta.vmsClipId());

        LocalDateTime capturedAt = LocalDateTime.ofInstant(meta.capturedAt(), ZoneId.systemDefault());
        // FfmpegFrameExtractor 는 Paths.get(filePath) 로 절대 해석하므로 LS_DATA_RAW.FILE_PATH 에는
        // 절대 경로를 저장해야 한다 (저장 자체는 storage prefix 내부로 이미 격리됨 — CWE-22 회피).
        LsDataRaw raw = LsDataRaw.createFromIngest(
                meta.vmsClipId(),
                meta.cctvId(),
                meta.eventTypeCd(),
                meta.localGovCd(),
                meta.prvcTypeCd().name(),
                savedAbsolutePath.toString(),
                capturedAt,
                durationSec
        );
        LsDataRaw saved = videoRepository.save(raw);
        Long rawSn = saved.getRawSn();
        long startedAt = System.currentTimeMillis();

        // CWE-117 Log Injection 방어 — 사용자 입력 원본 파일명/경로 절대 출력 금지.
        log.info("[DevAutolabelTest] uploaded rawSn={} vmsClipIdHash={} prvcType={} ext={} sizeBytes={} durationSec={}",
                rawSn,
                Integer.toHexString(meta.vmsClipId().hashCode()),
                meta.prvcTypeCd().name(),
                extension,
                file.getSize(),
                durationSec);

        // dev 업로드 단순화: 단계 토글/마킹 분기 없이 단일 runAsync(rawSn) 에 위임한다.
        // DevPipelineRunner 가 선두 비식별만 수행하고 MARKING_READY 에서 정지하며, 잔여 배치는
        // 사용자가 마킹 화면에서 마킹→완료할 때만 트리거된다. 트랜잭션 커밋 이후에 호출해야 새
        // 스레드에서 LsDataRaw row 가 보인다(read-after-write 가시성 — afterCommit 미사용 시
        // "영상 레코드가 없습니다" 발생). 예외 삼킴/실패 처리는 DevPipelineRunner(@Async) 내부에서 WARN.
        //
        // ★기술메타 적재도 같은 afterCommit 에 태운다 — 아래 storeTechnicalMeta Javadoc 참조.
        Runnable afterCommitWork = () -> {
            storeTechnicalMeta(rawSn, probed);
            devPipelineRunner.runAsync(rawSn);
        };

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            afterCommitWork.run();
                        }
                    });
        } else {
            afterCommitWork.run();
        }

        return new AutolabelTestResponse(rawSn, relativePath, "PROCESSING", startedAt);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "업로드 파일이 비어 있습니다.");
        }
        if (file.getSize() > maxFileSize) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "파일 크기가 허용 한도를 초과했습니다. 최대 " + maxFileSize + " bytes");
        }
        String extension = resolveExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 확장자입니다. 허용: " + ALLOWED_EXTENSIONS);
        }
        // MIME 은 브라우저/OS 에 따라 다양하여 너무 엄격하면 거부 많음 — 확장자 검증을 우선,
        // 단순한 sanity check 만 적용 (octet-stream 도 허용).
        String contentType = file.getContentType();
        if (contentType != null
                && !contentType.equalsIgnoreCase("application/octet-stream")
                && !contentType.toLowerCase(Locale.ROOT).startsWith("video/")) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 MIME 타입입니다.");
        }
    }

    private void validateMeta(AutolabelTestRequest meta) {
        // vmsClipId 중복 — UK 충돌 사전 차단.
        videoRepository.findByVmsClipId(meta.vmsClipId()).ifPresent(existing -> {
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일한 vmsClipId 가 이미 존재합니다.");
        });
        // ★cctvId 등록 여부는 검증하지 않는다 — 검증할 마스터가 없다.
        //   구 구현은 관제 공유 MNG_RESOURCE_CCTV 존재 여부로 400 을 냈으나 그 테이블은 V167 로
        //   제거됐다. 대체 원천(LS_DATA_INGEST.VMS_CCTV_ID)은 "이미 수신된 영상"의 기록이지 CCTV
        //   마스터가 아니라, 신규 dev 영상의 CCTV 를 판정할 근거가 되지 못한다(첫 영상은 항상 거부됨).
        //   형식 검증은 요청 DTO 의 @Pattern 이 담당한다(CWE-20).
        // 이벤트 코드 — @Pattern 으로 형식(EV+숫자8)만 1차 가드된 상태. 여기서 이벤트유형 마스터
        // (LS_EVNT_TYPE, V168) 등록 여부를 2차 검증한다. filterKeyOf 가 빈 Optional 이면 미등록
        // 코드 → 400. (CWE-20 입력 검증 — dev 도구도 미등록 코드 거부)
        String eventTypeCd = meta.eventTypeCd();
        if (eventTypeCd != null && !eventTypeCd.isBlank()
                && eventTypeService.filterKeyOf(eventTypeCd).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 이벤트 타입입니다.");
        }
    }

    /**
     * 사용자 입력 파일명에서 확장자만 추출 — 경로 구분자 제거 + 소문자화.
     * 확장자가 없거나 위험 문자가 포함되면 빈 문자열 반환 (allowlist 검증에서 실패).
     */
    private static String resolveExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        // CWE-22 — 경로 구분자 무시, 마지막 segment 만 사용.
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
        // 영문/숫자만 (특수문자 차단)
        if (!ext.matches("^[a-z0-9]{1,8}$")) {
            return "";
        }
        return ext;
    }

    /**
     * 업로드된 영상 파일을 <b>한 번만</b> 조사한다 — 결과는 duration 과 {@code video.*} 기술메타가 공유한다.
     *
     * <p>ffprobe 실패(손상된 영상/지원되지 않는 형식) 시 400 응답. 길이는 필수 적재값
     * ({@code LS_DATA_RAW.VDO_LEN_SEC})이므로 <b>여기서는 fail-open 하지 않는다</b> —
     * 부가 기능인 기술메타 적재만 fail-open 이다({@link #storeTechnicalMeta}).
     *
     * @param savedPath       저장된 영상의 절대 경로
     * @param vmsClipIdForLog 로그 마스킹용 — 원본은 출력하지 않고 hash 만 사용
     */
    private VideoMeta probeOnce(Path savedPath, String vmsClipIdForLog) {
        VideoMeta probed;
        try {
            probed = videoProbe.probe(savedPath);
        } catch (RuntimeException e) {
            log.warn("[DevAutolabelTest] ffprobe failed vmsClipIdHash={} causeType={}",
                    clipIdHash(vmsClipIdForLog), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이를 추출할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다.");
        }
        if (probed == null) {
            log.warn("[DevAutolabelTest] ffprobe returned no metadata vmsClipIdHash={}",
                    clipIdHash(vmsClipIdForLog));
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이를 추출할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다.");
        }
        return probed;
    }

    /**
     * probe 결과에서 duration(초) 을 도출한다.
     * <p>길이 미상({@code durationMs == null})은 <b>0 으로 단정하지 않고</b> 추출 실패로 다룬다.
     * <p>0 이하 또는 상한 초과 값은 거절 (LS_DATA_RAW.VDO_LEN_SEC 도메인 제약 — 1~7200s).
     */
    private int resolveDurationSec(VideoMeta probed, String vmsClipIdForLog) {
        Long durationMs = probed.durationMs();
        if (durationMs == null) {
            log.warn("[DevAutolabelTest] ffprobe returned unknown duration vmsClipIdHash={}",
                    clipIdHash(vmsClipIdForLog));
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이를 추출할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다.");
        }
        int durationSec = (int) Math.round(durationMs / (double) MILLIS_PER_SECOND);
        if (durationSec <= 0) {
            log.warn("[DevAutolabelTest] ffprobe returned non-positive duration vmsClipIdHash={} durationSec={}",
                    clipIdHash(vmsClipIdForLog), durationSec);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이가 유효하지 않습니다 (0초 이하).");
        }
        if (durationSec > MAX_DURATION_SEC) {
            log.warn("[DevAutolabelTest] ffprobe returned over-limit duration vmsClipIdHash={} durationSec={}",
                    clipIdHash(vmsClipIdForLog), durationSec);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이가 허용 한도(" + MAX_DURATION_SEC + "초) 를 초과합니다.");
        }
        return durationSec;
    }

    /**
     * 업로드된 파일에서 읽은 기술메타를 {@code LS_DATA_META} 의 {@code video.*} 로 적재한다.
     * [@design SCREEN-027]
     *
     * <p><b>왜 이 서비스가 하는가</b> — 즉시 실행 경로는 {@code VideoIngestedEvent} 를 발행하지 않아
     * {@code VideoMetaExtractBridge} → {@code AsyncVideoMetaRunner}({@code video.*} 를 쓰는 유일한 통로)가
     * 트리거되지 않았고, 그래서 사용자 입력값은 물론 <b>ffprobe 자동 추출조차 일어나지 않았다</b>.
     *
     * <p><b>새 적재 경로·새 키를 만들지 않는다</b> — 적재는 {@link VideoMetaService} 에 위임한다. 키 집합
     * (6종)과 "미상 필드는 저장하지 않는다"(값 0 과 미상을 구분) 판정을 그 서비스가 소유하므로, 여기서
     * {@code video.*} 문자열을 조립하면 두 번째 진실원이 된다. 뽑을 수 없는 항목은 지어내지 않고
     * 미상(null)으로 그대로 넘긴다.
     *
     * <p><b>왜 afterCommit 인가 (트랜잭션 경계)</b> — {@code LS_DATA_META.RAW_SN} 에 FK
     * ({@code fk_ls_data_meta_raw → ls_data_raw}) 가 있고 {@code VideoMetaService} 는
     * {@code REQUIRES_NEW}(별 커넥션) 로 쓴다. {@code upload()} 트랜잭션 안에서 부르면 아직 커밋되지
     * 않은 {@code LS_DATA_RAW} 행이 그 트랜잭션에서 보이지 않아 <b>FK 위반으로 실패</b>한다. 파이프라인
     * 트리거를 afterCommit 에 태우는 것과 같은 이유(read-after-write 가시성)이므로 같은 콜백에 태운다.
     *
     * <p><b>fail-open</b> — 기술메타는 부가 기능이므로 실패해도 업로드를 되돌리지 않는다. 다만 조용히
     * 삼키지 않고 WARN 으로 남긴다. afterCommit 콜백에서 던진 예외는 {@code commit()} 밖으로 전파되므로
     * 여기서 반드시 잡아야 한다(안 잡으면 커밋된 업로드가 5xx 로 보인다).
     *
     * <p>인입 병합 오버로드가 아니라 probe 단독 오버로드를 쓴다 — 이 경로에는
     * {@code LS_DATA_INGEST} 행이 없다(관제 인입 재현 경로만 그 원장을 쓴다).
     */
    private void storeTechnicalMeta(Long rawSn, VideoMeta probed) {
        if (!technicalMetaEnabled) {
            // 기동 시 INFO 로 이미 알렸다 — 요청마다 반복하지 않고 흐름 추적용 DEBUG 만 남긴다.
            log.debug("[DevAutolabelTest] technical meta extraction disabled — skip rawSn={}", rawSn);
            return;
        }
        try {
            videoMetaService.upsertVideoMeta(rawSn, probed);
        } catch (RuntimeException e) {
            log.warn("[DevAutolabelTest] technical meta store failed rawSn={} causeType={} — 업로드는 유지",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /** CWE-117 Log Injection 방어 — 사용자 입력 vmsClipId 원본 대신 hash 만 로그에 남긴다. */
    private static String clipIdHash(String vmsClipId) {
        return Integer.toHexString(vmsClipId == null ? 0 : vmsClipId.hashCode());
    }

    /**
     * CWE-22 Path Traversal 방어 — storage 기준 경로 prefix 검증.
     * UUID 재명명된 파일명만 입력으로 받으므로 일반적으로 안전하지만 이중 방어.
     */
    private Path resolveSafeStoragePath(String safeFileName) {
        Path candidate = storageRawPath.resolve(UPLOAD_SUBDIR).resolve(safeFileName).normalize();
        if (!candidate.startsWith(storageRawPath)) {
            log.warn("[DevAutolabelTest] path traversal attempt blocked");
            throw new CustomException(ErrorCode.FORBIDDEN,
                    "저장 경로가 허용 범위를 벗어납니다.");
        }
        return candidate;
    }
}
