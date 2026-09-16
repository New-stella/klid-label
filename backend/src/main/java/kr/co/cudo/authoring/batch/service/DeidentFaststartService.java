package kr.co.cudo.authoring.batch.service;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.storage.DeidentArtifactIntegrity;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 외부 비식별 산출물의 <b>재생 인덱스(moov) 재배치</b> — 비식별 완료가 커밋된 뒤, 인덱스가 파일 끝에
 * 있으면 재인코딩 없이 앞으로 옮긴 파일로 <b>같은 경로·같은 파일명</b>에 원자적으로 교체한다.
 *
 * <h3>시점 — 완료를 실제로 커밋한 노드에서만</h3>
 * <p>{@link #scheduleAfterCommit} 는 비식별 완료 트랜잭션 안에서 불리고 {@code afterCommit} 에 실행을
 * 미룬다. 완료 전이는 조건부 UPDATE 선점({@code claimDownloadCompletion})을 얻은 호출만 진행하므로
 * (4노드 Active-Active 중 한 노드), 같은 산출물을 두 노드가 동시에 재배치하지 않는다. 같은 노드 안의
 * 중복 실행은 실경로 단위 진행 표식으로 막는다. 롤백되면 실행하지 않는다.
 *
 * <h3>작업 파일 위치 — 산출 디렉터리 밖</h3>
 * <p>결과 회수 폴백({@code KpstDeidentService.scanSingleUsable})은 산출 디렉터리 바로 아래 파일을
 * <b>이름과 무관하게</b> 무결성만으로 세고, 둘 이상이면 「산출물이 모호함」으로 실패한다. 재위탁 직전
 * 정리({@code cleanExportDir})도 그 디렉터리 바로 아래 정규 파일을 지운다. 그래서 작업 파일은 산출
 * 디렉터리의 <b>형제 숨김 디렉터리</b>({@value #WORK_DIR_NAME})에 둔다 — 같은 부모라 통상 같은 파일
 * 시스템이며, 두 스캔은 비재귀라 이 디렉터리를 보지 않는다. 원자 이동이 불가능하면(다른 파일 시스템)
 * 교체하지 않는다. 비식별 제외 복사({@code DeidentExclusionService})가 산출 디렉터리 안에 임시 파일을
 * 두는 것과 다른 이유가 이것이다 — 그 복사본은 결과 회수를 거치지 않는다.
 *
 * <h3>재위탁과의 경합</h3>
 * <p>교체 직전 대상 파일의 식별(파일 키·크기·수정 시각)이 변환 시작 때와 같은지 다시 본다. 그 사이
 * 재위탁이 디렉터리를 비웠거나 새 산출물이 들어왔으면 교체하지 않는다(새 산출물을 옛 내용으로 덮지 않는다).
 *
 * <h3>실패 — fail-open</h3>
 * <p>어떤 실패도 비식별 완료 상태·기록된 경로를 되돌리지 않는다. 원래 산출물은 그대로 두고 작업
 * 파일은 지우며, 실패 사실은 WARN 로그와 지표({@value #METRIC_NAME}, {@code result} 태그)로 남긴다.
 * 파일 입출력·ffmpeg 실행은 데이터 트랜잭션 밖(전용 실행기)에서 한다.
 *
 * <h3>동시성</h3>
 * <p>전용 실행기로 동시 실행 수를 제한한다({@code authoring.deidentify.faststart.max-concurrency},
 * 기본 1). 대기열이 가득 차면 그 건은 재배치하지 않고 기록만 남긴다(fail-open).
 *
 * <h3>재생 중인 세션</h3>
 * <p>원자 이동(rename)은 이미 열린 파일 핸들이 옛 파일을 끝까지 읽게 둔다. 교체 뒤 이 노드의 스트림 메타
 * 캐시를 비운다(파일 크기가 바뀔 수 있다). 캐시는 노드 로컬이라 다른 노드에는 TTL 동안 옛 크기가 남지만,
 * 스트림 응답은 부분 요청 경계·416 판정·전체 길이를 <b>요청마다 잰 실제 크기</b>로 계산하므로
 * ({@code VideoStreamService.stream}) 캐시와 파일 크기의 불일치가 경계 오류로 이어지지 않는다.
 * 재생 도중 교체돼 옛 인덱스를 들고 있는 세션은 화면의 재생 오류 → 주소 재발급 → 재생 위치 복원 동선으로 회복된다.
 *
 * @design ADR-072
 * @design AC-1063
 * @design AC-1064
 * @design DFEAT-041
 */
@Slf4j
@Service
public class DeidentFaststartService {

    /** 작업 디렉터리 이름 — 산출 디렉터리의 형제. */
    static final String WORK_DIR_NAME = ".deid-faststart";
    static final String METRIC_NAME = "deident.faststart";

    /** 기록 사유 코드 — 로그·지표에만 쓰는 고정 상수(외부 유래 문자열 금지, CWE-117). */
    static final String REASON_REMUX_FAILED = "REMUX_FAILED";
    static final String REASON_OUTPUT_INVALID = "OUTPUT_INVALID";
    static final String REASON_TARGET_CHANGED = "TARGET_CHANGED";
    static final String REASON_ATOMIC_MOVE_UNSUPPORTED = "ATOMIC_MOVE_UNSUPPORTED";
    static final String REASON_WORK_DIR_REJECTED = "WORK_DIR_REJECTED";
    static final String REASON_QUEUE_FULL = "QUEUE_FULL";

    /** 변환 결과 크기 허용 편차 — 인덱스만 옮기므로 크기는 거의 같다. */
    private static final long SIZE_TOLERANCE_MIN_BYTES = 1_048_576L;
    private static final double SIZE_TOLERANCE_RATIO = 0.02d;

    /** 재배치 결과. */
    public enum Outcome {
        RELOCATED,
        ALREADY_FASTSTART,
        UNDETERMINED,
        SKIPPED,
        FAILED
    }

    private final VideoArtifactRootResolver artifactRootResolver;
    private final DeidentFaststartRemuxer remuxer;
    private final StreamMetaCacheEvictor streamMetaCacheEvictor;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final boolean enabled;
    private final Executor executor;
    /** 이 클래스가 만든 실행기면 종료 시 닫는다(시험이 주입한 실행기는 닫지 않는다). */
    private final ExecutorService ownedExecutor;
    private final Set<Path> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public DeidentFaststartService(
            VideoArtifactRootResolver artifactRootResolver,
            DeidentFaststartRemuxer remuxer,
            StreamMetaCacheEvictor streamMetaCacheEvictor,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${authoring.deidentify.faststart.enabled:true}") boolean enabled,
            @Value("${authoring.deidentify.faststart.max-concurrency:1}") int maxConcurrency,
            @Value("${authoring.deidentify.faststart.queue-capacity:32}") int queueCapacity) {
        this(artifactRootResolver, remuxer, streamMetaCacheEvictor, meterRegistry, enabled,
                newExecutor(maxConcurrency, queueCapacity), true);
    }

    /** 시험 seam — 실행기를 주입한다. */
    DeidentFaststartService(VideoArtifactRootResolver artifactRootResolver,
                            DeidentFaststartRemuxer remuxer,
                            StreamMetaCacheEvictor streamMetaCacheEvictor,
                            ObjectProvider<MeterRegistry> meterRegistry,
                            boolean enabled,
                            Executor executor) {
        this(artifactRootResolver, remuxer, streamMetaCacheEvictor, meterRegistry, enabled, executor, false);
    }

    private DeidentFaststartService(VideoArtifactRootResolver artifactRootResolver,
                                    DeidentFaststartRemuxer remuxer,
                                    StreamMetaCacheEvictor streamMetaCacheEvictor,
                                    ObjectProvider<MeterRegistry> meterRegistry,
                                    boolean enabled,
                                    Executor executor,
                                    boolean owned) {
        this.artifactRootResolver = artifactRootResolver;
        this.remuxer = remuxer;
        this.streamMetaCacheEvictor = streamMetaCacheEvictor;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.executor = executor;
        this.ownedExecutor = owned && executor instanceof ExecutorService es ? es : null;
    }

    /** 전용 실행기 — 동시 실행 상한 + 유계 대기열 + 포화 시 거부(호출 스레드에서 실행하지 않는다). */
    private static ThreadPoolExecutor newExecutor(int maxConcurrency, int queueCapacity) {
        int threads = maxConcurrency > 0 ? maxConcurrency : 1;
        int capacity = queueCapacity > 0 ? queueCapacity : 32;
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "deid-faststart-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), factory, new ThreadPoolExecutor.AbortPolicy());
        // 완료가 드물어 대부분 유휴다 — 유휴 스레드를 상주시키지 않는다.
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            // 진행 중인 변환은 인터럽트로 끊는다 — 실행기가 프로세스를 강제 종료하고 작업 파일을 지운다.
            ownedExecutor.shutdownNow();
        }
    }

    /**
     * 비식별 완료가 <b>커밋된 뒤</b> 재배치를 전용 실행기에 넘긴다. 활성 트랜잭션이 없으면 즉시 넘긴다.
     * 이 메서드와 그 콜백은 예외를 밖으로 내보내지 않는다 — 완료 전이를 실패로 오인하게 하지 않는다.
     *
     * @param rawSn         영상 PK
     * @param rawFilePathNm 원본 경로(산출 디렉터리 허용 목록 계산용)
     * @param deidFilePath  처리 이력에 기록된 산출물 경로
     */
    public void scheduleAfterCommit(Long rawSn, String rawFilePathNm, String deidFilePath) {
        if (!enabled || rawSn == null || deidFilePath == null || deidFilePath.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(rawSn, rawFilePathNm, deidFilePath);
                }
            });
        } else {
            submit(rawSn, rawFilePathNm, deidFilePath);
        }
    }

    private void submit(Long rawSn, String rawFilePathNm, String deidFilePath) {
        try {
            executor.execute(() -> relocateQuietly(rawSn, rawFilePathNm, deidFilePath));
        } catch (RejectedExecutionException e) {
            record(Outcome.FAILED);
            log.warn("[Deident] faststart relocation not scheduled rawSn={} reason={}", rawSn, REASON_QUEUE_FULL);
        } catch (RuntimeException e) {
            record(Outcome.FAILED);
            log.warn("[Deident] faststart relocation not scheduled rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    private void relocateQuietly(Long rawSn, String rawFilePathNm, String deidFilePath) {
        try {
            relocate(rawSn, rawFilePathNm, deidFilePath);
        } catch (RuntimeException e) {
            record(Outcome.FAILED);
            log.warn("[Deident] faststart relocation failed rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 재배치를 동기로 수행한다(실행기 스레드에서 불린다). 실패는 {@link Outcome#FAILED} 로 돌려주며
     * 원래 산출물은 그대로다.
     */
    Outcome relocate(Long rawSn, String rawFilePathNm, String deidFilePath) {
        if (!enabled || rawSn == null || deidFilePath == null || deidFilePath.isBlank()) {
            return done(Outcome.SKIPPED);
        }
        Path target;
        try {
            target = Paths.get(deidFilePath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return done(Outcome.SKIPPED);
        }
        DeidentFaststartRemuxer.Container container = containerOf(target);
        if (container == null) {
            return done(Outcome.UNDETERMINED);
        }
        Path deidDir = matchDeidDir(rawSn, rawFilePathNm, target);
        if (deidDir == null) {
            log.warn("[Deident] faststart skipped — artifact not in deid video dir rawSn={}", rawSn);
            return done(Outcome.SKIPPED);
        }
        Path realTarget;
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("[Deident] faststart skipped — artifact is not a regular file rawSn={}", rawSn);
                return done(Outcome.SKIPPED);
            }
            realTarget = VideoArtifactRootResolver.resolveRealPathUnder(target, deidDir);
        } catch (RuntimeException e) {
            log.warn("[Deident] faststart skipped — artifact path rejected rawSn={}", rawSn);
            return done(Outcome.SKIPPED);
        }

        DeidentFaststartInspector.Layout layout = DeidentFaststartInspector.inspect(realTarget);
        if (layout == DeidentFaststartInspector.Layout.FASTSTART) {
            log.debug("[Deident] faststart not needed rawSn={}", rawSn);
            return done(Outcome.ALREADY_FASTSTART);
        }
        if (layout != DeidentFaststartInspector.Layout.MOOV_AT_END) {
            log.info("[Deident] faststart skipped — index position undetermined rawSn={}", rawSn);
            return done(Outcome.UNDETERMINED);
        }

        if (!inFlight.add(realTarget)) {
            log.info("[Deident] faststart skipped — already in progress rawSn={}", rawSn);
            return done(Outcome.SKIPPED);
        }
        Path work = null;
        try {
            FileIdentity before = FileIdentity.of(realTarget);
            Path workDir = prepareWorkDir(deidDir);
            if (workDir == null) {
                return fail(rawSn, REASON_WORK_DIR_REJECTED, null);
            }
            work = workDir.resolve(rawSn + "-" + UUID.randomUUID() + "." + extensionOf(realTarget));

            try {
                remuxer.remux(realTarget, work, container);
            } catch (IOException | RuntimeException e) {
                return fail(rawSn, REASON_REMUX_FAILED, e);
            }
            if (!isAcceptableOutput(work, before.size())) {
                return fail(rawSn, REASON_OUTPUT_INVALID, null);
            }
            copyPermissionsQuietly(realTarget, work);

            if (!before.equals(FileIdentity.ofOrNull(realTarget))) {
                return fail(rawSn, REASON_TARGET_CHANGED, null);
            }
            try {
                Files.move(work, realTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                return fail(rawSn, REASON_ATOMIC_MOVE_UNSUPPORTED, null);
            }
            streamMetaCacheEvictor.evict(rawSn);
            log.info("[Deident] faststart relocated rawSn={}", rawSn);
            return done(Outcome.RELOCATED);
        } catch (IOException | RuntimeException e) {
            return fail(rawSn, REASON_REMUX_FAILED, e);
        } finally {
            deleteQuietly(work, rawSn);
            inFlight.remove(realTarget);
        }
    }

    /** 처리 이력 경로가 이 영상의 비식별 영상 디렉터리 바로 아래인지 — 맞으면 그 디렉터리. */
    private Path matchDeidDir(Long rawSn, String rawFilePathNm, Path target) {
        Path parent = target.getParent();
        if (parent == null) {
            return null;
        }
        List<Path> dirs;
        try {
            dirs = artifactRootResolver.readableDeidVideoDirs(rawSn, rawFilePathNm);
        } catch (RuntimeException e) {
            return null;
        }
        for (Path dir : dirs) {
            Path normalized = dir.toAbsolutePath().normalize();
            if (normalized.equals(parent)) {
                return normalized;
            }
        }
        return null;
    }

    /**
     * 산출 디렉터리의 형제 작업 디렉터리를 준비한다. 링크가 아닌 실제 디렉터리이고, 실경로의 부모가
     * 산출 디렉터리 실경로의 부모와 같아야 한다(다른 곳으로 새지 않는다).
     */
    private static Path prepareWorkDir(Path deidDir) throws IOException {
        Path parent = deidDir.getParent();
        if (parent == null) {
            return null;
        }
        Path workDir = parent.resolve(WORK_DIR_NAME);
        Files.createDirectories(workDir);
        if (!Files.isDirectory(workDir, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path realWork = workDir.toRealPath();
        Path realDeidParent = deidDir.toRealPath().getParent();
        if (realDeidParent == null || !realDeidParent.equals(realWork.getParent())) {
            return null;
        }
        return realWork;
    }

    /** 변환 결과가 일반 파일 + 유효한 영상 컨테이너 + 인덱스 앞 + 크기 편차 이내인지. */
    private static boolean isAcceptableOutput(Path work, long originalSize) {
        try {
            if (!Files.isRegularFile(work, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (!DeidentArtifactIntegrity.isValidVideoArtifact(work.toString())) {
                return false;
            }
            if (DeidentFaststartInspector.inspect(work) != DeidentFaststartInspector.Layout.FASTSTART) {
                return false;
            }
            long size = Files.size(work);
            long tolerance = Math.max(SIZE_TOLERANCE_MIN_BYTES, (long) (originalSize * SIZE_TOLERANCE_RATIO));
            return Math.abs(size - originalSize) <= tolerance;
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyPermissionsQuietly(Path from, Path to) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(to, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                view.setPermissions(Files.getPosixFilePermissions(from, LinkOption.NOFOLLOW_LINKS));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("[Deident] faststart permission copy skipped errType={}", e.getClass().getSimpleName());
        }
    }

    private static DeidentFaststartRemuxer.Container containerOf(Path file) {
        String ext = extensionOf(file);
        return switch (ext) {
            case "mp4", "m4v" -> DeidentFaststartRemuxer.Container.MP4;
            case "mov" -> DeidentFaststartRemuxer.Container.MOV;
            default -> null;
        };
    }

    private static String extensionOf(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String s = name.toString();
        int dot = s.lastIndexOf('.');
        return dot <= 0 ? "" : s.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Outcome fail(Long rawSn, String reason, Exception e) {
        log.warn("[Deident] faststart relocation failed — original artifact kept rawSn={} reason={} errType={}",
                rawSn, reason, e == null ? "-" : e.getClass().getSimpleName());
        return done(Outcome.FAILED);
    }

    private Outcome done(Outcome outcome) {
        record(outcome);
        return outcome;
    }

    private void record(Outcome outcome) {
        try {
            MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
            if (registry != null) {
                registry.counter(METRIC_NAME, "result", outcome.name().toLowerCase(Locale.ROOT)).increment();
            }
        } catch (RuntimeException e) {
            log.debug("[Deident] faststart metric skipped errType={}", e.getClass().getSimpleName());
        }
    }

    private static void deleteQuietly(Path work, Long rawSn) {
        if (work == null) {
            return;
        }
        try {
            Files.deleteIfExists(work);
        } catch (IOException e) {
            log.warn("[Deident] faststart work file cleanup failed rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /** 교체 직전 동일성 확인용 — 파일 키·크기·수정 시각. */
    private record FileIdentity(Object fileKey, long size, long modifiedMillis) {

        static FileIdentity of(Path file) throws IOException {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) {
                throw new IOException("artifact is not a regular file");
            }
            return new FileIdentity(a.fileKey(), a.size(), a.lastModifiedTime().toMillis());
        }

        static FileIdentity ofOrNull(Path file) {
            try {
                return of(file);
            } catch (IOException e) {
                return null;
            }
        }
    }
}
