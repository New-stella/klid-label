package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.dataset.export.ExportFileNaming;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetLayout;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetVideo;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.LayoutMismatch;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 포털 데이터셋 영상 <b>원장 등록</b>의 본체 — 해제본을 읽고 영상 단위로 원장에 앉힌다(ADR-068).
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>표식을 진행 중으로 쓴다.</li>
 *   <li>해제본 <b>전체</b>를 읽는다({@link PortalDatasetLayoutReader}) — 구성이 어긋나면 원장에 한 행도
 *       쓰기 전에 실패로 끝난다.</li>
 *   <li>모든 영상의 클립 식별자·원천 위치를 <b>먼저</b> 확정한다 — 한 영상이 규칙을 어기면 역시 쓰기 전에
 *       실패로 끝난다.</li>
 *   <li>영상마다: 이미 있으면 건너뛴다(멱등) → 이미지를 트랜잭션 <b>밖</b>에서 작업 중 자리로 복사 → 1
 *       트랜잭션 적재({@link PortalDatasetRegistrationTxService}) → 실패하면 복사본을 지운다.</li>
 *   <li>표식을 완료 또는 실패로 쓴다.</li>
 * </ol>
 *
 * <h3>★ 중복 기동은 데이터셋 단위로 막는다 — 조달 러너와 같은 방식</h3>
 * <p>진행 중 표시는 <b>이 노드의 메모리</b>에 있다. 재기동하면 사라지고, 그 공백은 목록 창구의 회복 규칙
 * (표식 없음 · 진행 중 표식이 오래됨)이 메운다. 노드가 여럿이면 두 노드가 같은 데이터셋을 동시에 등록할
 * 수 있으나 <b>클립 식별자 유일 제약</b>이 행 중복을 막고 진 쪽은 복사본을 지운다.
 *
 * <h3>★ 예외를 밖으로 던지지 않는다</h3>
 * <p>백그라운드에서 돌므로 예외가 호출자에게 닿지 않는다. 사유를 <b>값으로</b> 표식에 남긴다.
 *
 * <h3>진행 중 표식은 영상마다 다시 쓴다</h3>
 * <p>오래됨 판정 기준({@link #STALE_IN_PROGRESS})이 표식의 마지막 쓰기 시각이라, 영상이 많은 데이터셋이
 * 정상 진행 중인데 다른 노드가 「끊겼다」로 보고 다시 시작하지 않게 한다.
 *
 * <p>로그에는 데이터셋 번호 · 영상 키의 해시 · 건수만 남긴다 — 경로·파일명 원문 금지(CWE-209/117).
 *
 * @design ADR-068
 * @design API-253
 * @design AC-1118
 */
@Slf4j
@Service
public class PortalDatasetRegistrationService {

    /**
     * 진행 중 표식이 이보다 오래됐고 진행 중인 작업이 없으면 등록을 다시 시작한다 — 소재 조달 상태를
     * 자동으로 확인하는 예산과 같은 10분(API-253).
     */
    public static final Duration STALE_IN_PROGRESS = Duration.ofMinutes(10);

    /** 이미지 작업 중 자리 접두 — 영상 식별자(숫자) 자리와 겹치지 않는다. */
    static final String STAGING_PREFIX = ".portal-dataset-staging-";

    /** 확정된 이미지 묶음 자리 접두 — {@code frames/deid/{영상 식별자}/} 아래에 놓인다. */
    static final String BATCH_PREFIX = "portal-dataset-";

    /** 비식별 프레임 영역 상대 경로 — 서브트리 규약의 세그먼트를 그대로 잇는다. */
    private static final String FRAMES_DEID =
            StorageSubtreePolicy.SEG_FRAMES + "/" + StorageSubtreePolicy.SEG_DEID;

    /** {@code LS_DATA_RAW.RAW_FILE_PATH_NM} 컬럼 폭. */
    static final int RAW_FILE_PATH_MAX = 500;

    private final PortalMaterialsWorkspace workspace;
    private final PortalDatasetLayoutReader layoutReader;
    private final PortalDatasetRegistrationTxService txService;
    private final VideoRepository videoRepository;
    private final String deidentifiedPath;
    private final boolean enabled;
    private final Clock clock;

    /** 이 노드에서 등록 중인 데이터셋. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public PortalDatasetRegistrationService(
            PortalMaterialsWorkspace workspace,
            PortalDatasetLayoutReader layoutReader,
            PortalDatasetRegistrationTxService txService,
            VideoRepository videoRepository,
            @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String deidentifiedPath,
            @Value("${authoring.portal.materials.registration.enabled:true}") boolean enabled) {
        this(workspace, layoutReader, txService, videoRepository, deidentifiedPath, enabled, Clock.systemUTC());
    }

    public PortalDatasetRegistrationService(PortalMaterialsWorkspace workspace,
                                     PortalDatasetLayoutReader layoutReader,
                                     PortalDatasetRegistrationTxService txService,
                                     VideoRepository videoRepository,
                                     String deidentifiedPath,
                                     boolean enabled,
                                     Clock clock) {
        this.workspace = workspace;
        this.layoutReader = layoutReader;
        this.txService = txService;
        this.videoRepository = videoRepository;
        this.deidentifiedPath = deidentifiedPath;
        this.enabled = enabled;
        this.clock = clock;
    }

    /** 등록 토글 — 꺼져 있으면 러너도 목록 창구도 등록을 시작하지 않는다. */
    public boolean isEnabled() {
        return enabled;
    }

    /** 등록을 선점한다. @return 이번 호출이 선점했으면 {@code true} */
    public boolean tryClaim(long datasetId) {
        return inFlight.add(datasetId);
    }

    /** 선점을 놓는다 — 어떤 경로로 끝나도 반드시 부른다. */
    public void release(long datasetId) {
        inFlight.remove(datasetId);
    }

    /** 이 노드에서 등록이 진행 중인가. */
    public boolean inProgress(long datasetId) {
        return inFlight.contains(datasetId);
    }

    /**
     * 소재 공개 직후 <b>같은 백그라운드 작업</b>이 이어서 등록한다 — 선점·해제를 여기서 한다.
     *
     * <p>이미 다른 경로(목록 창구의 회복)가 진행 중이면 새로 시작하지 않는다.
     */
    public void registerAfterProvision(long datasetId) {
        if (!enabled) {
            log.info("[PortalDataset] 등록 토글이 꺼져 있어 등록을 시작하지 않습니다 datasetId={}", datasetId);
            return;
        }
        if (!tryClaim(datasetId)) {
            return;
        }
        try {
            registerClaimed(datasetId);
        } finally {
            release(datasetId);
        }
    }

    /**
     * 등록 본체 — 선점은 <b>호출자가 이미 마쳤다</b>. 예외를 올리지 않고 최종 표식을 돌려준다.
     */
    public PortalDatasetRegistrationStatus registerClaimed(long datasetId) {
        int registered = 0;
        int skipped = 0;
        try {
            writeStatus(datasetId, PortalDatasetRegistrationStatus.inProgress(0, 0, clock.instant()));

            DatasetLayout layout = layoutReader.read(
                    workspace.readyDir(datasetId).resolve(PortalMaterialsUnpacker.CONTENT_DIR));
            skipped = layout.skippedVideos();
            List<Planned> plans = plan(datasetId, layout);

            Path deidBase = Paths.get(deidentifiedPath).toAbsolutePath().normalize();
            for (Planned p : plans) {
                // 새로 등록했든 이미 등록돼 있었든(멱등) 이 데이터셋의 등록 영상으로 센다.
                registerVideo(datasetId, p, deidBase);
                registered++;
                writeStatus(datasetId, PortalDatasetRegistrationStatus.inProgress(registered, skipped,
                        clock.instant()));
            }

            PortalDatasetRegistrationStatus done =
                    PortalDatasetRegistrationStatus.done(registered, skipped, clock.instant());
            writeStatus(datasetId, done);
            log.info("[PortalDataset] 등록 완료 datasetId={} videos={} skipped={}", datasetId, registered, skipped);
            return done;
        } catch (LayoutMismatch e) {
            return fail(datasetId, e.reason(), registered, skipped);
        } catch (IOException | UncheckedIOException e) {
            return fail(datasetId, PortalDatasetRegistrationFailureReason.IO_ERROR, registered, skipped);
        } catch (RuntimeException e) {
            return fail(datasetId, PortalDatasetRegistrationFailureReason.PERSIST_FAILED, registered, skipped);
        }
    }

    /** 영상마다 클립 식별자·원천 위치를 <b>쓰기 전에</b> 확정한다. */
    private static List<Planned> plan(long datasetId, DatasetLayout layout) {
        List<Planned> plans = new ArrayList<>(layout.videos().size());
        for (DatasetVideo v : layout.videos()) {
            String clipId;
            try {
                clipId = LsDataRaw.portalDatasetClipId(datasetId, v.videoKey());
            } catch (IllegalArgumentException e) {
                throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VIDEO_KEY);
            }
            String rawFilePath = v.videoDir().toString();
            if (rawFilePath.length() > RAW_FILE_PATH_MAX) {
                throw new LayoutMismatch(PortalDatasetRegistrationFailureReason.INVALID_VALUE);
            }
            plans.add(new Planned(v, clipId, rawFilePath));
        }
        return plans;
    }

    /**
     * 영상 한 건을 등록한다.
     *
     * @return 새로 등록했으면 {@code true}, 이미 등록돼 있었으면 {@code false}
     */
    private boolean registerVideo(long datasetId, Planned p, Path deidBase) throws IOException {
        if (videoRepository.findByVmsClipId(p.clipId()).isPresent()) {
            return false; // 멱등 — 같은 데이터셋을 다시 등록해도 행이 늘지 않는다.
        }
        Path staging = copyToStaging(deidBase, p.video());
        AtomicReference<Path> movedTo = new AtomicReference<>();
        boolean committed = false;
        try {
            PortalDatasetRegistrationTxService.Persisted persisted = txService.persist(
                    datasetId, p.video(), p.clipId(), p.rawFilePath(), staging, deidBase, movedTo);
            committed = true;
            log.info("[PortalDataset] 영상 등록 datasetId={} videoKeyHash={} rawSn={} frames={} labels={}",
                    datasetId, hash(p.video().videoKey()), persisted.rawSn(),
                    persisted.frameCount(), persisted.labelCount());
            return true;
        } catch (DataIntegrityViolationException e) {
            // 다른 노드·다른 회차가 먼저 등록했다 — 실패가 아니다.
            log.info("[PortalDataset] 이미 등록된 영상이라 건너뜁니다 datasetId={} videoKeyHash={}",
                    datasetId, hash(p.video().videoKey()));
            return false;
        } finally {
            if (!committed) {
                // ★ 적재가 끝나지 않았으면 복사본을 남기지 않는다(고아 파일 방지). 이름을 바꾼 뒤 커밋이
                //   깨졌으면 옮긴 자리를 지운다 — 그 영상 식별자는 확정되지 않았으므로 다른 행이 쓰지 않는다.
                deleteQuietly(deidBase, staging);
                deleteQuietly(deidBase, movedTo.get());
            }
        }
    }

    /**
     * 비식별 이미지를 비식별 프레임 영역의 작업 중 자리로 복사한다 — <b>트랜잭션 밖</b>.
     *
     * <p>원본은 링크를 따라가지 않고 열고({@code NOFOLLOW_LINKS}), 대상은 새로 만든다({@code CREATE_NEW}).
     * 작업 중 자리가 비식별 저장소 base <b>실경로</b> 하위인지 확인한다(CWE-22/59).
     */
    private Path copyToStaging(Path deidBase, DatasetVideo video) throws IOException {
        Path framesDeid = deidBase.resolve(FRAMES_DEID);
        Files.createDirectories(framesDeid);
        Path staging = framesDeid.resolve(STAGING_PREFIX + UUID.randomUUID());
        Files.createDirectory(staging);
        try {
            requireUnderBase(deidBase, staging);
            for (PortalDatasetLayoutReader.DatasetFrame f : video.frames()) {
                Path target = staging.resolve(ExportFileNaming.imageFileName(f.frameNo()));
                try (InputStream in = Files.newInputStream(f.image(), LinkOption.NOFOLLOW_LINKS);
                     OutputStream out = Files.newOutputStream(target,
                             StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    in.transferTo(out);
                }
            }
            return staging;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(deidBase, staging);
            throw e;
        }
    }

    /** 작업 중 자리 이름에서 확정 자리 이름을 만든다 — 같은 난수를 잇는다(정리 판정이 두 이름을 알아본다). */
    static String finalBatchName(Path stagingDir) {
        String name = stagingDir.getFileName().toString();
        if (!name.startsWith(STAGING_PREFIX)) {
            throw new IllegalArgumentException("작업 중 자리가 아닙니다.");
        }
        return BATCH_PREFIX + name.substring(STAGING_PREFIX.length());
    }

    private static void requireUnderBase(Path deidBase, Path dir) throws IOException {
        Path realBase = deidBase.toRealPath();
        Path realDir = dir.toRealPath();
        if (!realDir.startsWith(realBase.resolve(FRAMES_DEID))) {
            throw new IOException("비식별 프레임 영역 밖입니다.");
        }
    }

    /**
     * 우리가 만든 자리만 지운다 — ①비식별 프레임 영역 <b>바로 아래</b>의 작업 중 자리, ②영상 식별자(숫자)
     * 폴더 <b>바로 아래</b>의 확정 묶음 자리. 그 밖은 지우지 않는다. 실패해도 예외를 올리지 않는다(삭제는 열기보다
     * 위험하다 — 판정이 어긋나면 남긴다). ②를 지운 뒤 영상 식별자 폴더가 <b>비었을 때만</b> 그 폴더도 지운다.
     */
    private static void deleteQuietly(Path deidBase, Path dir) {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Path area = deidBase.toRealPath().resolve(FRAMES_DEID);
            Path real = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
            String name = real.getFileName() == null ? "" : real.getFileName().toString();
            Path parent = real.getParent();
            boolean staging = area.equals(parent) && name.startsWith(STAGING_PREFIX);
            boolean batch = parent != null && area.equals(parent.getParent())
                    && parent.getFileName() != null
                    && parent.getFileName().toString().chars().allMatch(Character::isDigit)
                    && name.startsWith(BATCH_PREFIX);
            boolean own = staging || batch;
            if (!own || Files.isSymbolicLink(dir)) {
                log.warn("[PortalDataset] 우리가 만든 자리가 아니어서 정리를 건너뜁니다.");
                return;
            }
            try (Stream<Path> walk = Files.walk(real)) {
                walk.sorted(Comparator.reverseOrder()).forEach(q -> {
                    try {
                        Files.deleteIfExists(q);
                    } catch (IOException ignored) {
                        // 개별 실패는 삼킨다 — 고아 파일 존치가 등록 결론을 바꾸는 것보다 싸다.
                    }
                });
            }
            if (batch) {
                try {
                    Files.deleteIfExists(parent); // 비어 있을 때만 지워진다 — 비어 있지 않으면 남긴다.
                } catch (IOException ignored) {
                    // 다른 것이 들어 있다 — 우리 것이 아니므로 남긴다.
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[PortalDataset] 복사본 정리에 실패했습니다(고아 파일이 남습니다).");
        }
    }

    private PortalDatasetRegistrationStatus fail(long datasetId, PortalDatasetRegistrationFailureReason reason,
                                                 int registered, int skipped) {
        PortalDatasetRegistrationStatus failed =
                PortalDatasetRegistrationStatus.failed(reason, registered, skipped, clock.instant());
        try {
            writeStatus(datasetId, failed);
        } catch (IOException | RuntimeException e) {
            log.warn("[PortalDataset] 실패 표식을 쓰지 못했습니다 datasetId={}", datasetId);
        }
        log.warn("[PortalDataset] 등록 실패 datasetId={} reason={} videos={}", datasetId, reason, registered);
        return failed;
    }

    private void writeStatus(long datasetId, PortalDatasetRegistrationStatus status) throws IOException {
        workspace.writeRegistration(datasetId, status);
    }

    /** 로그용 영상 키 해시 — 원문을 남기지 않는다. */
    static String hash(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    /** 등록 계획 한 건. */
    private record Planned(DatasetVideo video, String clipId, String rawFilePath) {
    }

}
