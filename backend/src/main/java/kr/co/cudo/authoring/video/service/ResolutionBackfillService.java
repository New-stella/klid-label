package kr.co.cudo.authoring.video.service;

import jakarta.annotation.PostConstruct;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.video.dto.ResolutionBackfillResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.scheduler.ResolutionBackfillSweepJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 해상도 파생 산출물의 <b>비식별 저장소 이관 백필</b> (E-ISSUE-21/22/41 후속 데이터 정정).
 *
 * <p>코드만 고치면 이미 생성된 파생본(비디오·프레임)은 계속 raw 저장소에서 서빙되고, 마트 뷰는 원본
 * 경로를 비식별 경로로 노출한다. 이 배치가 기존 파생본을 비식별 서브트리로 옮긴다.
 *
 * <h3>왜 Flyway SQL 이 아닌가</h3>
 * <p>Flyway 는 파일을 옮길 수 없다. SQL 로 경로 문자열만 바꾸면 그 즉시 스트리밍·export 가 전부 404 가
 * 되어 B-ISSUE-61 이 재발한다. 따라서 스키마·뷰 변경(V133)과 파일 이관을 분리한다.
 *
 * <h3>안전 순서 (역전 금지)</h3>
 * <ol>
 *   <li><b>원천 검증</b> — DB 경로는 신뢰 대상이 아니다. 복사 원천이자 삭제 대상이므로 base·서브트리를
 *       먼저 검증한다(H-3).</li>
 *   <li><b>Copy</b> — 이동(move)이 아니라 복사로 원본을 보존한다.</li>
 *   <li><b>Verify</b> — 목적지 존재 + 크기 일치 확인(0바이트 거부).</li>
 *   <li><b>DB UPDATE 커밋</b> — {@link ResolutionBackfillTxService} 짧은 트랜잭션(커밋 시 캐시 무효화).</li>
 *   <li><b>구 파일 유예 등록</b> — 커밋 성공 이후에 <b>삭제하지 않고</b> 대기열에 올린다(아래).</li>
 *   <li><b>유예 경과분 정리</b> — 유예가 지난 잔존물만 실제 삭제한다.</li>
 * </ol>
 *
 * <h3>왜 즉시 삭제하지 않는가 — 2노드 캐시 stale (Critical)</h3>
 * <p>{@code stream-meta} 캐시는 <b>프로세스 로컬</b>(Caffeine)이고 배포는 <b>2노드 Active-Active</b> 라,
 * 노드A 의 evict 는 노드B 에 전파되지 않는다. 노드A 가 경로를 옮기고 구 파일을 <b>즉시 지우면</b> 노드B 는
 * 최대 TTL({@link CacheConfig#STREAM_META_TTL}) 동안 <b>사라진 경로</b>를 서빙해 500 이 난다. 같은 이유로
 * {@code @Cacheable} 의 get→load→put 이 비원자라, 커밋 전에 읽은 요청이 evict 이후에 옛 값을 재설치할 수도
 * 있다(같은 노드 안에서도 발생).
 *
 * <p>Redis 등 공유 캐시는 도입하지 않기로 확정됐으므로, 문제를 <b>"stale 경로가 남는 것"이 아니라
 * "그 경로의 파일이 사라진 것"</b>으로 재정의해 닫는다 — 구 파일을 TTL 보다 확실히 긴 <b>유예</b>
 * (기본 15분, {@code authoring.resolution-backfill.stale-grace-minutes}) 동안 남긴다. 두 파일은 <b>바이트
 * 동일 사본</b>(copy+size verify)이므로 stale 을 보는 노드도 정상 재생되고, TTL 경과 후 새 경로로 수렴한다.
 * 유예가 TTL 보다 짧게 설정되면 이 보증이 깨지므로 <b>기동 시 거부</b>한다({@link #validateGracePeriod()}).
 *
 * <h3>유예 대기열이 영원히 방치되지 않는 보장</h3>
 * <p>대기열은 <b>파일시스템 마커</b>({@code {rawBase}/.pending-delete/{sha256(path)}.pending}, 내용=대상
 * 절대경로, mtime=유예 시작시각)로 유지된다. DB 스키마를 늘리지 않고, 재기동·노드 교체와 무관하게 공유 NAS
 * 위에 남으며 멱등이다. 실제 정리는 <b>두 경로</b>가 보장한다:
 * <ol>
 *   <li>{@link ResolutionBackfillSweepJob} — 트래픽과 무관한 <b>주기 스케줄</b>(운영 기본 활성).
 *       "요청이 올 때만 청소"하는 기회적 정리는 트래픽이 끊기면 영원히 돌지 않으므로 채택하지 않는다.</li>
 *   <li>{@link #run(boolean)} — 백필을 다시 실행하면 매번 {@link #sweepPendingDeletions()} 를 수행한다
 *       (스케줄러가 꺼진 환경의 수동 보루).</li>
 * </ol>
 * 대기 중 잔존물 수는 응답({@code stalePendingCount})으로 노출해 운영자가 상태를 볼 수 있다.
 *
 * <h3>정책</h3>
 * <ul>
 *   <li><b>멱등</b> — 이미 비식별 경로를 가리키고 파일도 그 자리에 있으면 skip. 이때도 <b>레거시 잔존
 *       파일 유예 등록·정리는 매 실행 재시도</b>한다(M-3).</li>
 *   <li><b>실패 시 raw 경로 유지</b> — 경로를 NULL 로 만들지 않는다(현재 정상 서빙 중인 파생이
 *       즉시 404·export 누락으로 깨지는 것 방지). 실패 대상만 목록으로 반환하고 재실행 가능하다.</li>
 *   <li><b>대상 판별</b> — 파생 축({@code LS_DATA_RAW.ORGNL_RAW_SN} + {@code _RESL_} 마커)으로만 발견한다
 *       (H-5/A-4). {@code LS_DATA_AUG} 는 프리셋을 얻기 위한 <b>부가정보</b>일 뿐 발견 조건이 아니다 —
 *       확정 실패 시 예약행이 삭제되므로 AUG 없는 파생이 실재하며, 그런 파생이 영구 미발견되면 라벨 축을
 *       폐기한 것과 같은 실패가 반복된다.</li>
 * </ul>
 *
 * <h3>감사 (H-4)</h3>
 * <p>"어떤 rawSn 이 영향을 받는가"는 SQL 근사가 아니라 <b>서빙과 동일한 판정기</b>
 * ({@link StorageSubtreePolicy#verifyDeidentifiedFile})로 판정한다. base 를 주입받아 판정하므로 환경별
 * base 차이가 그대로 반영된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionBackfillService {

    /** 감사 커서 페이지 크기. */
    private static final int AUDIT_PAGE_SIZE = 500;
    /** 감사 1회 실행에서 판정할 최대 프레임 행 수(자원 고갈 방지 — API4:2023). */
    private static final int AUDIT_MAX_ROWS = 200_000;

    /** 구(레거시) 파생 산출물 루트 세그먼트 — {@code {rawBase}/resolution/{rawSn}/…} (M-3 정리 대상). */
    private static final String LEGACY_ROOT = "resolution";

    /** 유예 삭제 대기열 디렉토리 — {@code {rawBase}/.pending-delete}. 산출물 트리 밖이라 서빙과 무관하다. */
    private static final String PENDING_DIR = ".pending-delete";
    /** 유예 마커 확장자. */
    private static final String PENDING_SUFFIX = ".pending";
    /** 1회 스윕에서 처리할 최대 마커 수(자원 고갈 방지 — API4:2023). 나머지는 다음 스윕이 이어받는다. */
    private static final int SWEEP_MAX_MARKERS = 5_000;

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final ResolutionBackfillTxService txService;
    private final StreamMetaCacheEvictor streamMetaCacheEvictor;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 구 파일 삭제 유예(분) — {@link CacheConfig#STREAM_META_TTL} 보다 <b>길어야</b> 한다.
     * 짧으면 2노드 stale 캐시가 사라진 파일을 가리켜 500 이 재발하므로 기동 시 거부한다.
     */
    @Value("${authoring.resolution-backfill.stale-grace-minutes:15}")
    private long staleGraceMinutes;

    /**
     * 기동 시 유예 설정 검증 — 유예가 {@code stream-meta} TTL 보다 짧으면 <b>부팅을 거부</b>한다.
     *
     * <p>유예의 존재 이유가 "다른 노드의 stale 캐시가 TTL 동안 옛 경로를 계속 해석해도 파일이 살아 있게"
     * 하는 것이므로, TTL 보다 짧은 유예는 보증을 조용히 무력화한다(설정 실수로 500 재발). WARN 으로만
     * 흘리면 아무도 보지 않으므로 fail-closed 로 막는다.
     */
    @PostConstruct
    public void validateGracePeriod() {
        Duration grace = staleGrace();
        if (grace.compareTo(CacheConfig.STREAM_META_TTL) < 0) {
            throw new IllegalStateException(
                    "authoring.resolution-backfill.stale-grace-minutes 는 stream-meta TTL 이상이어야 합니다"
                            + " (grace=" + grace.toMinutes() + "m, ttl=" + CacheConfig.STREAM_META_TTL.toMinutes() + "m)");
        }
        log.info("[Video][ResolutionBackfill] stale delete grace={}m (stream-meta ttl={}m)",
                grace.toMinutes(), CacheConfig.STREAM_META_TTL.toMinutes());
    }

    /**
     * 백필을 실행한다. 대상별로 실패를 격리하여 한 건 실패가 나머지 이관을 막지 않는다.
     *
     * <p>실행 끝에서 항상 {@link #sweepPendingDeletions()} 를 수행한다 — 스케줄러가 꺼진 환경에서도
     * 재실행만으로 잔존물이 정리되도록 하는 보루다(dryRun 은 제외 — 아무것도 변경하지 않는다).
     *
     * @param dryRun true 면 파일 복사·DB 변경·삭제 없이 대상/감사 결과만 산출한다.
     */
    public ResolutionBackfillResponse run(boolean dryRun) {
        List<Object[]> rows = videoRepository.findResolutionDerivativeTargets();
        AuditResult audit = auditDeidentifiedFramePaths();
        List<ResolutionBackfillResponse.ViewExclusion> viewExcluded = viewGateExclusions();

        List<Long> migrated = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        List<ResolutionBackfillResponse.Failure> failures = new ArrayList<>();
        Set<Long> targetRawSns = new LinkedHashSet<>();
        Set<Long> unresolvedPreset = new LinkedHashSet<>();

        for (Object[] row : rows) {
            Long rawSn = toLong(row[0]);
            Long orgnlRawSn = toLong(row[1]);
            // A-4 — AUG 예약행이 사라진 파생(확정 실패 시 예약행 삭제 정책)도 <b>발견 대상</b>이다.
            //       프리셋 미확정은 조용히 건너뛰지 않고 결과에 드러낸다.
            String augTypeCd = (row[2] instanceof String s && !s.isBlank()) ? s : null;
            if (rawSn == null || orgnlRawSn == null) {
                continue;
            }
            targetRawSns.add(rawSn);
            if (augTypeCd == null) {
                unresolvedPreset.add(rawSn);
                log.warn("[Video][ResolutionBackfill] preset unresolved (no ACCEPTED RESL reservation) rawSn={}", rawSn);
            }
            if (dryRun) {
                continue;
            }
            try {
                if (migrateOne(rawSn, orgnlRawSn, augTypeCd)) {
                    migrated.add(rawSn);
                } else {
                    skipped.add(rawSn);
                }
            } catch (RuntimeException e) {
                // 실패 격리 — 경로는 기존(raw) 그대로 유지된다. 경로 원문 미노출(CWE-209).
                log.error("[Video][ResolutionBackfill] migration failed — path left unchanged rawSn={} cause={}",
                        rawSn, e.getClass().getSimpleName());
                failures.add(new ResolutionBackfillResponse.Failure(rawSn, e.getClass().getSimpleName()));
            }
        }

        // 유예 경과 잔존물 정리 — dryRun 은 어떤 것도 지우지 않는다(대기 수만 센다).
        StaleSweepResult sweep = dryRun ? countPendingOnly() : sweepPendingDeletions();

        List<Long> unmatched = audit.violatingRawSns().stream().filter(sn -> !targetRawSns.contains(sn)).toList();
        if (!unmatched.isEmpty()) {
            log.warn("[Video][ResolutionBackfill] audit found non-deidentified frame paths not covered by targets rawSns={}",
                    unmatched);
        }
        if (audit.truncated()) {
            // A-2 — 잘린 감사는 "위반 0" 을 안전 근거로 쓸 수 없다. 반드시 상위에 드러낸다.
            log.warn("[Video][ResolutionBackfill] audit TRUNCATED at maxRows={} — violations are a PARTIAL scan, "
                    + "do not treat empty violations as proof of no impact", AUDIT_MAX_ROWS);
        }
        log.info("[Video][ResolutionBackfill] done dryRun={} targets={} migrated={} skipped={} failed={} unmatched={} auditScanned={} auditViolations={} auditTruncated={} unresolvedPreset={} viewExcluded={} staleDeleted={} stalePending={}",
                dryRun, targetRawSns.size(), migrated.size(), skipped.size(), failures.size(), unmatched.size(),
                audit.scanned(), audit.violations().size(), audit.truncated(), unresolvedPreset.size(),
                viewExcluded.size(), sweep.deleted(), sweep.pending());
        return new ResolutionBackfillResponse(targetRawSns.size(), migrated, skipped, failures, unmatched,
                audit.scanned(), audit.violations(), audit.truncated(), List.copyOf(unresolvedPreset), viewExcluded,
                sweep.deleted(), sweep.pending());
    }

    // ---------------------------------------------------------------------
    // 감사 (H-4) — 코드와 동일 판정기로 실행
    // ---------------------------------------------------------------------

    /**
     * 비식별 경로를 가진 <b>모든</b> 프레임을 {@link StorageSubtreePolicy#verifyDeidentifiedFile} 로 판정해
     * 위반을 rawSn·사유별로 집계한다 — 서빙(export/이미지)과 <b>동일한 판정 로직</b>이다(H-4).
     *
     * <p>SQL 근사({@code POSITION('/frames/deid/' IN path) = 0})는 ①base 무검증 ②세그먼트가 아닌 부분일치
     * ③파일시스템 무검증의 3중 비동치가 있어 "영향 없음"을 입증하지 못했다. 여기서는 base 를 주입받아
     * 판정하므로 환경별 base 차이가 결과에 그대로 반영된다.
     */
    public AuditResult auditDeidentifiedFramePaths() {
        Path base = deidBase();
        Map<Long, Map<StorageSubtreePolicy.Verdict, Long>> byRaw = new LinkedHashMap<>();
        long scanned = 0;
        long cursor = 0L;
        boolean truncated = false;
        while (true) {
            if (scanned >= AUDIT_MAX_ROWS) {
                // A-2 — 상한 도달. <b>남은 행이 실제로 있는지</b> 1행 프로브로 확인해 "부분 스캔"임을 결과에
                //       표시한다. 표시가 없으면 20만 번째 이후 위반이 auditViolations=[] 로 보고돼,
                //       "영향 없음"이라는 증거가 조용히 부분 스캔에 근거하게 된다.
                truncated = !videoRepository.findDeidFramePathsAfter(cursor, 1).isEmpty();
                break;
            }
            List<Object[]> page = videoRepository.findDeidFramePathsAfter(cursor, AUDIT_PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (Object[] r : page) {
                Long srcSn = toLong(r[0]);
                Long rawSn = toLong(r[1]);
                String deidPath = (String) r[2];
                if (srcSn != null) {
                    cursor = srcSn;
                }
                scanned++;
                StorageSubtreePolicy.Verification v = StorageSubtreePolicy.verifyDeidentifiedFile(base, deidPath);
                if (!v.ok() && rawSn != null) {
                    byRaw.computeIfAbsent(rawSn, k -> new LinkedHashMap<>())
                            .merge(v.verdict(), 1L, Long::sum);
                }
            }
            if (page.size() < AUDIT_PAGE_SIZE) {
                break;
            }
        }

        List<ResolutionBackfillResponse.AuditViolation> violations = new ArrayList<>();
        byRaw.forEach((rawSn, counts) -> counts.forEach((verdict, cnt) ->
                violations.add(new ResolutionBackfillResponse.AuditViolation(rawSn, verdict.name(), cnt))));
        violations.sort(Comparator.comparing(ResolutionBackfillResponse.AuditViolation::rawSn)
                .thenComparing(ResolutionBackfillResponse.AuditViolation::verdict));
        return new AuditResult(scanned, List.copyOf(violations), List.copyOf(byRaw.keySet()), truncated);
    }

    /**
     * G-2 — 현행 {@code V_COMPLETED_FRAME} 게이트로 뷰에서 제외되는 영상 목록(조회 가능 형태로 산출).
     * 게이트는 결함 형태(원본 경로 = 비식별 경로)에 한정한다(M-1).
     */
    public List<ResolutionBackfillResponse.ViewExclusion> viewGateExclusions() {
        List<ResolutionBackfillResponse.ViewExclusion> out = new ArrayList<>();
        for (Object[] r : videoRepository.findRawSnsExcludedByFrameViewGate()) {
            Long rawSn = toLong(r[0]);
            Long cnt = toLong(r[1]);
            if (rawSn != null) {
                out.add(new ResolutionBackfillResponse.ViewExclusion(rawSn, cnt == null ? 0L : cnt));
            }
        }
        return out;
    }

    /**
     * 감사 결과 — 판정 행 수 + 위반 집계 + 위반 rawSn 집합 + <b>부분 스캔 여부</b>(A-2).
     * {@code truncated=true} 면 위반 목록은 전수가 아니다(상한 이후 미판정).
     */
    public record AuditResult(long scanned,
                              List<ResolutionBackfillResponse.AuditViolation> violations,
                              List<Long> violatingRawSns,
                              boolean truncated) {
    }

    // ---------------------------------------------------------------------
    // 이관
    // ---------------------------------------------------------------------

    /**
     * 파생 1건 이관 — 원천 검증 → Copy → Verify → DB 커밋 → <b>구 파일 유예 등록</b> → 레거시 유예 등록
     * → 캐시 재무효화.
     *
     * <p><b>구 파일은 여기서 지우지 않는다</b>(클래스 javadoc "왜 즉시 삭제하지 않는가"). 실제 삭제는
     * 유예가 지난 뒤 {@link #sweepPendingDeletions()} 가 수행한다.
     *
     * @return 실제 이관 수행 시 true, 이미 이관 완료(멱등 skip)면 false
     */
    private boolean migrateOne(Long rawSn, Long orgnlRawSn, String augTypeCd) {
        Path base = deidBase();
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);

        Map<Long, String> frameUpdates = new LinkedHashMap<>();
        List<Path> staleFiles = new ArrayList<>();

        // 1) 프레임 원천 검증 + Copy + Verify.
        for (LsDataSrc f : frames) {
            String cur = f.getDeidFilePath();
            if (cur == null || cur.isBlank()) {
                throw new CustomException(ErrorCode.CONFLICT,
                        "비식별 프레임 경로가 없어 이관할 수 없습니다: srcSn=" + f.getSrcSn());
            }
            // H-3 — DB 경로는 신뢰 대상이 아니다. 이 값은 ①복사 원천이자 ②삭제 대상이므로,
            //       검증 없이 쓰면 오염/레거시 행 하나로 부모 원본 프레임을 deid 서브트리로 복사한 뒤
            //       원본을 삭제하는 파괴적 동작이 성립한다(PII 유출 + 원본 소실 동시).
            Path src = validateMigrationSource(cur, "프레임");
            Path dst = safeDeidPath(base,
                    StorageSubtreePolicy.deidFramesDir(rawSn) + "/" + fileNameOf(src, f));
            if (src.equals(dst)) {
                // 이미 목표 위치 = 이관 완료(멱등). 다른 프레임과 섞이지 않도록 개별 판정한다.
                continue;
            }
            copyAndVerify(src, dst);
            frameUpdates.put(f.getSrcSn(), dst.toString());
            staleFiles.add(src);
        }

        // 2) 영상 파일 원천 검증 + Copy + Verify.
        //    A-4 — 프리셋 미확정(AUG 예약행 소실)이면 영상 파일 경로 규약을 만들 수 없으므로 영상은 손대지
        //    않고 프레임만 이관한다. 해당 rawSn 은 unresolvedPresetRawSns 로 보고된다(침묵 skip 아님).
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "파생 영상을 찾을 수 없습니다: rawSn=" + rawSn));
        String newVideoPath = null;
        Path staleVideo = null;
        String staleVideoDbValue = null;
        if (augTypeCd != null) {
            Path curVideo = validateMigrationSource(raw.getRawFilePathNm(), "영상");
            // A-6 — 목적 경로 키에 <b>파생 rawSn</b> 을 포함한다(같은 (부모,프리셋) 파생 복수 존재 시 상호 덮어쓰기 차단).
            Path dstVideo = safeDeidPath(base,
                    StorageSubtreePolicy.resolutionVideoFile(orgnlRawSn, rawSn, augTypeCd));
            if (!curVideo.equals(dstVideo)) {
                copyAndVerify(curVideo, dstVideo);
                newVideoPath = dstVideo.toString();
                staleVideo = curVideo;
                staleVideoDbValue = raw.getRawFilePathNm();
            }
        }

        if (frameUpdates.isEmpty() && newVideoPath == null) {
            // M-3 — "이미 이관 완료" 여도 레거시 잔존 파일 유예 등록은 <b>매 실행 재시도</b>한다.
            //       등록 실패를 조용히 삼킨 뒤 다음 실행이 skip 으로 빠지면 구 파일이 영구 잔존한다.
            markLegacyArtifactsStale(rawSn, orgnlRawSn, augTypeCd);
            log.info("[Video][ResolutionBackfill] already migrated — skip rawSn={}", rawSn);
            return false;
        }

        // 3) DB 커밋(원자, 커밋 후 캐시 evict) → 4) 구 파일 <b>유예 등록</b>(삭제 아님) → 5) 레거시 유예 등록.
        txService.commitRelocation(rawSn, frameUpdates, newVideoPath);
        staleFiles.forEach(p -> markStale(p, rawSn));
        if (staleVideo != null) {
            // A-6 — 구 영상 파일은 <b>다른 파생이 아직 참조 중</b>일 수 있다(구 규약은 (부모,프리셋) 키라
            //       한 파일을 여러 rawSn 이 공유했다). 참조 판정은 <b>삭제 직전</b>(스윕)으로 미룬다 —
            //       그 사이 마지막 참조가 옮겨가면 그때 삭제된다(재실행 멱등, 판정이 더 정확해진다).
            markStale(staleVideo, rawSn);
        }
        markLegacyArtifactsStale(rawSn, orgnlRawSn, augTypeCd);
        // 6) 캐시 재무효화 — 커밋 시점(TxService.afterCommit) evict 이후에 <b>커밋 전에 읽은</b> 요청이
        //    옛 값을 다시 put 했을 수 있다(@Cacheable get→load→put 비원자). 전체 완료 후 한 번 더 비워
        //    창을 좁힌다. 본 메서드는 비트랜잭션이고 DB 는 이미 커밋됐으므로 즉시 evict 가 안전하다.
        //    (다른 노드에는 전파되지 않는다 — 그 잔여 stale 은 유예 삭제로 무해화한다.)
        streamMetaCacheEvictor.evict(rawSn);
        return true;
    }

    /**
     * H-3 — 이관 원천(복사 소스 = 삭제 대상) 경로 검증.
     *
     * <p>DB 값을 {@code normalize()} 만 해서 쓰면 어떤 저장소의 어떤 서브트리인지 전혀 모른 채 복사·삭제가
     * 일어난다. 다음을 강제한다:
     * <ul>
     *   <li>두 저장소 base(raw/deid) 중 하나의 <b>하위</b>여야 한다(CWE-22 traversal + 임의 경로 차단).</li>
     *   <li>{@code frames/raw/**}(원본 프레임 서브트리)이면 <b>거부</b> — 부모 원본을 비식별 벌로 복제하고
     *       원본을 삭제하는 최악 시나리오를 원천 차단한다.</li>
     *   <li>존재한다면 <b>정규 파일</b>이어야 한다(심링크·디렉토리 거부).</li>
     * </ul>
     */
    private Path validateMigrationSource(String pathValue, String what) {
        if (pathValue == null || pathValue.isBlank()) {
            throw new CustomException(ErrorCode.CONFLICT, what + " 경로가 없어 이관할 수 없습니다.");
        }
        Path rawBase = rawBase();
        Path deid = deidBase();
        Path resolved;
        try {
            Path candidate = Paths.get(pathValue);
            resolved = candidate.isAbsolute() ? candidate.normalize() : deid.resolve(candidate).normalize();
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, what + " 경로를 해석할 수 없습니다.");
        }
        if (!resolved.startsWith(rawBase) && !resolved.startsWith(deid)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, what + " 원천 경로가 허용된 저장소를 벗어납니다.");
        }
        if (StorageSubtreePolicy.isRawFrameArtifact(rawBase, resolved)
                || StorageSubtreePolicy.isRawFrameArtifact(deid, resolved)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    what + " 원천 경로가 원본 프레임 서브트리를 가리켜 이관할 수 없습니다.");
        }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new CustomException(ErrorCode.CONFLICT, what + " 원천 경로가 정규 파일이 아닙니다.");
        }
        return resolved;
    }

    /**
     * 복사 후 검증 — 목적지 존재 + 크기 일치. 원본이 이미 없고 목적지만 있으면 직전 실행의
     * 삭제 단계 직전에서 끊긴 것이므로 정상으로 본다(멱등 재실행).
     *
     * <p><b>M-2</b>: 그 skip 분기에서도 <b>크기 검증을 생략하지 않는다</b>. 직전 실행이 남긴 0바이트/절단
     * 목적지를 "이관 완료"로 승인하면 파일이 손상된 채 구 파일이 삭제된다.
     */
    private static void copyAndVerify(Path src, Path dst) {
        try {
            if (!Files.exists(src)) {
                if (Files.exists(dst) && Files.isRegularFile(dst) && Files.size(dst) > 0L) {
                    return; // 이전 실행이 복사까지 마친 상태 — 재복사 불필요(크기 검증 통과).
                }
                throw new CustomException(ErrorCode.NOT_FOUND,
                        "이관 대상 파일이 존재하지 않거나 목적지가 손상되었습니다.");
            }
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            long dstSize = Files.exists(dst) ? Files.size(dst) : -1L;
            if (dstSize < 0L || dstSize != Files.size(src)) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "이관 파일 검증에 실패했습니다.");
            }
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이관 파일 복사에 실패했습니다.", e);
        }
    }

    /** 목적 경로를 비식별 base + 비식별 서브트리로 강제 해석한다(CWE-22 + CWE-359). */
    private static Path safeDeidPath(Path base, String relative) {
        Path resolved = base.resolve(relative).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이관 목적 경로가 비식별 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    /**
     * M-3 — 레거시(raw base) 파생 산출물 잔존 <b>유예 등록</b>. 구 스킴은
     * {@code {rawBase}/resolution/{rawSn}/…}(프레임)·{@code {rawBase}/resolution/{parentRawSn}/{preset}/…}(영상)
     * 였다. 파생 산출물 base 가 비식별 저장소로 옮겨지면서 이 경로를 지우는 코드가 사라져, 원본 저장소에
     * 파생 사본이 무기한 잔존했다. 매 실행 재시도한다(등록 실패는 다음 실행이 다시 시도).
     *
     * <p>여기서 <b>삭제하지 않는다</b> — 대기열에만 올리고, 참조 판정과 실제 삭제는 유예가 지난 뒤
     * {@link #sweepPendingDeletions()} 가 일괄 수행한다. 참조 판정을 삭제 직전으로 미루면 그 사이 이관된
     * 다른 파생의 상태까지 반영되어 판정이 더 안전해진다(A-6 공유 파일 오삭제 방지 유지).
     */
    private void markLegacyArtifactsStale(Long rawSn, Long orgnlRawSn, String augTypeCd) {
        Path rawBase = rawBase();
        markLegacyDirStale(rawBase, LEGACY_ROOT + "/" + rawSn, rawSn);
        if (orgnlRawSn != null && augTypeCd != null && !augTypeCd.isBlank()) {
            // A-6 — {부모}/{프리셋} 디렉토리는 구 경로 규약상 <b>같은 (부모,프리셋) 파생들이 공유</b>한다.
            markLegacyDirStale(rawBase, LEGACY_ROOT + "/" + orgnlRawSn + "/" + augTypeCd, rawSn);
        }
    }

    /** 레거시 디렉토리의 정규 파일을 유예 대기열에 올린다 — raw base 하위 {@code resolution/**} 만 허용. */
    private void markLegacyDirStale(Path rawBase, String relative, Long rawSn) {
        Path dir = rawBase.resolve(relative).normalize();
        Path legacyRoot = rawBase.resolve(LEGACY_ROOT).normalize();
        if (!dir.startsWith(legacyRoot) || dir.equals(legacyRoot)) {
            return; // 방어 — resolution 루트 자체·이탈 경로는 절대 대상이 아니다.
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .forEach(p -> markStale(p, rawSn));
        } catch (IOException ignored) {
            // best-effort — 순회 실패도 다음 실행이 재시도한다.
        }
    }

    private static String fileNameOf(Path src, LsDataSrc frame) {
        Path name = src.getFileName();
        return name != null ? name.toString() : ("frame-" + frame.getFrameNo() + ".jpg");
    }

    // ---------------------------------------------------------------------
    // 유예 삭제 대기열 (2노드 stale 캐시 대응)
    // ---------------------------------------------------------------------

    /**
     * 구 파일을 <b>유예 대기열</b>에 올린다(삭제하지 않는다). 마커가 이미 있으면 <b>덮어쓰지 않는다</b> —
     * 유예 시작시각(mtime)이 뒤로 밀려 영원히 삭제되지 않는 것을 막는다.
     *
     * <p>마커 파일명은 대상 절대경로의 SHA-256 hex 라 같은 파일에 마커가 중복 생기지 않고(멱등),
     * 파일명 길이·문자 제약도 받지 않는다. 내용은 대상 절대경로 1줄이다.
     */
    private void markStale(Path file, Long rawSn) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return; // 이미 없거나 정규 파일이 아니면 대기열 대상이 아니다.
            }
            Path dir = pendingDir();
            Files.createDirectories(dir);
            Path marker = dir.resolve(markerName(file));
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                return; // 유예 시작시각 보존(리셋 금지).
            }
            Files.writeString(marker, file.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            log.info("[Video][ResolutionBackfill] stale file queued for deferred delete rawSn={} graceMinutes={}",
                    rawSn, staleGraceMinutes);
        } catch (FileAlreadyExistsException ignored) {
            // 동시 실행이 먼저 만들었다 — 그대로 둔다(유예 시작시각 보존).
        } catch (IOException | RuntimeException e) {
            // 등록 실패는 <b>구 파일 보존</b>으로 귀결된다(삭제되지 않음 = 안전측). 다음 실행이 재시도한다.
            log.warn("[Video][ResolutionBackfill] stale queue register failed — keep file rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 유예가 지난 잔존물을 실제 삭제한다 — 백필 재실행과 {@link ResolutionBackfillSweepJob} 주기 잡이
     * 호출한다(둘 중 하나만 돌아도 대기열이 소진된다).
     *
     * <p>삭제 조건(모두 충족해야 함):
     * <ol>
     *   <li><b>유예 경과</b> — 마커 mtime + grace ≤ now. 유예 중이면 건드리지 않는다(2노드 stale 보호).</li>
     *   <li><b>DB 미참조</b> — {@link VideoRepository#countReferencesToFilePath} == 0. 즉 DB 가 이미 새
     *       경로를 가리키고 다른 행도 이 파일을 쓰지 않는다. 조회 실패는 <b>보존</b>으로 판단한다.</li>
     *   <li><b>저장소 내부</b> — raw/deid base 하위이고 {@code frames/raw/**}(원본 프레임)가 아니다.
     *       마커 내용은 파일이므로 신뢰하지 않고 매번 재검증한다(CWE-22).</li>
     *   <li><b>정규 파일</b> — 심링크·디렉토리는 삭제하지 않는다({@code NOFOLLOW_LINKS}).</li>
     * </ol>
     *
     * @return 이번 스윕에서 삭제한 수 + 아직 유예/참조로 대기 중인 수
     */
    public StaleSweepResult sweepPendingDeletions() {
        Path dir = pendingDir();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new StaleSweepResult(0, 0);
        }
        int deleted = 0;
        int pending = 0;
        for (Path marker : listMarkers(dir)) {
            if (sweepOne(marker)) {
                deleted++;
            } else if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                pending++;
            }
        }
        if (deleted > 0 || pending > 0) {
            log.info("[Video][ResolutionBackfill] stale sweep done deleted={} pending={} graceMinutes={}",
                    deleted, pending, staleGraceMinutes);
        }
        return new StaleSweepResult(deleted, pending);
    }

    /** dryRun 전용 — 아무것도 지우지 않고 대기 중인 잔존물 수만 센다. */
    private StaleSweepResult countPendingOnly() {
        Path dir = pendingDir();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new StaleSweepResult(0, 0);
        }
        return new StaleSweepResult(0, listMarkers(dir).size());
    }

    /** 마커 목록(상한 적용) — 남은 마커는 다음 스윕이 이어받는다. */
    private List<Path> listMarkers(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().endsWith(PENDING_SUFFIX))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .limit(SWEEP_MAX_MARKERS)
                    .toList();
        } catch (IOException e) {
            log.warn("[Video][ResolutionBackfill] stale queue listing failed cause={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** 마커 1건 처리. @return 대상 파일을 실제로 삭제했으면 true. */
    private boolean sweepOne(Path marker) {
        String raw;
        try {
            raw = Files.readString(marker, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn("[Video][ResolutionBackfill] stale marker unreadable — keep cause={}", e.getClass().getSimpleName());
            return false;
        }
        if (raw.isBlank()) {
            discardMarker(marker); // 내용 없는 마커는 삭제 대상이 없다.
            return false;
        }
        Path target;
        try {
            target = Paths.get(raw).normalize();
        } catch (RuntimeException e) {
            discardMarker(marker);
            return false;
        }
        if (!isDeletableLocation(target)) {
            // 저장소 밖·원본 프레임 서브트리 — 절대 지우지 않는다. 마커만 버려 무한 재시도를 막는다.
            log.warn("[Video][ResolutionBackfill] stale target outside deletable area — never delete, drop marker");
            discardMarker(marker);
            return false;
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            discardMarker(marker); // 이미 사라짐 — 대기열에서 내린다.
            return false;
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("[Video][ResolutionBackfill] stale target is not a regular file — skip delete, drop marker");
            discardMarker(marker);
            return false;
        }
        if (!graceElapsed(marker)) {
            return false; // 유예 중 — 다른 노드의 stale 캐시가 이 파일을 아직 서빙할 수 있다.
        }
        try {
            if (videoRepository.countReferencesToFilePath(raw) > 0L) {
                log.info("[Video][ResolutionBackfill] stale file still referenced — keep (re-check next sweep)");
                return false;
            }
        } catch (RuntimeException e) {
            // 조회 실패는 보존 쪽으로 판단한다(삭제는 되돌릴 수 없다).
            log.warn("[Video][ResolutionBackfill] stale reference check failed — keep cause={}",
                    e.getClass().getSimpleName());
            return false;
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            log.warn("[Video][ResolutionBackfill] stale file delete failed — will retry next sweep cause={}",
                    e.getClass().getSimpleName());
            return false;
        }
        discardMarker(marker);
        pruneEmptyLegacyDirs(target.getParent());
        return true;
    }

    /** 마커 mtime + 유예 ≤ now 인가. mtime 조회 실패는 <b>미경과</b>(보존)로 본다. */
    private boolean graceElapsed(Path marker) {
        try {
            Instant since = Files.getLastModifiedTime(marker, LinkOption.NOFOLLOW_LINKS).toInstant();
            return !Instant.now().isBefore(since.plus(staleGrace()));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 삭제 가능한 위치인가 — 두 저장소 base 하위이면서 원본 프레임 서브트리({@code frames/raw/**})가 아님.
     * 마커 내용은 신뢰 대상이 아니므로 삭제 직전에 다시 판정한다(H-3 와 동일 기준).
     */
    private boolean isDeletableLocation(Path target) {
        if (!target.isAbsolute()) {
            return false;
        }
        Path rawBase = rawBase();
        Path deid = deidBase();
        if (!target.startsWith(rawBase) && !target.startsWith(deid)) {
            return false;
        }
        return !StorageSubtreePolicy.isRawFrameArtifact(rawBase, target)
                && !StorageSubtreePolicy.isRawFrameArtifact(deid, target);
    }

    /** 비어버린 레거시 디렉토리를 {@code resolution} 루트 직하까지 정리한다(루트 자체는 보존). */
    private void pruneEmptyLegacyDirs(Path start) {
        Path legacyRoot = rawBase().resolve(LEGACY_ROOT).normalize();
        Path cur = start;
        while (cur != null && cur.startsWith(legacyRoot) && !cur.equals(legacyRoot)) {
            try (Stream<Path> children = Files.list(cur)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.deleteIfExists(cur);
            } catch (IOException e) {
                return;
            }
            cur = cur.getParent();
        }
    }

    private void discardMarker(Path marker) {
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            log.warn("[Video][ResolutionBackfill] stale marker delete failed cause={}", e.getClass().getSimpleName());
        }
    }

    private Path pendingDir() {
        return rawBase().resolve(PENDING_DIR).normalize();
    }

    private Duration staleGrace() {
        return Duration.ofMinutes(Math.max(0L, staleGraceMinutes));
    }

    /** 대상 절대경로의 SHA-256 hex + {@code .pending} — 경로 1:1 대응(멱등) + 파일명 제약 회피. */
    private static String markerName(Path file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(file.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb + PENDING_SUFFIX;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 유예 삭제 스윕 결과.
     *
     * @param deleted 이번 스윕에서 실제 삭제한 잔존물 수
     * @param pending 아직 유예가 지나지 않았거나 DB 참조가 남아 대기 중인 잔존물 수
     */
    public record StaleSweepResult(int deleted, int pending) {
    }

    private Path rawBase() {
        return Paths.get(storageRawPath).toAbsolutePath().normalize();
    }

    private Path deidBase() {
        return Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
    }

    private static Long toLong(Object v) {
        return (v instanceof Number n) ? n.longValue() : null;
    }
}
