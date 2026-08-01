package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.entity.MngClipMasterId;
import kr.co.cudo.authoring.video.repository.MngClipMasterRepository;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import lombok.extern.slf4j.Slf4j;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.probe.FFmpegProbeResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * [개발/검수 전용] 영상 업로드 → <b>관제 테이블 INSERT → 실제 운영 픽업 배치</b> 서비스 (Phase 3).
 *
 * <p>{@code authoring.dev.upload.enabled=true} 일 때만 빈이 등록된다 (기본 false, fail-closed).
 *
 * <h3>Phase 3 전환 — dev 전용 우회 적재 제거</h3>
 * <p>이전에는 이 서비스가 {@code LsDataRaw.createFromIngest} 로 {@code LS_DATA_RAW} 를 <b>직접</b>
 * 만들고 {@code DevPipelineRunner} 로 비식별을 트리거했다. 그래서 운영 픽업 경로를 한 줄도 검증하지
 * 못했다. 이제는 다음 순서로 <b>운영과 동일한 경로</b>를 탄다:
 * <ol>
 *   <li>업로드 파일을 관제 NAS 규약 경로({@code {clipNasBasePath}/{clipId}.{ext}})에 저장</li>
 *   <li>{@link DevControlClipWriter} 로 관제 두 테이블에 행 INSERT (독립 트랜잭션 — 반환 시 커밋)</li>
 *   <li><b>커밋 이후</b> {@link TrainingVideoIngestService#scanAndIngest()} 호출 —
 *       주기 배치({@code ControlTrainingVideoScanJob})가 부르는 <b>바로 그 메서드</b>다.
 *       적재는 {@code TrainingVideoIngestTx.ingestOne} 이 수행하며 {@code VideoIngestedEvent} 를
 *       발행해 {@code IngestDeidentifyBridge → AsyncDeidentifyRunner} 가 선두 비식별을 수행한다.</li>
 *   <li>적재된 {@code rawSn} 을 {@code VMS_CLIP_ID} 로 회수해 응답에 담는다.</li>
 * </ol>
 * <p>따라서 dev 전용 비식별 러너({@code DevPipelineRunner})는 <b>불필요</b>해져 제거되었다 —
 * 운영 경로가 같은 비식별 + {@code MARKING_READY} 전이를 수행한다.
 *
 * <h3>트랜잭션 경계 (Critical)</h3>
 * <p>이 클래스에는 {@code @Transactional} 이 <b>없다</b>. 관제 INSERT 를 감싸는 트랜잭션 안에서
 * 스캔을 호출하면, 스캔의 후보 조회(별도 READ 트랜잭션) + 적재({@code REQUIRES_NEW}, 별도 커넥션)가
 * 미커밋 행을 보지 못해 <b>0건 픽업</b>된다. 커밋 경계는 {@link DevControlClipWriter#insertClip} 이
 * 소유하고, 스캔은 그 반환(=커밋) 이후에 호출한다. 여기에 {@code @Transactional} 을 붙이지 말 것.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>CWE-434 Unrestricted File Upload — 확장자 allowlist + 크기 제한 + MIME sanity check.</li>
 *   <li>CWE-22 Path Traversal — {@code clipId} allowlist 패턴(1차) + 관제 NAS 기준경로 prefix 검증(2차).</li>
 *   <li>CWE-117 Log Injection — 사용자 입력 원문 로그 출력 금지 (식별자 해시만).</li>
 *   <li>CWE-209 Information Leak — 응답에 절대경로 미노출 (상대경로만 반환).</li>
 *   <li>CWE-285 — 컨트롤러 {@code @PreAuthorize("hasRole('REVIEWER')")} + dev 토글 + 쓰기 빈 {@code !prd}.</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "authoring.dev.upload", name = "enabled", havingValue = "true")
public class DevAutolabelTestService {

    /** CWE-434 영상 확장자 allowlist (소문자 비교). */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("mp4", "webm", "mov", "avi");

    /** ffprobe 가 추출 가능한 최소 duration 상한 — `LS_DATA_RAW.DURATION_SEC` 유효 범위(2h). */
    private static final int MAX_DURATION_SEC = 7200;

    /** 관제 VDO_LEN_SEC 실측 단위(ms) 환산 — 적재 시 TrainingVideoIngestTx 가 ÷1000 으로 되돌린다. */
    private static final int MILLIS_PER_SECOND = 1000;

    /** 서버 생성 CLIP_ID 접두 — 총 길이 30자 이내(관제 CLIP_ID VARCHAR(50) 여유). */
    private static final String CLIP_ID_PREFIX = "CLP-";
    /** 서버 생성 EVNT_ID 접두 — dev 업로드분 식별용. */
    private static final String EVNT_ID_PREFIX = "DEV-";
    /** 서버 생성 식별자의 UUID 본문 길이(하이픈 제거 hex 32자 중 앞 26자 → 접두 포함 30자). */
    private static final int GENERATED_SUFFIX_LENGTH = 26;

    /** CWE-22 — clipId 는 파일명이 되므로 allowlist 로만 통과시킨다 (DTO @Pattern 과 동일 규약, 2차 방어). */
    private static final Pattern CLIP_ID_PATTERN = Pattern.compile("^CLP-[A-Za-z0-9]{1,26}$");
    /** 관제 EVNT_ID allowlist (DTO @Pattern 과 동일 규약, 2차 방어). */
    private static final Pattern EVNT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,50}$");

    /** 관제 CLIP_TYPE_CD 기본값 — 원본 클립. */
    static final String DEFAULT_CLIP_TYPE_CD = "ORIGINAL";
    /** 관제 CLIP_STTS_CD 기본값 — 관제 실데이터에서 관측된 완료 상태값. */
    static final String DEFAULT_CLIP_STTS_CD = "mediainfo_complete";
    /** 학습용 지정 — 픽업 배치가 집으려면 'Y' 여야 한다. */
    static final String DEFAULT_JOB_DMND_YN = "Y";
    static final String DEFAULT_JOB_DMND_PRNMNT_YN = "N";
    /** 관제 CRT_TYPE 기본값 — 0=중계서버 생성. */
    static final int DEFAULT_CRT_TYPE = 0;

    /** 적재까지 성공한 경우의 응답 상태. */
    static final String STATUS_PROCESSING = "PROCESSING";
    /** 관제 INSERT 는 됐으나 이번 스캔에서 적재되지 않은 경우 — 거짓 성공 금지. */
    static final String STATUS_INGEST_PENDING = "INGEST_PENDING";

    private final VideoRepository videoRepository;
    private final MngResourceCctvRepository cctvRepository;
    private final MngClipMasterRepository clipMasterRepository;
    private final EventTypeService eventTypeService;
    private final TrainingVideoIngestService trainingVideoIngestService;
    /**
     * 관제 쓰기 빈 공급자. {@code @Profile("!prd")} 라 운영에서는 부재하며, 그때 업로드는 403 으로 거절된다
     * (업로드 endpoint 자체는 폐쇄망 bring-up 을 위해 prd 에서도 등록될 수 있으므로 필수 의존으로 두지 않는다).
     */
    private final Supplier<DevControlClipWriter> clipWriterSupplier;

    private final Path storageRawPath;
    /** 관제 NAS 업로드 규약 루트 — CONST-050 {@code /nas-storage/data/upload/v2/{clip_id}} 대응. */
    private final Path clipNasBasePath;
    private final long maxFileSize;
    private final String ffprobePath;
    /**
     * 영상 파일 → duration(초) 추출 함수. 운영은 ffprobe 바이너리 호출.
     * 테스트는 stub 함수 주입으로 ffprobe 의존성을 격리.
     */
    private final DurationProbe durationProbe;

    /** 운영용 생성자 — Spring 이 의존성 주입. */
    @org.springframework.beans.factory.annotation.Autowired
    public DevAutolabelTestService(
            VideoRepository videoRepository,
            MngResourceCctvRepository cctvRepository,
            MngClipMasterRepository clipMasterRepository,
            EventTypeService eventTypeService,
            TrainingVideoIngestService trainingVideoIngestService,
            ObjectProvider<DevControlClipWriter> clipWriterProvider,
            @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
            @Value("${authoring.dev.upload.clip-nas-base-path:${authoring.storage.raw-path:./storage/raw}/data/upload/v2}")
            String clipNasBasePath,
            @Value("${authoring.dev.autolabel-test.max-file-size:524288000}") long maxFileSize,
            @Value("${authoring.ffmpeg.ffprobe-binary:ffprobe}") String ffprobePath
    ) {
        this(videoRepository, cctvRepository, clipMasterRepository, eventTypeService,
                trainingVideoIngestService, clipWriterProvider::getIfAvailable,
                storageRawPath, clipNasBasePath, maxFileSize, ffprobePath, null);
    }

    /**
     * 테스트용 생성자 — 쓰기 빈과 {@link DurationProbe} 를 직접 주입해 Spring/ffprobe 의존성을 격리한다.
     * {@code durationProbe == null} 이면 ffprobe 기반 기본 구현을 사용한다.
     */
    public DevAutolabelTestService(
            VideoRepository videoRepository,
            MngResourceCctvRepository cctvRepository,
            MngClipMasterRepository clipMasterRepository,
            EventTypeService eventTypeService,
            TrainingVideoIngestService trainingVideoIngestService,
            Supplier<DevControlClipWriter> clipWriterSupplier,
            String storageRawPath,
            String clipNasBasePath,
            long maxFileSize,
            String ffprobePath,
            DurationProbe durationProbe
    ) {
        this.videoRepository = videoRepository;
        this.cctvRepository = cctvRepository;
        this.clipMasterRepository = clipMasterRepository;
        this.eventTypeService = eventTypeService;
        this.trainingVideoIngestService = trainingVideoIngestService;
        this.clipWriterSupplier = clipWriterSupplier;
        this.storageRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.clipNasBasePath = Paths.get(clipNasBasePath).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.ffprobePath = ffprobePath;
        this.durationProbe = durationProbe != null ? durationProbe : this::probeWithFfprobe;
    }

    /**
     * 영상 업로드 → 관제 두 테이블 INSERT → (커밋 후) 운영 픽업 스캔 → rawSn 회수.
     *
     * @param file 업로드된 영상 파일 (multipart)
     * @param meta 검증된 메타데이터
     * @return rawSn(적재 성공 시) + 저장 상대 경로 + 파이프라인 상태
     */
    public AutolabelTestResponse upload(MultipartFile file, AutolabelTestRequest meta) {
        DevControlClipWriter clipWriter = requireClipWriter();
        long startedAt = System.currentTimeMillis();

        validateFile(file);
        String clipId = resolveClipId(meta.vmsClipId());
        String evntId = resolveEvntId(meta.evntId());
        String clipTypeCd = defaultIfBlank(meta.clipTypeCd(), DEFAULT_CLIP_TYPE_CD);
        validateMeta(meta, clipId, evntId, clipTypeCd);

        String extension = resolveExtension(file.getOriginalFilename());
        Path savedAbsolutePath = resolveSafeStoragePath(clipId + "." + extension);
        storeFile(file, savedAbsolutePath, clipId);

        int durationSec;
        try {
            // duration 자동 추출 — ffprobe 로 영상 메타에서 추출. 실패 시 400 으로 거절.
            durationSec = resolveDurationSec(meta.vdoLenSec(), savedAbsolutePath, clipId);
            // 관제 두 테이블 INSERT — 반환 시 커밋된다(별도 트랜잭션 경계).
            clipWriter.insertClip(buildRow(meta, clipId, evntId, clipTypeCd,
                    savedAbsolutePath, extension, file, durationSec));
        } catch (RuntimeException e) {
            deleteQuietly(savedAbsolutePath);
            throw e;
        }

        // ★ 커밋 이후에만 스캔한다 — 미커밋 상태로 스캔하면 후보 조회/REQUIRES_NEW 적재가 행을 못 본다.
        int ingested = trainingVideoIngestService.scanAndIngest();
        Long rawSn = videoRepository.findByVmsClipId(clipId)
                .map(raw -> raw.getRawSn())
                .orElse(null);

        // CWE-117 Log Injection 방어 — 사용자 입력 원본 파일명/식별자 절대 출력 금지(해시만).
        log.info("[DevAutolabelTest] uploaded clipIdHash={} ext={} sizeBytes={} durationSec={} ingested={} rawSn={}",
                hash(clipId), extension, file.getSize(), durationSec, ingested, rawSn);

        if (rawSn == null) {
            // 거짓 성공 금지 — 적재가 안 됐으면 rawSn 없이 정직하게 알린다(주기 배치가 다음 tick 에 픽업).
            log.warn("[DevAutolabelTest] control clip inserted but not ingested yet clipIdHash={}", hash(clipId));
            return new AutolabelTestResponse(null, relativeSavedPath(savedAbsolutePath),
                    STATUS_INGEST_PENDING, startedAt);
        }
        return new AutolabelTestResponse(rawSn, relativeSavedPath(savedAbsolutePath),
                STATUS_PROCESSING, startedAt);
    }

    /**
     * 관제 쓰기 빈을 확보한다. {@code @Profile("!prd")} 라 운영에서는 부재하며 그때는 403 으로 거절한다 —
     * 운영 관제 테이블(MNG_*, 관제팀 소유)에 저작도구가 쓰는 경로를 열지 않는다.
     */
    private DevControlClipWriter requireClipWriter() {
        DevControlClipWriter writer = clipWriterSupplier.get();
        if (writer == null) {
            throw new CustomException(ErrorCode.FORBIDDEN,
                    "운영(prd) 환경에서는 관제 클립 테이블 쓰기가 허용되지 않아 dev 업로드를 사용할 수 없습니다.");
        }
        return writer;
    }

    /** 미전송 시 서버 생성 — {@code CLP-} + UUID(하이픈 제거) 26자 = 총 30자. */
    private static String resolveClipId(String requested) {
        if (!StringUtils.hasText(requested)) {
            return CLIP_ID_PREFIX + randomSuffix();
        }
        if (!CLIP_ID_PATTERN.matcher(requested).matches()) {
            // CWE-22 2차 방어 — DTO @Pattern 을 우회한 호출(서비스 직접 호출 등)도 차단한다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "vmsClipId 는 CLP- 접두 + 영문/숫자 1~26자(총 30자 이내)만 허용됩니다.");
        }
        return requested;
    }

    /** 미전송 시 서버 생성 — {@code DEV-} + UUID(하이픈 제거) 26자 = 총 30자 (관제 EVNT_ID 50자 이내). */
    private static String resolveEvntId(String requested) {
        if (!StringUtils.hasText(requested)) {
            return EVNT_ID_PREFIX + randomSuffix();
        }
        if (!EVNT_ID_PATTERN.matcher(requested).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "evntId 는 영문/숫자/-/_ 1~50자만 허용됩니다.");
        }
        return requested;
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, GENERATED_SUFFIX_LENGTH);
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

    /**
     * 중복·미등록 사전 검증. 충돌 축이 둘이므로 <b>메시지를 구분</b>한다 —
     * ①{@code LS_DATA_RAW.VMS_CLIP_ID}(UK) ②관제 {@code MNG_CLIP_MASTER} 복합 PK.
     */
    private void validateMeta(AutolabelTestRequest meta, String clipId, String evntId, String clipTypeCd) {
        // ① 적재 축 — 같은 clipId 로 이미 LS_DATA_RAW 가 만들어져 있으면 픽업이 skip 되어 무의미하다.
        if (videoRepository.findByVmsClipId(clipId).isPresent()) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일한 clipId 로 적재된 영상이 이미 존재합니다 (LS_DATA_RAW.VMS_CLIP_ID 중복). "
                            + "vmsClipId 를 바꾸거나 비워서 서버 생성값을 사용하세요.");
        }
        // ② 관제 축 — 복합 PK (EVNT_ID, CLIP_TYPE_CD). DB 제약이 최종 방어이고 여기는 1선이다.
        if (clipMasterRepository.existsById(new MngClipMasterId(evntId, clipTypeCd))) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일한 evntId + clipTypeCd 의 관제 클립이 이미 존재합니다 (MNG_CLIP_MASTER PK 충돌). "
                            + "evntId 를 바꾸거나 비워서 서버 생성값을 사용하세요.");
        }
        // cctvId 등록 여부.
        if (!cctvRepository.existsById(meta.cctvId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "등록되지 않은 CCTV 입니다.");
        }
        // 이벤트 코드 — @Pattern 으로 형식(EV+숫자8)만 1차 가드된 상태. 여기서 관제 마스터 등록 여부를
        // 2차 검증한다(TusUploadService 와 동일 SoT). categoryKeyOf 가 빈 Optional 이면 관제 미등록 → 400.
        String eventTypeCd = meta.eventTypeCd();
        if (eventTypeCd != null && !eventTypeCd.isBlank()
                && eventTypeService.categoryKeyOf(eventTypeCd).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 이벤트 타입입니다.");
        }
    }

    /** 관제 컬럼 값 조립 — 미전송 필드는 서버 기본값/실측값으로 채운다. */
    private DevControlClipWriter.ControlClipRow buildRow(
            AutolabelTestRequest meta, String clipId, String evntId, String clipTypeCd,
            Path savedAbsolutePath, String extension, MultipartFile file, int durationSec) {
        LocalDateTime now = LocalDateTime.now();
        return new DevControlClipWriter.ControlClipRow(
                evntId,
                clipTypeCd,
                clipId,
                meta.localGovCd(),
                defaultIfBlank(meta.fileNm(), normalizeFileName(file.getOriginalFilename(), clipId, extension)),
                savedAbsolutePath.toString(),
                defaultIfBlank(meta.fileFmt(), extension),
                // ★ 초 → ms. 관제 VDO_LEN_SEC 실측 단위가 ms 이고 적재가 ÷1000 으로 되돌린다.
                durationSec * MILLIS_PER_SECOND,
                defaultIfBlank(meta.clipSttsCd(), DEFAULT_CLIP_STTS_CD),
                toLocalDateTime(meta.crtDt(), now),
                toLocalDateTime(meta.uldCmptDt(), now),
                defaultIfBlank(meta.jobDmndYn(), DEFAULT_JOB_DMND_YN),
                meta.cctvId(),
                meta.fileSz() != null ? meta.fileSz() : file.getSize(),
                defaultIfBlank(meta.jobDmndPrnmntYn(), DEFAULT_JOB_DMND_PRNMNT_YN),
                meta.crtType() != null ? meta.crtType() : DEFAULT_CRT_TYPE,
                meta.eventTypeCd(),
                toLocalDateTime(meta.capturedAt(), now),
                trimToNull(meta.evntNm()),
                trimToNull(meta.sesnCd()),
                trimToNull(meta.hrTypeCd()),
                trimToNull(meta.prvcTypeCd()),
                trimToNull(meta.idntfYn()),
                trimToNull(meta.clctPath()),
                trimToNull(meta.clctSrc()));
    }

    private void storeFile(MultipartFile file, Path savedAbsolutePath, String clipId) {
        try {
            Files.createDirectories(savedAbsolutePath.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, savedAbsolutePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[DevAutolabelTest] file save failed clipIdHash={}", hash(clipId), e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 파일 저장에 실패했습니다.");
        }
    }

    /** 관제 INSERT 가 실패하면 방금 쓴 파일이 고아로 남으므로 best-effort 로 지운다. */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[DevAutolabelTest] orphan upload file cleanup failed causeType={}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 사용자 입력 파일명에서 확장자만 추출 — 경로 구분자 제거 + 소문자화.
     * 확장자가 없거나 위험 문자가 포함되면 빈 문자열 반환 (allowlist 검증에서 실패).
     */
    private static String resolveExtension(String originalFilename) {
        String basename = baseName(originalFilename);
        if (basename.isEmpty()) {
            return "";
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

    /** CWE-22 — 경로 구분자 무시, 마지막 segment 만 사용. */
    private static String baseName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        String basename = originalFilename;
        int slash = Math.max(basename.lastIndexOf('/'), basename.lastIndexOf('\\'));
        if (slash >= 0) {
            basename = basename.substring(slash + 1);
        }
        return basename;
    }

    /**
     * 관제 {@code FILE_NM} 기본값 — 업로드 원본 파일명을 정규화(basename + 제어문자 제거 + 길이 상한)한다.
     * 정규화 결과가 비면 저장 파일명({@code {clipId}.{ext}})으로 대체한다.
     */
    private static String normalizeFileName(String originalFilename, String clipId, String extension) {
        String basename = baseName(originalFilename);
        StringBuilder sb = new StringBuilder(basename.length());
        for (int i = 0; i < basename.length() && sb.length() < 256; i++) {
            char c = basename.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        String normalized = sb.toString().trim();
        return normalized.isEmpty() ? clipId + "." + extension : normalized;
    }

    /**
     * 관제에 넣을 영상 길이(초)를 확정한다. 요청이 명시하면 그 값을, 아니면 ffprobe 실측값을 쓴다.
     *
     * @param requestedSec 요청이 명시한 길이(초). null 이면 ffprobe 실측
     */
    private int resolveDurationSec(Integer requestedSec, Path savedPath, String clipIdForLog) {
        if (requestedSec != null) {
            if (requestedSec <= 0 || requestedSec > MAX_DURATION_SEC) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "vdoLenSec 는 1 ~ " + MAX_DURATION_SEC + " 초 범위만 허용됩니다.");
            }
            return requestedSec;
        }
        return extractDurationSec(savedPath, clipIdForLog);
    }

    /**
     * 업로드된 영상 파일에서 duration(초) 을 자동 추출한다.
     * <p>ffprobe 실패(손상된 영상/지원되지 않는 형식) 시 400 응답.
     * <p>0 이하 또는 상한 초과 값은 거절 (LS_DATA_RAW.DURATION_SEC 도메인 제약 — 1~7200s).
     *
     * @param savedPath 저장된 영상의 절대 경로
     * @param clipIdForLog 로그 마스킹용 — 원본은 출력하지 않고 hash 만 사용
     */
    int extractDurationSec(Path savedPath, String clipIdForLog) {
        int durationSec;
        try {
            durationSec = durationProbe.probe(savedPath);
        } catch (RuntimeException e) {
            log.warn("[DevAutolabelTest] ffprobe failed clipIdHash={} causeType={}",
                    hash(clipIdForLog), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이를 추출할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다.");
        }
        if (durationSec <= 0) {
            log.warn("[DevAutolabelTest] ffprobe returned non-positive duration clipIdHash={} durationSec={}",
                    hash(clipIdForLog), durationSec);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이가 유효하지 않습니다 (0초 이하).");
        }
        if (durationSec > MAX_DURATION_SEC) {
            log.warn("[DevAutolabelTest] ffprobe returned over-limit duration clipIdHash={} durationSec={}",
                    hash(clipIdForLog), durationSec);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이가 허용 한도(" + MAX_DURATION_SEC + "초) 를 초과합니다.");
        }
        return durationSec;
    }

    /**
     * ffprobe 바이너리 기반 duration 추출 — 운영 기본 구현.
     * <p>실패 시 RuntimeException 으로 위쪽 {@link #extractDurationSec} 가 변환.
     */
    private int probeWithFfprobe(Path filePath) {
        try {
            FFprobe ffprobe = new FFprobe(ffprobePath);
            FFmpegProbeResult probe = ffprobe.probe(filePath.toString());
            if (probe == null || probe.getFormat() == null) {
                throw new IllegalStateException("ffprobe 응답에 format 정보가 없습니다.");
            }
            double durationSec = probe.getFormat().duration;
            return (int) Math.round(durationSec);
        } catch (IOException e) {
            throw new IllegalStateException("ffprobe 호출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * CWE-22 Path Traversal 방어 — <b>관제 NAS 기준 경로</b> prefix 검증.
     *
     * <p>파일명은 {@code {clipId}.{ext}} 이고 clipId 는 allowlist 패턴을 통과한 값이지만, 기준 경로가
     * {@code authoring.storage.raw-path} 에서 {@code clip-nas-base-path} 로 바뀌었으므로 prefix 검증
     * 기준도 함께 바뀌어야 한다(구 구현은 raw-path 기준이라 base 를 밖으로 오버라이드하면 무력화된다).
     */
    private Path resolveSafeStoragePath(String safeFileName) {
        Path candidate = clipNasBasePath.resolve(safeFileName).normalize();
        if (!candidate.startsWith(clipNasBasePath)) {
            log.warn("[DevAutolabelTest] path traversal attempt blocked");
            throw new CustomException(ErrorCode.FORBIDDEN,
                    "저장 경로가 허용 범위를 벗어납니다.");
        }
        return candidate;
    }

    /**
     * 응답용 상대 경로 — CWE-209 방어로 절대 경로를 노출하지 않는다.
     * 기본 설정처럼 clip 기준 경로가 storage raw-path 하위면 raw-path 기준 상대경로를,
     * 밖으로 오버라이드된 배포에서는 파일명만 돌려준다.
     */
    private String relativeSavedPath(Path savedAbsolutePath) {
        Path base = savedAbsolutePath.startsWith(storageRawPath) ? storageRawPath : clipNasBasePath;
        return base.relativize(savedAbsolutePath).toString().replace('\\', '/');
    }

    private static LocalDateTime toLocalDateTime(Instant instant, LocalDateTime fallback) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault()) : fallback;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** CWE-117 — 사용자 입력 원문 대신 해시만 로그로 남긴다. */
    private static String hash(String value) {
        return Integer.toHexString(value == null ? 0 : value.hashCode());
    }

    /**
     * 영상 파일 → duration(초) 추출 추상화. 테스트는 stub 구현 주입으로 ffprobe 의존성 제거.
     */
    @FunctionalInterface
    public interface DurationProbe {
        /**
         * @param filePath 저장된 영상의 절대 경로
         * @return duration in seconds (1 ~ 7200 범위 검증은 호출자가 수행)
         * @throws RuntimeException ffprobe 호출 실패 또는 영상 포맷 인식 불가
         */
        int probe(Path filePath);
    }
}
