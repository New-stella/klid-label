package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.service.port.Java2DImageResizer;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPlan;
import kr.co.cudo.authoring.webhook.service.AugmentFrameProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase B(파일 I/O) 단위 테스트 — Phase 7-D 외부 산출물 반입.
 *
 * <p>부모 영상 재추출이 아니라 <b>외부 생성형 AI 산출 이미지</b>가 파생 프레임으로 반입되는지,
 * 그 전에 허용루트·실재·해상도 검증이 fail-closed 로 동작하는지, 실패 시 부분 산출이 남지 않는지를
 * 실제 파일로 검증한다. 또한 <b>리포지토리 주입 0</b>(DB 커넥션 미보유)을 구조(필드 타입)로 보증한다.
 */
class AugmentFrameProducerTest {

    @TempDir Path tempDir;

    private AugmentFrameProducer producer;
    private Path deidBase;
    private Path externalDir;
    private Path parentFrame;
    /** 부모 비식별 <영상> — 파생영상 비디오의 복사 소스(원본이 아니다). */
    private Path parentDeidVideo;

    @BeforeEach
    void setup() throws IOException {
        deidBase = tempDir.resolve("deid");
        externalDir = tempDir.resolve("genai");
        Files.createDirectories(deidBase);
        Files.createDirectories(externalDir);
        // 허용 마운트 루트 = 테스트 임시 루트(외부 산출 경로·비식별 저장소 모두 그 하위).
        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                tempDir.toString(), "", tempDir.resolve("raw").toString(), deidBase.toString(),
                tempDir.resolve("labeling").toString(), "co-locate");
        producer = new AugmentFrameProducer(resolver, new Java2DImageResizer());
        ReflectionTestUtils.setField(producer, "storageDeidentifiedPath", deidBase.toString());
        // 기준(부모 비식별) 프레임 — 위탁 입력과 동일 해상도.
        parentFrame = writeImage(deidBase.resolve("frames/deid/100/frame-0.jpg"), 64, 48, Color.GRAY);
        // 부모 비식별 영상 — 파일명은 고정이 아니므로(KPST 는 {stem}-mask{ext}) 계획이 준 경로를 그대로 쓴다.
        parentDeidVideo = deidBase.resolve("videos/100/100-mask.mp4");
        Files.createDirectories(parentDeidVideo.getParent());
        Files.writeString(parentDeidVideo, "PARENT-DEID-VIDEO");
    }

    /** 지정 해상도의 실제 이미지 파일을 만든다(ImageIO 판독 가능). */
    private static Path writeImage(Path dst, int w, int h, Color color) throws IOException {
        Files.createDirectories(dst.getParent());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, w, h);
        } finally {
            g.dispose();
        }
        String name = dst.getFileName().toString();
        String fmt = name.endsWith(".png") ? "png" : "jpg";
        ImageIO.write(img, fmt, dst.toFile());
        return dst;
    }

    private Path framesDir(long newRawSn) {
        return deidBase.resolve("frames/deid/" + newRawSn);
    }

    /** 파생 비디오 목적 경로 — 파생 RAW_SN 을 키에 포함(부모/타 파생 파일과 절대 겹치지 않는다). */
    private Path videoDst(long newRawSn) {
        return deidBase.resolve("videos/augment/100/" + newRawSn + "/WINTER.mp4");
    }

    private AugmentExtractPlan plan(long newRawSn, List<AugmentExtractPlan.FrameSpec> frames) {
        return new AugmentExtractPlan(newRawSn, 100L, 20L, "rev1", parentFrame,
                parentDeidVideo, videoDst(newRawSn), framesDir(newRawSn), frames);
    }

    private AugmentExtractPlan.FrameSpec spec(long newRawSn, long frameNo, Path external) {
        return new AugmentExtractPlan.FrameSpec(600L + frameNo, frameNo, 100L * (frameNo + 1),
                LocalDateTime.now(), external, framesDir(newRawSn).resolve("frame-" + frameNo + ".jpg"));
    }

    @Test
    @DisplayName("증강_파생영상의_프레임이_부모_재추출본이_아니라_외부_산출본이다")
    void ingestsExternalOutputsInsteadOfReExtracting() throws IOException {
        // given — 외부 산출물은 부모 프레임과 <다른 픽셀>(파랑)이며 해상도는 동일.
        Path out0 = writeImage(externalDir.resolve("job-1/out-0.jpg"), 64, 48, Color.BLUE);
        Path out1 = writeImage(externalDir.resolve("job-1/out-1.jpg"), 64, 48, Color.BLUE);
        AugmentExtractPlan p = plan(9001L, List.of(spec(9001L, 0, out0), spec(9001L, 1, out1)));

        // when
        producer.produce(p);

        // then — 산출 프레임 바이트가 외부 산출물과 동일하고, 부모 프레임과는 다르다.
        assertThat(Files.readAllBytes(p.frames().get(0).dst())).isEqualTo(Files.readAllBytes(out0));
        assertThat(Files.readAllBytes(p.frames().get(1).dst())).isEqualTo(Files.readAllBytes(out1));
        assertThat(Files.readAllBytes(p.frames().get(0).dst()))
                .as("부모 픽셀 사본이면 증강 효과가 0 이다")
                .isNotEqualTo(Files.readAllBytes(parentFrame));
    }

    @Test
    @DisplayName("원본_및_부모_프레임_파일을_덮어쓰지_않는다")
    void doesNotTouchParentFrames() throws IOException {
        Path out0 = writeImage(externalDir.resolve("job-2/out-0.jpg"), 64, 48, Color.RED);
        byte[] parentBefore = Files.readAllBytes(parentFrame);
        Path rawFrame = writeImage(tempDir.resolve("raw/frames/raw/100/frame-0.jpg"), 64, 48, Color.WHITE);
        byte[] rawBefore = Files.readAllBytes(rawFrame);

        producer.produce(plan(9002L, List.of(spec(9002L, 0, out0))));

        assertThat(Files.readAllBytes(parentFrame)).isEqualTo(parentBefore);
        assertThat(Files.readAllBytes(rawFrame)).isEqualTo(rawBefore);
        assertThat(Files.readAllBytes(out0)).as("외부 산출 원본도 우리 소유가 아니다").isNotEmpty();
    }

    @Test
    @DisplayName("외부_산출_파일이_존재하지_않으면_실패처리된다")
    void missingOutputFile_rejected() {
        Path missing = externalDir.resolve("job-3/no-such.jpg");
        AugmentExtractPlan p = plan(9003L, List.of(spec(9003L, 0, missing)));

        assertThatThrownBy(() -> producer.produce(p))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        assertThat(Files.exists(p.frames().get(0).dst())).isFalse();
    }

    @Test
    @DisplayName("외부_산출_파일이_비어있으면_실패처리된다")
    void emptyOutputFile_rejected() throws IOException {
        Path empty = externalDir.resolve("job-4/empty.jpg");
        Files.createDirectories(empty.getParent());
        Files.createFile(empty);
        AugmentExtractPlan p = plan(9004L, List.of(spec(9004L, 0, empty)));

        assertThatThrownBy(() -> producer.produce(p)).isInstanceOf(CustomException.class);
        assertThat(Files.exists(p.frames().get(0).dst())).isFalse();
    }

    @Test
    @DisplayName("증강본_해상도가_원본과_다르면_실패처리된다")
    void resolutionMismatch_rejected() throws IOException {
        // given — 라벨 좌표를 그대로 복사하는 전제(해상도 동일)가 깨진 산출물.
        Path scaled = writeImage(externalDir.resolve("job-5/scaled.jpg"), 32, 24, Color.BLUE);
        AugmentExtractPlan p = plan(9005L, List.of(spec(9005L, 0, scaled)));

        assertThatThrownBy(() -> producer.produce(p))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("CONFLICT");
        assertThat(Files.exists(p.frames().get(0).dst())).isFalse();
    }

    @Test
    @DisplayName("허용루트_밖_경로는_반영되지_않는다")
    void outsideAllowedRoot_rejected() throws IOException {
        // given — 임시 루트 밖(=허용 마운트 루트 밖)의 실제 이미지.
        Path outside = Files.createTempDirectory("klid-outside").resolve("evil.jpg");
        writeImage(outside, 64, 48, Color.BLUE);
        AugmentExtractPlan p = plan(9006L, List.of(spec(9006L, 0, outside)));

        assertThatThrownBy(() -> producer.produce(p))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        assertThat(Files.exists(p.frames().get(0).dst())).isFalse();
    }

    @Test
    @DisplayName("쓰기루트_밖_읽기루트_안의_벤더_산출물은_반입된다")
    void vendorOutputOutsideWriteRootsButInsideReadRoots_isIngested() throws IOException {
        // given — 실 형상 재현(docker): 벤더/목은 <자기 트리>(STORAGE_EXTERNAL_READ_ROOTS=/app/genai-out)에
        //   결과를 쓰고, 우리 쓰기 allowlist(STORAGE_RAW_MOUNT_ROOTS=/app/storage/*)에는 손대지 않는다.
        //   즉 반입 판정 축은 <읽기>(verifyExternalReadablePath / readableRoots) 여야 한다 — 구 API
        //   (verifyIngestablePath / allowedRoots)로 되돌리면 이 정상 경로가 전건 거부되어 증강이 실패한다.
        //   위 setup() 은 tempDir 전체를 쓰기 루트로 잡아 두 축이 겹치므로 여기서만 축을 분리한다.
        Path storage = tempDir.resolve("storage");        // 쓰기 allowlist(우리 소유 트리)
        Path vendorRoot = tempDir.resolve("vendor-out");  // 읽기 전용 allowlist(벤더 트리)
        Path storageDeid = storage.resolve("deidentified");
        Files.createDirectories(storageDeid);
        Files.createDirectories(vendorRoot);
        VideoArtifactRootResolver splitAxisResolver = new VideoArtifactRootResolver(
                storage.toString(), vendorRoot.toString(), storage.resolve("raw").toString(),
                storageDeid.toString(), storage.resolve("labeling").toString(), "co-locate");
        AugmentFrameProducer splitAxisProducer =
                new AugmentFrameProducer(splitAxisResolver, new Java2DImageResizer());
        ReflectionTestUtils.setField(splitAxisProducer, "storageDeidentifiedPath", storageDeid.toString());
        Path reference = writeImage(storageDeid.resolve("frames/deid/200/frame-0.jpg"), 64, 48, Color.GRAY);
        Path vendorOut = writeImage(vendorRoot.resolve("job-ext/out-0.jpg"), 64, 48, Color.BLUE);
        Path framesDir = storageDeid.resolve("frames/deid/9100");
        Path parentVideo = storageDeid.resolve("videos/200/deidentified.mp4");
        Files.createDirectories(parentVideo.getParent());
        Files.writeString(parentVideo, "PARENT-DEID-VIDEO");
        AugmentExtractPlan p = new AugmentExtractPlan(9100L, 200L, 20L, "rev1", reference,
                parentVideo, storageDeid.resolve("videos/augment/200/9100/WINTER.mp4"), framesDir,
                List.of(new AugmentExtractPlan.FrameSpec(700L, 0L, 100L, LocalDateTime.now(),
                        vendorOut, framesDir.resolve("frame-0.jpg"))));

        // when
        splitAxisProducer.produce(p);

        // then — 벤더 산출물이 그대로 파생 프레임으로 반입된다
        assertThat(Files.readAllBytes(p.frames().get(0).dst()))
                .as("벤더 트리(읽기 허용)에서 온 산출물이 거부되면 증강 반입이 통째로 실패한다")
                .isEqualTo(Files.readAllBytes(vendorOut));
        // and — 통과의 근거가 "쓰기 루트를 넓혀서" 가 아니어야 한다(PII 격리 축 유지)
        assertThat(splitAxisResolver.allowedRoots())
                .as("벤더 트리를 쓰기 allowlist 로 승격하면 산출물 쓰기 범위가 벤더 트리까지 넓어진다")
                .noneMatch(root -> vendorRoot.toAbsolutePath().normalize().startsWith(root));
    }

    @Test
    @DisplayName("허용루트_안의_심링크가_밖을_가리켜도_반영되지_않는다")
    void symlinkEscape_rejected() throws IOException {
        // given — 허용 루트 안의 파일명이 밖의 실제 파일을 가리킨다(CWE-59).
        Path outsideFile = Files.createTempDirectory("klid-outside-link").resolve("secret.jpg");
        writeImage(outsideFile, 64, 48, Color.BLUE);
        Path link = externalDir.resolve("job-9/linked.jpg");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 심링크 미지원 환경에서는 검증 대상 아님
        }
        AugmentExtractPlan p = plan(9010L, List.of(spec(9010L, 0, link)));

        assertThatThrownBy(() -> producer.produce(p))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        assertThat(Files.exists(p.frames().get(0).dst())).isFalse();
    }

    @Test
    @DisplayName("프레임_반입_중_실패해도_반쯤_채워진_프레임셋이_남지_않는다")
    void partialFailure_leavesNoHalfFilledFrameSet() throws IOException {
        // given — 1번은 정상, 2번은 해상도 불일치.
        Path ok = writeImage(externalDir.resolve("job-7/ok.jpg"), 64, 48, Color.BLUE);
        Path bad = writeImage(externalDir.resolve("job-7/bad.jpg"), 16, 16, Color.BLUE);
        AugmentExtractPlan p = plan(9007L, List.of(spec(9007L, 0, ok), spec(9007L, 1, bad)));

        assertThatThrownBy(() -> producer.produce(p)).isInstanceOf(CustomException.class);
        // 러너 계약대로 cleanup 하면 부분 산출이 사라진다(DB 행은 Phase C 전이라 애초에 없다).
        assertThat(producer.cleanup(9007L, p.videoDst())).isTrue();
        assertThat(Files.exists(framesDir(9007L))).isFalse();
    }

    @Test
    @DisplayName("증강_파생영상의_비디오_파일이_실제로_복사된다")
    void copiesDeidVideoIntoDerivativePath() throws IOException {
        Path out0 = writeImage(externalDir.resolve("job-v1/out-0.jpg"), 64, 48, Color.BLUE);
        AugmentExtractPlan p = plan(9101L, List.of(spec(9101L, 0, out0)));

        producer.produce(p);

        // 기록될 경로(videoDst)에 실제 파일이 있고, 내용은 부모 비식별 영상 그대로다.
        assertThat(Files.exists(p.videoDst())).isTrue();
        assertThat(Files.readAllBytes(p.videoDst())).isEqualTo(Files.readAllBytes(parentDeidVideo));
        // 반쯤 쓰인 임시 파일이 남지 않는다(.part → move).
        assertThat(Files.exists(p.videoDst().resolveSibling(p.videoDst().getFileName() + ".part"))).isFalse();
    }

    @Test
    @DisplayName("복사본은_부모의_비식별_영상이며_원본이_아니다")
    void copiedVideoComesFromDeidentifiedSourceNotOriginal() throws IOException {
        // given — 원본(비-비식별) 영상이 같은 임시 루트에 함께 존재한다.
        Path original = tempDir.resolve("raw/videos/100/original.mp4");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "ORIGINAL-PII-VIDEO");
        Path out0 = writeImage(externalDir.resolve("job-v2/out-0.jpg"), 64, 48, Color.BLUE);
        AugmentExtractPlan p = plan(9102L, List.of(spec(9102L, 0, out0)));

        producer.produce(p);

        assertThat(Files.readString(p.videoDst()))
                .as("원본 픽셀이 파생영상으로 복제되면 PII 노출이다")
                .isEqualTo("PARENT-DEID-VIDEO")
                .isNotEqualTo(Files.readString(original));
    }

    @Test
    @DisplayName("부모_영상_파일을_덮어쓰지_않는다")
    void doesNotOverwriteParentVideo() throws IOException {
        byte[] before = Files.readAllBytes(parentDeidVideo);
        Path out0 = writeImage(externalDir.resolve("job-v3/out-0.jpg"), 64, 48, Color.BLUE);
        AugmentExtractPlan p = plan(9103L, List.of(spec(9103L, 0, out0)));

        producer.produce(p);

        assertThat(p.videoDst()).isNotEqualTo(parentDeidVideo);
        assertThat(Files.readAllBytes(parentDeidVideo)).isEqualTo(before);
    }

    @Test
    @DisplayName("비식별_영상_소스가_없으면_증강이_성공으로_확정되지_않는다")
    void missingDeidVideoSource_failsClosed() throws IOException {
        Path out0 = writeImage(externalDir.resolve("job-v4/out-0.jpg"), 64, 48, Color.BLUE);
        Path missingSrc = deidBase.resolve("videos/100/no-such.mp4");
        AugmentExtractPlan p = new AugmentExtractPlan(9104L, 100L, 20L, "rev1", parentFrame,
                missingSrc, videoDst(9104L), framesDir(9104L), List.of(spec(9104L, 0, out0)));

        assertThatThrownBy(() -> producer.produce(p))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("NOT_FOUND");
        // 실패했으므로 파생 비디오·프레임 어느 것도 산출되지 않는다(성공 위장 금지).
        assertThat(Files.exists(p.videoDst())).isFalse();
        assertThat(Files.exists(p.frames().get(0).dst())).isFalse();
    }

    @Test
    @DisplayName("같은_계획을_두_번_반입해도_비디오가_두_번_쌓이지_않는다")
    void repeatedProduce_isIdempotentOnVideo() throws IOException {
        Path out0 = writeImage(externalDir.resolve("job-v5/out-0.jpg"), 64, 48, Color.BLUE);
        AugmentExtractPlan p = plan(9105L, List.of(spec(9105L, 0, out0)));

        producer.produce(p);
        producer.produce(p);

        try (var files = Files.list(p.videoDst().getParent())) {
            List<Path> written = new ArrayList<>(files.toList());
            assertThat(written).hasSize(1);
            assertThat(written.get(0)).isEqualTo(p.videoDst());
        }
        assertThat(Files.readAllBytes(p.videoDst())).isEqualTo(Files.readAllBytes(parentDeidVideo));
    }

    @Test
    @DisplayName("cleanup은_복사된_파생비디오도_삭제하고_부모_영상은_남긴다")
    void cleanupDeletesCopiedVideoOnly() throws IOException {
        Path out0 = writeImage(externalDir.resolve("job-v6/out-0.jpg"), 64, 48, Color.BLUE);
        AugmentExtractPlan p = plan(9106L, List.of(spec(9106L, 0, out0)));
        producer.produce(p);

        assertThat(producer.cleanup(9106L, p.videoDst())).isTrue();

        assertThat(Files.exists(p.videoDst())).isFalse();
        assertThat(Files.exists(framesDir(9106L))).isFalse();
        assertThat(Files.exists(parentDeidVideo)).as("부모 비식별 영상은 정리 대상이 아니다").isTrue();
    }

    @Test
    @DisplayName("같은_계획을_두_번_반입해도_프레임이_두_번_쌓이지_않는다")
    void repeatedProduce_isIdempotentOnFiles() throws IOException {
        Path out0 = writeImage(externalDir.resolve("job-8/out-0.jpg"), 64, 48, Color.BLUE);
        AugmentExtractPlan p = plan(9008L, List.of(spec(9008L, 0, out0)));

        producer.produce(p);
        producer.produce(p);

        try (var files = Files.list(framesDir(9008L))) {
            List<Path> written = new ArrayList<>(files.toList());
            assertThat(written).hasSize(1);
            assertThat(written.get(0).getFileName().toString()).isEqualTo("frame-0.jpg");
        }
    }

    @Test
    @DisplayName("cleanup은_파생프레임_디렉토리를_삭제하고_잔존없으면_true")
    void cleanupDeletesFramesDir() throws IOException {
        Path dir = framesDir(9009L);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("frame-0.jpg"), "dummy");

        assertThat(producer.cleanup(9009L, videoDst(9009L))).isTrue();
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    @DisplayName("cleanup은_디렉토리가_없어도_true_멱등")
    void cleanupIdempotentWhenAbsent() {
        assertThat(producer.cleanup(9999L, videoDst(9999L))).isTrue();
    }

    @Test
    @DisplayName("Phase_B는_리포지토리를_주입하지_않는다_무커넥션_구조보증")
    void hasNoRepositoryDependency() {
        for (Field f : AugmentFrameProducer.class.getDeclaredFields()) {
            String typeName = f.getType().getName();
            assertThat(typeName)
                    .as("Phase B 는 DB 커넥션을 잡지 않도록 리포지토리 의존이 0 이어야 한다: %s", f.getName())
                    .doesNotContain("Repository");
        }
    }
}
