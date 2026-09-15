package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 조달 해제본이 놓이는 <b>우리 작업영역</b>의 배치와 공개(publish)를 소유한다.
 *
 * <h3>★ 포털 소재영역은 읽기 전용이다 — 여기가 「푸는 자리」다</h3>
 * <p>조달처의 배포 압축본은 <b>포털이 소유·관리하는 원본</b>이라 이동·수정·삭제·덮어쓰기를 하지
 * 않는다. 우리 자리로 복사해 풀고 <b>그 사본으로만</b> 작업한다 — 원본을 건드리면 포털의 배포본과
 * 무결성 값이 깨진다(INT-014 「우리 쪽 규칙 4」). 이 클래스는 <b>읽는 자리를 아예 알지 못한다</b>.
 *
 * <h3>배치</h3>
 * <pre>
 *   {포털 저장소 루트}/materials/{데이터셋 식별자}/current/   ← 공개된 해제본(준비 완료)
 *   {포털 저장소 루트}/materials/.staging-{식별자}-{난수}/     ← 작업 중(공개 전)
 * </pre>
 * <p>세그먼트 이름은 같은 루트를 쓰는 형제들({@code frames} · {@code images} · {@code tus-video})과
 * 나란히 둔다. ⚠ 이 루트는 비식별 산출물 서브트리 규약({@code frames/raw} 대 {@code frames/deid})을
 * 쓰는 저장소 base 와 <b>다른 루트</b>라 그 축과 충돌하지 않는다.
 *
 * <h3>★ 원자성 — 반쯤 풀린 디렉터리가 「완료」로 보이지 않는다</h3>
 * <p>작업은 <b>staging</b> 에서 하고 끝났을 때만 <b>단일 rename</b> 으로 공개한다. 중간에 죽으면
 * staging 이 남을 뿐 {@code current} 는 생기지 않으므로, 준비 완료 판정({@link #isReady(long)})이
 * 거짓말을 하지 않는다.
 *
 * <h3>★ 이미 공개돼 있으면 덮어쓰지 않는다</h3>
 * <p>다른 노드가 먼저 끝냈을 수 있다. 그때는 우리 staging 을 버린다 — 사용자가 그 해제본을 물고
 * 저작하는 중일 수 있어 <b>덮어쓰기가 그 작업을 깨뜨린다</b>.
 *
 * <p>경로 원문은 로그에 남기지 않는다(CWE-209). 데이터셋 식별자는 {@code long} 이라 경로 세그먼트에
 * 주입될 여지가 없다(CWE-22).
 *
 * @design INT-014
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalMaterialsWorkspace {

    /** 조달 전용 서브트리 세그먼트. */
    static final String MATERIALS_SUBDIR = "materials";

    /** 공개된 해제본 디렉터리 이름 — 이 디렉터리의 존재가 곧 「준비 완료」다. */
    static final String READY_DIR = "current";

    /** 작업 중 디렉터리 접두 — 점으로 시작해 공개본 목록과 섞이지 않는다. */
    static final String STAGING_PREFIX = ".staging-";

    /** 해제본 요약 파일 이름 — 해제본과 <b>함께 공개</b>돼 재기동 뒤에도 남는다. */
    static final String SUMMARY_FILE = ".summary.json";

    private final PortalStoragePathGuard pathGuard;
    private final ObjectMapper objectMapper;

    /** 조달 전용 서브트리 루트. */
    public Path materialsRoot() {
        return pathGuard.baseDir().resolve(MATERIALS_SUBDIR);
    }

    /** 데이터셋 1건의 자리. */
    public Path datasetDir(long datasetId) {
        return materialsRoot().resolve(Long.toString(datasetId));
    }

    /** 공개된 해제본 자리 — 존재하면 준비 완료다. */
    public Path readyDir(long datasetId) {
        return datasetDir(datasetId).resolve(READY_DIR);
    }

    /** 준비 완료 여부 — 공개 디렉터리의 존재로만 판정한다(원장 표를 두지 않는다). */
    public boolean isReady(long datasetId) {
        return Files.isDirectory(readyDir(datasetId));
    }

    /**
     * 작업 중 디렉터리를 새로 만든다 — 공개 자리와 <b>같은 파일 시스템</b>이라 rename 이 원자적이다.
     */
    public Path createStaging(long datasetId) throws IOException {
        Path root = materialsRoot();
        Files.createDirectories(root);
        Path staging = root.resolve(STAGING_PREFIX + datasetId + "-" + UUID.randomUUID());
        Files.createDirectory(staging);
        return staging;
    }

    /**
     * 작업 중 디렉터리를 공개한다.
     *
     * @return 공개했으면 {@code true}. <b>이미 공개돼 있어 덮어쓰지 않았으면 {@code false}</b>
     *         (호출자는 staging 을 버린다 — 실패가 아니다)
     */
    public boolean publish(long datasetId, Path staging) throws IOException {
        Path ready = readyDir(datasetId);
        if (Files.exists(ready, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Files.createDirectories(ready.getParent());
        try {
            Files.move(staging, ready, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
            // 위 존재 확인과 rename 사이에 다른 노드가 먼저 공개했다 — 정상 경로다.
            return false;
        }
    }

    /**
     * 요약을 <b>공개 전 작업 중 자리에</b> 쓴다 — 해제본과 함께 한 번의 rename 으로 공개된다.
     *
     * <p>공개 뒤에 따로 쓰면 「공개는 됐는데 요약은 아직」인 순간이 생기고, 그 사이에 죽으면 요약
     * 없는 해제본이 영구히 남는다.
     */
    public void writeSummary(Path staging, PortalMaterialsSummary summary) throws IOException {
        objectMapper.writeValue(staging.resolve(SUMMARY_FILE).toFile(), summary);
    }

    /**
     * 공개된 해제본의 요약을 읽는다 — 없거나 읽을 수 없으면 {@code null}.
     *
     * <p>요약을 읽지 못한 것이 <b>준비 완료 판정을 뒤집지 않는다</b>. 판정의 진실원은 공개 디렉터리의
     * 존재이고 요약은 부가 정보다 — 여기서 예외를 던지면 부가 정보의 손상이 기능을 멈춘다.
     */
    public PortalMaterialsSummary readSummary(long datasetId) {
        Path file = readyDir(datasetId).resolve(SUMMARY_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), PortalMaterialsSummary.class);
        } catch (IOException | RuntimeException e) {
            log.warn("[PortalMaterials] 해제본 요약을 읽지 못했습니다 — 준비 완료 판정은 그대로입니다.");
            return null;
        }
    }

    /**
     * 작업 중 디렉터리를 버린다 — <b>실패해도 예외를 올리지 않는다</b>.
     *
     * <p>정리 실패로 조달의 결론이 바뀌면 안 된다. 고아 디렉터리가 남는 편이 훨씬 싸다.
     *
     * <p>⚠ 반드시 {@link #createStaging} 이 만든 자리에만 쓴다 — 삭제는 열기보다 위험하다.
     * 그래서 <b>조달 전용 서브트리 하위이고 작업 중 접두를 가진 자리</b>가 아니면 지우지 않는다.
     */
    public void discardQuietly(Path staging) {
        if (staging == null) {
            return;
        }
        Path normalized = staging.toAbsolutePath().normalize();
        Path root = materialsRoot().toAbsolutePath().normalize();
        boolean own = normalized.startsWith(root)
                && normalized.getFileName() != null
                && normalized.getFileName().toString().startsWith(STAGING_PREFIX);
        if (!own) {
            log.warn("[PortalMaterials] 작업 중 자리가 아닌 경로의 정리를 거부했습니다.");
            return;
        }
        try (Stream<Path> walk = Files.walk(normalized)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 개별 실패는 삼킨다 — 고아 파일 존치가 조달 실패보다 싸다.
                }
            });
        } catch (IOException e) {
            log.warn("[PortalMaterials] 작업 중 자리 정리에 실패했습니다(고아 디렉터리가 남습니다).");
        }
    }
}
