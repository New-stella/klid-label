package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 비식별 제외 처리(원본 → 비식별 영상 자리 일반 파일 복사) 단위 테스트.
 *
 * @design ADR-066
 * @design DFEAT-041
 */
class DeidentExclusionServiceTest {

    private static final long RAW_SN = 7301L;

    @TempDir
    Path tmp;

    private Path allowedRoot;
    private VideoArtifactRootResolver resolver;
    private DeidentExclusionTxService txService;
    private DeidentExclusionService service;

    @BeforeEach
    void setUp() throws IOException {
        allowedRoot = tmp.toRealPath().resolve("mount");
        Files.createDirectories(allowedRoot);
        resolver = ArtifactRootTestSupport.coLocate(allowedRoot);
        txService = mock(DeidentExclusionTxService.class);
        service = new DeidentExclusionService(resolver, txService);
    }

    private Path seedSource(String name, String content) throws IOException {
        Path src = allowedRoot.resolve("videos").resolve(name);
        Files.createDirectories(src.getParent());
        Files.writeString(src, content);
        return src;
    }

    private static LsDataRaw generatedRaw(String rawFilePathNm) {
        LsDataRaw raw = LsDataRaw.createFromIngest("clip-excl", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFilePathNm, null, 60, LsDataRaw.SRC_TYPE_GENERATED);
        setField(raw, "rawSn", RAW_SN);
        return raw;
    }

    private static boolean hasTempLeftover(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().startsWith(DeidentExclusionService.TEMP_PREFIX));
        }
    }

    @Test
    @DisplayName("원본을_비식별_영상_자리에_원본_파일명_그대로_바이트_동일한_일반_파일로_복사하고_성공을_기록한다")
    void 복사_성공() throws IOException {
        Path src = seedSource("gen-001.mp4", "generated-video-bytes");
        LsDataRaw raw = generatedRaw(src.toString());

        String copied = service.complete(raw);

        Path expectedDir = resolver.deidVideoDir(RAW_SN, src.toString());
        Path copiedPath = Path.of(copied);
        assertThat(copiedPath.getParent()).isEqualTo(expectedDir);
        assertThat(copiedPath.getFileName().toString()).isEqualTo("gen-001.mp4");
        assertThat(Files.isRegularFile(copiedPath, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(copiedPath)).as("링크가 아니라 일반 파일이어야 한다").isFalse();
        assertThat(Files.isSameFile(copiedPath, src)).as("하드링크·동일 파일이면 안 된다").isFalse();
        assertThat(Files.readAllBytes(copiedPath)).isEqualTo(Files.readAllBytes(src));
        assertThat(hasTempLeftover(expectedDir)).isFalse();
        verify(txService).recordSuccess(RAW_SN, src.toString(), copied);
        verify(txService, never()).recordFailure(anyLong(), anyString(), anyString());
        // 원본 불변
        assertThat(Files.readString(src)).isEqualTo("generated-video-bytes");
    }

    @Test
    @DisplayName("이미_같은_이름의_복사본이_있으면_원자적으로_교체한다")
    void 기존_복사본_교체() throws IOException {
        Path src = seedSource("gen-002.mp4", "new-bytes");
        Path dir = resolver.deidVideoDir(RAW_SN, src.toString());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("gen-002.mp4"), "stale-bytes");

        String copied = service.complete(generatedRaw(src.toString()));

        assertThat(Files.readString(Path.of(copied))).isEqualTo("new-bytes");
        assertThat(hasTempLeftover(dir)).isFalse();
    }

    @Test
    @DisplayName("원본이_없으면_실패를_EXCLUDED_로_기록하고_예외를_전파하며_아무_파일도_만들지_않는다")
    void 원본_부재() throws IOException {
        Path missing = allowedRoot.resolve("videos").resolve("absent.mp4");
        LsDataRaw raw = generatedRaw(missing.toString());

        assertThatThrownBy(() -> service.complete(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(txService).recordFailure(eq(RAW_SN), eq(DeidentExclusionService.SOURCE_MISSING_CODE), anyString());
        verify(txService, never()).recordSuccess(any(), any(), any());
        assertThat(Files.exists(allowedRoot.resolve("videos").resolve(String.valueOf(RAW_SN)))).isFalse();
    }

    @Test
    @DisplayName("원본이_심볼릭_링크면_외부위탁과_같은_규칙으로_따라가_복사한다_복사본은_링크가_아닌_일반파일이고_원본_링크는_그대로다")
    void 원본_심링크는_따라가_복사() throws IOException {
        Path real = seedSource("real.mp4", "real-bytes");
        Path link = allowedRoot.resolve("videos").resolve("link.mp4");
        Files.createSymbolicLink(link, real);

        String copied = service.complete(generatedRaw(link.toString()));

        Path copiedPath = Path.of(copied);
        assertThat(copiedPath.getFileName().toString()).isEqualTo("link.mp4");
        assertThat(Files.isRegularFile(copiedPath, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(copiedPath)).isFalse();
        assertThat(Files.isSameFile(copiedPath, real)).as("하드링크·동일 파일이면 안 된다").isFalse();
        assertThat(Files.readAllBytes(copiedPath)).isEqualTo(Files.readAllBytes(real));
        verify(txService).recordSuccess(RAW_SN, link.toString(), copied);
        verify(txService, never()).recordFailure(anyLong(), anyString(), anyString());
        // 원본 링크와 링크 대상은 그대로다
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(real);
        assertThat(Files.readString(real)).isEqualTo("real-bytes");
    }

    @Test
    @DisplayName("원본이_허용_마운트_밖이면_경로_위반으로_복사하지_않고_실패를_기록한다_원본_불변")
    void 경로_위반() throws IOException {
        Path outside = tmp.toRealPath().resolve("outside").resolve("gen-003.mp4");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "outside-bytes");

        assertThatThrownBy(() -> service.complete(generatedRaw(outside.toString())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        verify(txService).recordFailure(eq(RAW_SN), eq(DeidentExclusionService.COPY_FAILED_CODE), anyString());
        verify(txService, never()).recordSuccess(any(), any(), any());
        assertThat(Files.exists(outside.getParent().resolve(String.valueOf(RAW_SN)))).isFalse();
        assertThat(Files.readString(outside)).isEqualTo("outside-bytes");
    }

    @Test
    @DisplayName("복사_뒤_성공_기록이_실패하면_실패_기록을_시도하고_원래_예외를_전파하며_원본은_그대로다")
    void 성공기록_실패시_실패기록_시도() throws IOException {
        Path src = seedSource("gen-004.mp4", "gen-bytes");
        IllegalStateException original = new IllegalStateException("record failed");
        org.mockito.Mockito.doThrow(original).when(txService).recordSuccess(anyLong(), anyString(), anyString());
        // 실패 기록마저 실패해도 원래 예외가 전파돼야 한다.
        org.mockito.Mockito.doThrow(new IllegalStateException("fail record failed"))
                .when(txService).recordFailure(anyLong(), anyString(), anyString());

        assertThatThrownBy(() -> service.complete(generatedRaw(src.toString()))).isSameAs(original);

        verify(txService).recordFailure(eq(RAW_SN), eq(DeidentExclusionService.RECORD_FAILED_CODE), anyString());
        assertThat(Files.readString(src)).isEqualTo("gen-bytes");
    }

    @Test
    @DisplayName("복사_중_입출력_오류가_나면_임시파일을_지우고_실패를_기록한다_원본은_그대로다")
    void 입출력_오류시_임시파일_정리() throws IOException {
        Path src = seedSource("gen-005.mp4", "gen-bytes");
        Path dir = resolver.deidVideoDir(RAW_SN, src.toString());
        // 대상 이름 자리에 비어 있지 않은 디렉터리를 두어 원자 이동이 입출력 오류로 실패하게 한다.
        Path blocker = dir.resolve("gen-005.mp4");
        Files.createDirectories(blocker);
        Files.writeString(blocker.resolve("keep.txt"), "x");

        assertThatThrownBy(() -> service.complete(generatedRaw(src.toString())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        assertThat(hasTempLeftover(dir)).as("반쯤 쓴 임시 파일은 정리돼야 한다").isFalse();
        verify(txService).recordFailure(eq(RAW_SN), eq(DeidentExclusionService.COPY_FAILED_CODE), anyString());
        verify(txService, never()).recordSuccess(any(), any(), any());
        assertThat(Files.readString(src)).isEqualTo("gen-bytes");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
