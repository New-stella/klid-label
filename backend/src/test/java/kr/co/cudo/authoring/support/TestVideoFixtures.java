package kr.co.cudo.authoring.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 비식별 산출물 무결성 검증({@code DeidentArtifactIntegrity})을 통과하는 <b>실제 영상 바이트</b> 픽스처.
 *
 * <p>{@code src/test/resources/fixtures/tiny-video.mp4} 는 ffmpeg 으로 생성한 <b>실제 재생 가능한</b>
 * 최소 mp4 다(H.264 16x16 1프레임, 1,546바이트 — ftyp 32 + free 8 + mdat 717 + moov 789).
 * "정상이지만 아주 작은 영상"의 실측 하한 근거이자, 산출물 무결성 강화 후에도 정상 파일이
 * 오탐 거부되지 않음을 고정하는 회귀 픽스처로 쓴다.
 *
 * <p>무결성 검증이 강화되기 전에는 테스트들이 {@code "MASKED"} 같은 텍스트를 비식별 산출물로 썼는데,
 * 그런 픽스처는 실제 KPST 산출물을 대표하지 못한다(18바이트 스텁도 통과시켜버린 결함의 원인).
 */
public final class TestVideoFixtures {

    /** 클래스패스 상의 최소 유효 mp4 픽스처 경로. */
    private static final String TINY_MP4_RESOURCE = "fixtures/tiny-video.mp4";

    private TestVideoFixtures() {
    }

    /** 실제 재생 가능한 최소 mp4 바이트(1,546바이트). */
    public static byte[] tinyMp4Bytes() {
        try (InputStream in = TestVideoFixtures.class.getClassLoader()
                .getResourceAsStream(TINY_MP4_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("테스트 픽스처를 찾을 수 없습니다: " + TINY_MP4_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 대상 경로에 최소 유효 mp4 를 쓴다(부모 디렉터리 자동 생성). */
    public static Path writeTinyMp4(Path target) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, tinyMp4Bytes());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
