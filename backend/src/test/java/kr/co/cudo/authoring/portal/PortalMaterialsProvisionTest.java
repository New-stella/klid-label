package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.PortalMaterialsClient;
import kr.co.cudo.authoring.common.client.dto.PortalMaterialsResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalMaterialsState;
import kr.co.cudo.authoring.portal.dto.PortalMaterialsStatusResponse;
import kr.co.cudo.authoring.portal.service.PortalMaterialsFailureReason;
import kr.co.cudo.authoring.portal.service.PortalMaterialsPathGuard;
import kr.co.cudo.authoring.portal.service.PortalMaterialsProvisionRunner;
import kr.co.cudo.authoring.portal.service.PortalMaterialsProvisionService;
import kr.co.cudo.authoring.portal.service.PortalMaterialsProvisionState;
import kr.co.cudo.authoring.portal.service.PortalMaterialsUnpacker;
import kr.co.cudo.authoring.portal.service.PortalMaterialsWorkspace;
import kr.co.cudo.authoring.portal.service.PortalStoragePathGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 조달 한 건의 <b>끝에서 끝까지</b>(조회 → 경로 재검증 → 사본 해제 → 공개)와 착수 창구의 멱등을 고정한다.
 *
 * <h3>★ 실패는 「값」으로 남아야 한다</h3>
 * <p>{@code @Async void} 의 예외는 호출자에게 닿지 않으므로, 작업자가 사유를 기록하지 않으면
 * <b>아무도 왜 실패했는지 모른다</b>. 그래서 각 실패 경로마다 기록된 사유를 직접 단언한다.
 *
 * @design INT-014
 */
class PortalMaterialsProvisionTest {

    private static final long DATASET_ID = 4704L;

    private PortalMaterialsClient client;
    private PortalMaterialsWorkspace workspace;
    private PortalMaterialsProvisionState state;
    private PortalMaterialsProvisionRunner runner;
    private Path repoRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        Path storage = Files.createDirectories(tmp.resolve("portal-storage"));
        repoRoot = Files.createDirectories(tmp.resolve("portal-repo"));

        client = mock(PortalMaterialsClient.class);
        PortalStoragePathGuard storageGuard = new PortalStoragePathGuard(properties(storage));
        workspace = new PortalMaterialsWorkspace(storageGuard, new ObjectMapper().findAndRegisterModules());
        state = new PortalMaterialsProvisionState();
        runner = new PortalMaterialsProvisionRunner(
                client,
                new PortalMaterialsPathGuard(),
                new PortalMaterialsUnpacker(1000, 10L * 1024 * 1024),
                workspace,
                state);
    }

    // ── 조달 본체 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("조달에_성공하면_해제본이_공개되고_요약이_함께_남는다")
    void provisionPublishesUnpackedContentWithSummary() throws IOException {
        Path zip = zip(repoRoot.resolve("deploy.zip"), Map.of("meta.json", "{}"));
        when(client.fetch(DATASET_ID)).thenReturn(response(zip, 2));

        runner.runAsync(DATASET_ID);

        assertThat(workspace.isReady(DATASET_ID)).isTrue();
        assertThat(Files.readString(workspace.readyDir(DATASET_ID)
                .resolve(PortalMaterialsUnpacker.CONTENT_DIR).resolve("meta.json"))).isEqualTo("{}");

        var summary = workspace.readSummary(DATASET_ID);
        assertThat(summary).isNotNull();
        assertThat(summary.code()).isEqualTo("DS-1");
        assertThat(summary.version()).isEqualTo("11.0.0");
        assertThat(summary.videoCount()).isEqualTo(2);
        assertThat(summary.entryCount()).isGreaterThanOrEqualTo(1);
        // 진행 중 표시는 반드시 놓아야 한다 — 남기면 다시 조달할 수 없다.
        assertThat(state.inProgress(DATASET_ID)).isFalse();
        assertThat(state.lastFailure(DATASET_ID)).isNull();
    }

    @Test
    @DisplayName("★★조달_뒤에도_포털_원본_압축본은_그대로다_이동도_수정도_삭제도_하지_않는다")
    void portalOriginalArchive_isUntouched() throws IOException {
        Path zip = zip(repoRoot.resolve("deploy.zip"), Map.of("a.txt", "a"));
        FileTime before = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(zip, before);
        byte[] bytesBefore = Files.readAllBytes(zip);
        when(client.fetch(DATASET_ID)).thenReturn(response(zip, 0));

        runner.runAsync(DATASET_ID);

        assertThat(workspace.isReady(DATASET_ID)).isTrue();
        assertThat(Files.exists(zip)).isTrue();
        assertThat(Files.readAllBytes(zip)).isEqualTo(bytesBefore);
        assertThat(Files.getLastModifiedTime(zip)).isEqualTo(before);
        // 소재영역에 우리 흔적(사본·해제본)이 남지도 않았다.
        try (var entries = Files.list(repoRoot)) {
            assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("deploy.zip");
        }
    }

    @Test
    @DisplayName("★저장소_루트_밖을_가리키는_소재는_열지_않고_실패로_마감한다")
    void materialOutsideRepoRoot_isRejected(@TempDir Path other) throws IOException {
        Path outside = zip(other.resolve("evil.zip"), Map.of("a.txt", "a"));
        when(client.fetch(DATASET_ID)).thenReturn(new PortalMaterialsResponse(
                DATASET_ID, "DS-1", "11.0.0", null, repoRoot.toString(),
                List.of(new PortalMaterialsResponse.MaterialFile(
                        1L, PortalMaterialsResponse.FILE_DV_DEPLOYMENT_ZIP, "evil.zip",
                        outside.toString(), 1L, null))));

        runner.runAsync(DATASET_ID);

        assertThat(workspace.isReady(DATASET_ID)).isFalse();
        assertThat(state.lastFailure(DATASET_ID).reason())
                .isEqualTo(PortalMaterialsFailureReason.MATERIAL_PATH_REJECTED);
    }

    @Test
    @DisplayName("배포_압축본이_없으면_사유를_구분해_실패한다")
    void missingDeploymentZip_isDistinctFailure() {
        when(client.fetch(DATASET_ID)).thenReturn(new PortalMaterialsResponse(
                DATASET_ID, null, "v", null, repoRoot.toString(),
                List.of(new PortalMaterialsResponse.MaterialFile(
                        1L, PortalMaterialsResponse.FILE_DV_DATASET_VIDEO, "v.mp4",
                        repoRoot.resolve("v.mp4").toString(), 1L, null))));

        runner.runAsync(DATASET_ID);

        assertThat(state.lastFailure(DATASET_ID).reason())
                .isEqualTo(PortalMaterialsFailureReason.NO_DEPLOYMENT_ZIP);
    }

    @Test
    @DisplayName("★미구성_실패가_포털_거부로_잘못_기록되지_않는다_상속_관계에_순서가_걸려_있다")
    void notConfigured_isNotMisclassifiedAsRejected() {
        when(client.fetch(DATASET_ID)).thenThrow(
                new PortalMaterialsClient.PortalMaterialsUnavailableException("미구성"));

        runner.runAsync(DATASET_ID);

        assertThat(state.lastFailure(DATASET_ID).reason())
                .isEqualTo(PortalMaterialsFailureReason.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("포털이_거부하면_거부로_기록한다")
    void portalRejection_isRecorded() {
        when(client.fetch(DATASET_ID)).thenThrow(
                new NonRetryableExternalException("포털 소재 조회 4xx 응답(status=404)", 404));

        runner.runAsync(DATASET_ID);

        assertThat(state.lastFailure(DATASET_ID).reason())
                .isEqualTo(PortalMaterialsFailureReason.FETCH_REJECTED);
    }

    @Test
    @DisplayName("★해제가_거부되면_반쯤_풀린_자리가_남지_않고_준비완료로도_보이지_않는다")
    void rejectedUnpack_leavesNoStagingAndNoReady() throws IOException {
        Path zip = zip(repoRoot.resolve("evil.zip"), Map.of("../escaped.txt", "pwned"));
        when(client.fetch(DATASET_ID)).thenReturn(response(zip, 0));

        runner.runAsync(DATASET_ID);

        assertThat(state.lastFailure(DATASET_ID).reason())
                .isEqualTo(PortalMaterialsFailureReason.UNPACK_REJECTED);
        assertThat(workspace.isReady(DATASET_ID)).isFalse();
        try (var entries = Files.list(workspace.materialsRoot())) {
            assertThat(entries).isEmpty();
        }
    }

    // ── 착수 창구 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★이미_준비된_대상은_다시_풀지_않는다_사용자가_물고_있는_해제본을_깨뜨리지_않는다")
    void alreadyReady_doesNotRestart() throws IOException {
        Files.createDirectories(workspace.readyDir(DATASET_ID));
        PortalMaterialsProvisionRunner spyRunner = mock(PortalMaterialsProvisionRunner.class);
        PortalMaterialsProvisionService service =
                new PortalMaterialsProvisionService(workspace, state, spyRunner);

        PortalMaterialsStatusResponse response = service.start(DATASET_ID);

        assertThat(response.state()).isEqualTo(PortalMaterialsState.READY);
        verify(spyRunner, never()).runAsync(anyLong());
    }

    @Test
    @DisplayName("★진행_중인_대상은_새로_시작하지_않는다")
    void alreadyInProgress_doesNotRestart() {
        PortalMaterialsProvisionRunner spyRunner = mock(PortalMaterialsProvisionRunner.class);
        PortalMaterialsProvisionService service =
                new PortalMaterialsProvisionService(workspace, state, spyRunner);

        assertThat(service.start(DATASET_ID).state()).isEqualTo(PortalMaterialsState.IN_PROGRESS);
        assertThat(service.start(DATASET_ID).state()).isEqualTo(PortalMaterialsState.IN_PROGRESS);

        verify(spyRunner).runAsync(DATASET_ID); // 정확히 1회
    }

    @Test
    @DisplayName("★맡기지_못하면_선점을_되돌린다_아무도_일하지_않는데_진행중으로_굳지_않는다")
    void rejectedTask_releasesClaim() {
        PortalMaterialsProvisionRunner failing = mock(PortalMaterialsProvisionRunner.class);
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(failing).runAsync(DATASET_ID);
        PortalMaterialsProvisionService service =
                new PortalMaterialsProvisionService(workspace, state, failing);

        assertThatThrownBy(() -> service.start(DATASET_ID)).isInstanceOf(CustomException.class);

        assertThat(state.inProgress(DATASET_ID)).isFalse();
        assertThat(service.status(DATASET_ID).state()).isEqualTo(PortalMaterialsState.NOT_PROVISIONED);
    }

    @Test
    @DisplayName("데이터셋_식별자가_양수가_아니면_거부한다_경로_세그먼트로_내려가지_않는다")
    void nonPositiveId_isRejected() {
        PortalMaterialsProvisionService service = new PortalMaterialsProvisionService(
                workspace, state, mock(PortalMaterialsProvisionRunner.class));

        assertThatThrownBy(() -> service.start(0L)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.start(-1L)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.status(-1L)).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("실패한_뒤에도_다시_착수할_수_있다_실패가_영구_차단이_되지_않는다")
    void failureDoesNotBlockRetry() {
        state.recordFailure(DATASET_ID, PortalMaterialsFailureReason.FETCH_FAILED);
        PortalMaterialsProvisionRunner spyRunner = mock(PortalMaterialsProvisionRunner.class);
        PortalMaterialsProvisionService service =
                new PortalMaterialsProvisionService(workspace, state, spyRunner);

        assertThat(service.status(DATASET_ID).state()).isEqualTo(PortalMaterialsState.FAILED);
        assertThat(service.status(DATASET_ID).failureReason())
                .isEqualTo(PortalMaterialsFailureReason.FETCH_FAILED);

        assertThat(service.start(DATASET_ID).state()).isEqualTo(PortalMaterialsState.IN_PROGRESS);
        verify(spyRunner).runAsync(DATASET_ID);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────────

    private PortalMaterialsResponse response(Path zip, int videoCount) {
        var files = new java.util.ArrayList<PortalMaterialsResponse.MaterialFile>();
        files.add(new PortalMaterialsResponse.MaterialFile(
                1L, PortalMaterialsResponse.FILE_DV_DEPLOYMENT_ZIP, zip.getFileName().toString(),
                zip.toString(), 1L, null));
        for (int i = 0; i < videoCount; i++) {
            files.add(new PortalMaterialsResponse.MaterialFile(
                    100L + i, PortalMaterialsResponse.FILE_DV_DATASET_VIDEO, "v" + i + ".mp4",
                    repoRoot.resolve("v" + i + ".mp4").toString(), 1L, null));
        }
        return new PortalMaterialsResponse(
                DATASET_ID, "DS-1", "11.0.0", null, repoRoot.toString(), files);
    }

    private static PortalUploadProperties properties(Path storage) {
        return new PortalUploadProperties(
                5368709120L, List.of("mp4"), storage.toString(), List.of("jpg"),
                20971520L, 50, 2000, 16777216L, 2097152L, 30L, 30L);
    }

    private static Path zip(Path target, Map<String, String> entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(target);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return target;
    }
}
