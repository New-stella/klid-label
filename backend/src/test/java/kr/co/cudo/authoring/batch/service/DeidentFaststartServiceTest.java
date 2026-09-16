package kr.co.cudo.authoring.batch.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.co.cudo.authoring.batch.service.DeidentFaststartInspector.Layout;
import kr.co.cudo.authoring.batch.service.DeidentFaststartService.Outcome;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

import static kr.co.cudo.authoring.batch.service.DeidentFaststartInspectorTest.box;
import static kr.co.cudo.authoring.batch.service.DeidentFaststartInspectorTest.concat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재생 인덱스 재배치 서비스 — 판정·교체·fail-open·작업 파일 위치·커밋 이후 실행을 실행기 목으로 고정한다.
 *
 * @design ADR-072
 * @design AC-1063
 * @design AC-1064
 */
class DeidentFaststartServiceTest {

    private static final long RAW_SN = 9001L;

    @TempDir
    Path tmp;

    private Path rawFile;
    private Path deidDir;
    private Path artifact;
    private VideoArtifactRootResolver resolver;
    private StreamMetaCacheEvictor evictor;
    private SimpleMeterRegistry registry;
    private ObjectProvider<MeterRegistry> registryProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        rawFile = Files.writeString(Files.createDirectories(tmp.resolve("nas")).resolve("clip.mp4"), "raw");
        deidDir = Files.createDirectories(tmp.resolve("nas").resolve(String.valueOf(RAW_SN)).resolve("deid"));
        artifact = deidDir.resolve("clip-mask.mp4");
        resolver = mock(VideoArtifactRootResolver.class);
        when(resolver.readableDeidVideoDirs(RAW_SN, rawFile.toString())).thenReturn(List.of(deidDir));
        evictor = mock(StreamMetaCacheEvictor.class);
        registry = new SimpleMeterRegistry();
        registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
    }

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 인덱스가 끝에 있는 합성 mp4(무결성 하한 512B 이상). */
    private static byte[] moovAtEnd() {
        return concat(box("ftyp", 12), box("free", 0), box("mdat", 900), box("moov", 60));
    }

    /** 인덱스를 앞으로 옮긴 합성 mp4 — 같은 크기. */
    private static byte[] faststart() {
        return concat(box("ftyp", 12), box("moov", 60), box("free", 0), box("mdat", 900));
    }

    private DeidentFaststartService service(DeidentFaststartRemuxer remuxer) {
        return new DeidentFaststartService(resolver, remuxer, evictor, registryProvider, true, Runnable::run);
    }

    private Path workDir() {
        return deidDir.getParent().resolve(DeidentFaststartService.WORK_DIR_NAME);
    }

    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }

    private double counter(String result) {
        var c = registry.find(DeidentFaststartService.METRIC_NAME).tag("result", result).counter();
        return c == null ? 0d : c.count();
    }

    @Test
    @DisplayName("인덱스가_끝이면_같은_경로_같은_파일명으로_앞으로_옮긴_파일로_교체한다")
    void relocatesMoovAtEndInPlace() throws Exception {
        Files.write(artifact, moovAtEnd());
        List<Path> seenOutputs = new ArrayList<>();
        DeidentFaststartRemuxer remuxer = (source, output, container) -> {
            // 재배치 진행 중에도 산출 디렉터리에는 산출물 하나뿐이다 — 결과 회수가 모호해지지 않는다.
            assertThat(list(deidDir)).containsExactly(artifact);
            assertThat(output.getParent().getFileName().toString())
                    .isEqualTo(DeidentFaststartService.WORK_DIR_NAME);
            assertThat(output).doesNotExist();
            assertThat(container).isEqualTo(DeidentFaststartRemuxer.Container.MP4);
            seenOutputs.add(output);
            Files.write(output, faststart());
        };

        Outcome outcome = service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString());

        assertThat(outcome).isEqualTo(Outcome.RELOCATED);
        assertThat(seenOutputs).hasSize(1);
        assertThat(list(deidDir)).containsExactly(artifact);
        assertThat(Files.readAllBytes(artifact)).isEqualTo(faststart());
        assertThat(DeidentFaststartInspector.inspect(artifact)).isEqualTo(Layout.FASTSTART);
        assertThat(list(workDir())).isEmpty();
        verify(evictor).evict(RAW_SN);
        assertThat(counter("relocated")).isEqualTo(1d);
    }

    @Test
    @DisplayName("인덱스가_이미_앞이면_실행기를_부르지_않고_파일을_건드리지_않는다")
    void alreadyFaststartIsNoop() throws Exception {
        Files.write(artifact, faststart());
        DeidentFaststartRemuxer remuxer = mock(DeidentFaststartRemuxer.class);

        Outcome outcome = service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString());

        assertThat(outcome).isEqualTo(Outcome.ALREADY_FASTSTART);
        verify(remuxer, never()).remux(any(), any(), any());
        assertThat(Files.readAllBytes(artifact)).isEqualTo(faststart());
        assertThat(workDir()).doesNotExist();
        verify(evictor, never()).evict(anyLong());
    }

    @Test
    @DisplayName("인덱스를_판정할_수_없는_파일은_실행기를_부르지_않고_무변경이다")
    void undeterminedIsNoop() throws Exception {
        byte[] noMoov = concat(box("ftyp", 12), box("mdat", 900));
        Files.write(artifact, noMoov);
        DeidentFaststartRemuxer remuxer = mock(DeidentFaststartRemuxer.class);

        Outcome outcome = service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString());

        assertThat(outcome).isEqualTo(Outcome.UNDETERMINED);
        verify(remuxer, never()).remux(any(), any(), any());
        assertThat(Files.readAllBytes(artifact)).isEqualTo(noMoov);
    }

    @Test
    @DisplayName("지원하지_않는_확장자는_판정하지_않는다")
    void unsupportedExtensionIsUndetermined() throws Exception {
        Path mkv = deidDir.resolve("clip-mask.mkv");
        Files.write(mkv, moovAtEnd());
        DeidentFaststartRemuxer remuxer = mock(DeidentFaststartRemuxer.class);

        Outcome outcome = service(remuxer).relocate(RAW_SN, rawFile.toString(), mkv.toString());

        assertThat(outcome).isEqualTo(Outcome.UNDETERMINED);
        verify(remuxer, never()).remux(any(), any(), any());
    }

    @Test
    @DisplayName("실행기가_실패하면_원본은_무훼손이고_작업파일이_남지_않으며_실패가_기록된다")
    void remuxFailureKeepsOriginal() throws Exception {
        Files.write(artifact, moovAtEnd());
        DeidentFaststartRemuxer remuxer = (source, output, container) -> {
            Files.write(output, new byte[]{1, 2, 3}); // 반쯤 쓴 작업 파일
            throw new IOException("boom");
        };

        Outcome outcome = service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString());

        assertThat(outcome).isEqualTo(Outcome.FAILED);
        assertThat(Files.readAllBytes(artifact)).isEqualTo(moovAtEnd());
        assertThat(list(workDir())).isEmpty();
        assertThat(list(deidDir)).containsExactly(artifact);
        verify(evictor, never()).evict(anyLong());
        assertThat(counter("failed")).isEqualTo(1d);
    }

    @Test
    @DisplayName("실행기가_런타임_예외를_던져도_원본은_무훼손이다")
    void remuxRuntimeExceptionKeepsOriginal() throws Exception {
        Files.write(artifact, moovAtEnd());
        DeidentFaststartRemuxer remuxer = (source, output, container) -> {
            throw new IllegalStateException("boom");
        };

        assertThat(service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString()))
                .isEqualTo(Outcome.FAILED);
        assertThat(Files.readAllBytes(artifact)).isEqualTo(moovAtEnd());
        assertThat(list(workDir())).isEmpty();
    }

    @Test
    @DisplayName("변환_결과의_인덱스가_여전히_끝이면_교체하지_않는다")
    void outputStillMoovAtEndIsRejected() throws Exception {
        Files.write(artifact, moovAtEnd());
        DeidentFaststartRemuxer remuxer = (source, output, container) -> Files.write(output, moovAtEnd());

        assertThat(service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString()))
                .isEqualTo(Outcome.FAILED);
        assertThat(Files.readAllBytes(artifact)).isEqualTo(moovAtEnd());
        assertThat(list(workDir())).isEmpty();
    }

    @Test
    @DisplayName("변환_결과가_유효한_영상이_아니거나_크기가_크게_다르면_교체하지_않는다")
    void invalidOrResizedOutputIsRejected() throws Exception {
        Files.write(artifact, moovAtEnd());
        DeidentFaststartRemuxer tooSmall = (source, output, container) ->
                Files.write(output, concat(box("ftyp", 12), box("moov", 20)));
        assertThat(service(tooSmall).relocate(RAW_SN, rawFile.toString(), artifact.toString()))
                .isEqualTo(Outcome.FAILED);

        DeidentFaststartRemuxer tooLarge = (source, output, container) ->
                Files.write(output, concat(box("ftyp", 12), box("moov", 60), box("mdat", 2_000_000)));
        assertThat(service(tooLarge).relocate(RAW_SN, rawFile.toString(), artifact.toString()))
                .isEqualTo(Outcome.FAILED);

        assertThat(Files.readAllBytes(artifact)).isEqualTo(moovAtEnd());
        assertThat(list(workDir())).isEmpty();
    }

    @Test
    @DisplayName("변환_중_산출물이_바뀌면_재위탁의_새_산출물을_덮지_않는다")
    void targetChangedDuringRemuxIsNotOverwritten() throws Exception {
        Files.write(artifact, moovAtEnd());
        byte[] newer = concat(box("ftyp", 12), box("moov", 70), box("mdat", 950));
        DeidentFaststartRemuxer remuxer = (source, output, container) -> {
            Files.write(output, faststart());
            // 재위탁이 디렉터리를 비우고 새 산출물을 쓴 상황.
            Files.delete(artifact);
            Files.write(artifact, newer);
        };

        assertThat(service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString()))
                .isEqualTo(Outcome.FAILED);
        assertThat(Files.readAllBytes(artifact)).isEqualTo(newer);
        assertThat(list(workDir())).isEmpty();
    }

    @Test
    @DisplayName("변환_중_산출물이_지워지면_다시_만들지_않는다")
    void targetDeletedDuringRemuxIsNotRecreated() throws Exception {
        Files.write(artifact, moovAtEnd());
        DeidentFaststartRemuxer remuxer = (source, output, container) -> {
            Files.write(output, faststart());
            Files.delete(artifact);
        };

        assertThat(service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString()))
                .isEqualTo(Outcome.FAILED);
        assertThat(artifact).doesNotExist();
        assertThat(list(workDir())).isEmpty();
    }

    @Test
    @DisplayName("이_영상의_비식별_영상_디렉터리_밖_경로는_건드리지_않는다")
    void pathOutsideDeidDirIsSkipped() throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("other")).resolve("clip-mask.mp4");
        Files.write(elsewhere, moovAtEnd());
        DeidentFaststartRemuxer remuxer = mock(DeidentFaststartRemuxer.class);

        assertThat(service(remuxer).relocate(RAW_SN, rawFile.toString(), elsewhere.toString()))
                .isEqualTo(Outcome.SKIPPED);
        verify(remuxer, never()).remux(any(), any(), any());
        assertThat(Files.readAllBytes(elsewhere)).isEqualTo(moovAtEnd());
    }

    @Test
    @DisplayName("산출물이_심볼릭_링크면_건드리지_않는다")
    void symlinkArtifactIsSkipped() throws Exception {
        Path realFile = Files.write(tmp.resolve("outside.mp4"), moovAtEnd());
        Files.createSymbolicLink(artifact, realFile);
        DeidentFaststartRemuxer remuxer = mock(DeidentFaststartRemuxer.class);

        assertThat(service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString()))
                .isEqualTo(Outcome.SKIPPED);
        verify(remuxer, never()).remux(any(), any(), any());
        assertThat(Files.readAllBytes(realFile)).isEqualTo(moovAtEnd());
    }

    @Test
    @DisplayName("허용_디렉터리_계산이_실패하면_건드리지_않는다")
    void resolverFailureIsSkipped() throws Exception {
        Files.write(artifact, moovAtEnd());
        when(resolver.readableDeidVideoDirs(RAW_SN, rawFile.toString())).thenThrow(new IllegalStateException());
        DeidentFaststartRemuxer remuxer = mock(DeidentFaststartRemuxer.class);

        assertThat(service(remuxer).relocate(RAW_SN, rawFile.toString(), artifact.toString()))
                .isEqualTo(Outcome.SKIPPED);
        verify(remuxer, never()).remux(any(), any(), any());
    }

    @Test
    @DisplayName("설정으로_끄면_아무것도_하지_않는다")
    void disabledIsNoop() throws Exception {
        Files.write(artifact, moovAtEnd());
        DeidentFaststartRemuxer remuxer = mock(DeidentFaststartRemuxer.class);
        List<Runnable> submitted = new ArrayList<>();
        DeidentFaststartService off = new DeidentFaststartService(
                resolver, remuxer, evictor, registryProvider, false, submitted::add);

        off.scheduleAfterCommit(RAW_SN, rawFile.toString(), artifact.toString());

        assertThat(submitted).isEmpty();
        assertThat(off.relocate(RAW_SN, rawFile.toString(), artifact.toString())).isEqualTo(Outcome.SKIPPED);
        verify(remuxer, never()).remux(any(), any(), any());
    }

    @Test
    @DisplayName("활성_트랜잭션에서는_커밋_이후에만_실행기에_넘긴다")
    void schedulesOnlyAfterCommit() {
        List<Runnable> submitted = new ArrayList<>();
        DeidentFaststartService svc = new DeidentFaststartService(
                resolver, mock(DeidentFaststartRemuxer.class), evictor, registryProvider, true, submitted::add);
        TransactionSynchronizationManager.initSynchronization();

        svc.scheduleAfterCommit(RAW_SN, rawFile.toString(), artifact.toString());
        assertThat(submitted).isEmpty();

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        assertThat(submitted).hasSize(1);
    }

    @Test
    @DisplayName("롤백되면_실행기에_넘기지_않는다")
    void rollbackDoesNotSchedule() {
        List<Runnable> submitted = new ArrayList<>();
        DeidentFaststartService svc = new DeidentFaststartService(
                resolver, mock(DeidentFaststartRemuxer.class), evictor, registryProvider, true, submitted::add);
        TransactionSynchronizationManager.initSynchronization();

        svc.scheduleAfterCommit(RAW_SN, rawFile.toString(), artifact.toString());
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(submitted).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션이_없으면_곧바로_실행기에_넘기고_실행기가_재배치를_수행한다")
    void schedulesImmediatelyWithoutTransaction() throws Exception {
        Files.write(artifact, moovAtEnd());
        DeidentFaststartRemuxer remuxer = (source, output, container) -> Files.write(output, faststart());

        service(remuxer).scheduleAfterCommit(RAW_SN, rawFile.toString(), artifact.toString());

        assertThat(DeidentFaststartInspector.inspect(artifact)).isEqualTo(Layout.FASTSTART);
    }

    @Test
    @DisplayName("대기열이_가득_차_거부되어도_예외를_밖으로_내보내지_않는다")
    void rejectionIsFailOpen() {
        DeidentFaststartService svc = new DeidentFaststartService(
                resolver, mock(DeidentFaststartRemuxer.class), evictor, registryProvider, true,
                r -> {
                    throw new RejectedExecutionException("full");
                });

        assertThatCode(() -> svc.scheduleAfterCommit(RAW_SN, rawFile.toString(), artifact.toString()))
                .doesNotThrowAnyException();
        assertThat(counter("failed")).isEqualTo(1d);
    }

    @Test
    @DisplayName("실행기_안의_예상밖_예외도_밖으로_내보내지_않는다")
    void unexpectedExceptionInsideTaskIsSwallowed() throws Exception {
        Files.write(artifact, moovAtEnd());
        when(resolver.readableDeidVideoDirs(RAW_SN, rawFile.toString())).thenReturn(null);

        assertThatCode(() -> service(mock(DeidentFaststartRemuxer.class))
                .scheduleAfterCommit(RAW_SN, rawFile.toString(), artifact.toString()))
                .doesNotThrowAnyException();
        assertThat(Files.readAllBytes(artifact)).isEqualTo(moovAtEnd());
    }
}
