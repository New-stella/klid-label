package kr.co.cudo.authoring.common.storage;

import kr.co.cudo.authoring.support.TestVideoFixtures;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-ISSUE-01 — 비식별 산출물 무결성 판정 단위 테스트.
 *
 * <p>핵심 균형: <b>스텁/위장 파일은 거부</b>하되 <b>정상(작아도) 영상은 절대 거부하지 않는다</b>.
 * 판정 실패는 terminal 'F'(자동 재시도 없음)로 이어지므로 오탐이 곧 운영 사고다.
 */
class DeidentArtifactIntegrityTest {

    @TempDir
    Path tmp;

    private Path write(String name, byte[] bytes) throws Exception {
        Path p = tmp.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private byte[] filled(int size, byte value) {
        byte[] b = new byte[size];
        Arrays.fill(b, value);
        return b;
    }

    // ────────────────────────── 거부(스텁/위장) ──────────────────────────

    @Test
    @DisplayName("18바이트_목_placeholder는_거부된다")
    void rejectsMockPlaceholder() throws Exception {
        Path stub = write("clip-mask.mp4",
                "MOCK_DEIDENTIFIED\n".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.size(stub)).isEqualTo(18L);

        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(stub.toString())).isFalse();
    }

    @Test
    @DisplayName("크기하한_미만이면_거부된다")
    void rejectsBelowMinimumSize() throws Exception {
        // 시그니처는 정상(ftyp)이지만 크기가 하한 미만 — 구조적으로 성립 불가한 영상.
        byte[] head = new byte[(int) DeidentArtifactIntegrity.MIN_VIDEO_BYTES - 1];
        System.arraycopy("....ftypisom".getBytes(StandardCharsets.ISO_8859_1), 0, head, 0, 12);
        Path p = write("small.mp4", head);

        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(p.toString())).isFalse();
    }

    @Test
    @DisplayName("컨테이너_시그니처가_없는_텍스트_파일은_거부된다")
    void rejectsUnknownSignature() throws Exception {
        Path p = write("log.mp4", filled(8192, (byte) 'A'));

        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(p.toString())).isFalse();
    }

    @Test
    @DisplayName("미존재_blank_null_경로는_거부된다")
    void rejectsMissingAndBlankPaths() {
        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(null)).isFalse();
        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact("  ")).isFalse();
        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(tmp.resolve("nope.mp4").toString()))
                .isFalse();
        // 디렉터리도 정규 파일이 아니다.
        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(tmp.toString())).isFalse();
    }

    @Test
    @DisplayName("심볼릭링크는_정규파일로_통과하지_않는다")
    void rejectsSymlink() throws Exception {
        Path real = TestVideoFixtures.writeTinyMp4(tmp.resolve("real.mp4"));
        Path link = tmp.resolve("link.mp4");
        try {
            Files.createSymbolicLink(link, real);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "심링크 미지원 환경 — skip");
        }

        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(link.toString())).isFalse();
    }

    // ────────────────────────── 수용(정상 영상 — 오탐 방지) ──────────────────────────

    @Test
    @DisplayName("실측_최소영상_1546바이트_mp4는_수용된다")
    void acceptsRealTinyMp4() {
        // ffmpeg 산출 H.264 16x16 1프레임 mp4(1,546B) — "정상이지만 아주 작은 영상"의 실측 하한.
        Path p = TestVideoFixtures.writeTinyMp4(tmp.resolve("tiny.mp4"));

        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(p.toString())).isTrue();
    }

    @Test
    @DisplayName("mp4_이외_컨테이너_시그니처도_관대하게_수용된다")
    void acceptsOtherContainerSignatures() throws Exception {
        // 코덱/무결 파싱은 하지 않는다 — 알려진 컨테이너 선두 시그니처면 통과(오탐 거부 방지).
        byte[] body = filled(2048, (byte) 0x11);
        assertThat(accepted("mkv", new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}, body)).isTrue();   // Matroska
        assertThat(accepted("avi", "RIFF....AVI ".getBytes(StandardCharsets.ISO_8859_1), body)).isTrue();
        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(
                write("ts.bin", mpegTsBytes(4)).toString())).isTrue();                                  // MPEG-TS
        assertThat(accepted("ps", new byte[]{0x00, 0x00, 0x01, (byte) 0xBA}, body)).isTrue();           // MPEG-PS
        assertThat(accepted("mov", "....moov".getBytes(StandardCharsets.ISO_8859_1), body)).isTrue();   // QuickTime
        assertThat(accepted("flv", "FLV".getBytes(StandardCharsets.ISO_8859_1), body)).isTrue();
    }

    // ────────────────────── MPEG-TS 시그니처 강화 (DEV_FIX MEDIUM-1) ──────────────────────

    @Test
    @DisplayName("MPEG_TS_오판_텍스트_파일은_유효한_비식별본이_아니다")
    void rejectsTextFileMisreadAsMpegTs() throws Exception {
        // given — 'G'(0x47) 로 시작하는 평범한 액세스 로그. 구 판정은 선두 1바이트만 봐서 통과시켰고,
        //         그 오판이 재비식별 완료 검증을 뚫어 'F'→'Y' 복원(라벨/스트리밍/export 동시 개방)을 유발했다.
        StringBuilder log = new StringBuilder();
        while (log.length() < 4096) {
            log.append("GET /v1/videos/1/stream HTTP/1.1 200 OK\n");
        }
        Path p = write("access-log.mp4", log.toString().getBytes(StandardCharsets.ISO_8859_1));
        assertThat(Files.size(p)).isGreaterThan(DeidentArtifactIntegrity.MIN_VIDEO_BYTES);

        // when / then — 188바이트 정렬이 없으므로 MPEG-TS 로 인정되지 않는다.
        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(p.toString())).isFalse();
    }

    @Test
    @DisplayName("선두만_0x47_이고_188바이트_정렬이_없으면_거부된다")
    void rejectsSingleSyncByteWithoutPacketAlignment() throws Exception {
        byte[] bytes = filled(4096, (byte) 0x11);
        bytes[0] = 0x47;
        Path p = write("fake-ts.bin", bytes);

        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(p.toString())).isFalse();
    }

    @Test
    @DisplayName("정상_mp4_픽스처는_강화된_시그니처_검사를_통과한다")
    void realMp4FixtureStillPasses() {
        // 오탐 방지 회귀 가드 — 판정 실패는 terminal 'F'(자동 재시도 없음)라 정상 파일 거부가 곧 사고다.
        Path p = TestVideoFixtures.writeTinyMp4(tmp.resolve("regression.mp4"));

        assertThat(DeidentArtifactIntegrity.isValidVideoArtifact(p.toString())).isTrue();
    }

    @Test
    @DisplayName("ISO_박스타입_junk_는_더_이상_영상_컨테이너로_인정되지_않는다")
    void rejectsJunkBoxType() throws Exception {
        byte[] body = filled(2048, (byte) 0x11);
        assertThat(accepted("junkbox", "....junk".getBytes(StandardCharsets.ISO_8859_1), body)).isFalse();
    }

    /** 188바이트 패킷 n 개짜리 MPEG-TS 유사 스트림(각 패킷 선두에 동기 바이트 0x47). */
    private byte[] mpegTsBytes(int packets) {
        byte[] bytes = filled(188 * packets, (byte) 0x11);
        for (int i = 0; i < packets; i++) {
            bytes[i * 188] = 0x47;
        }
        return bytes;
    }

    /** 선두 시그니처 + 충분한 본문으로 파일을 만들고 판정 결과를 돌려준다. */
    private boolean accepted(String name, byte[] signature, byte[] body) throws Exception {
        byte[] bytes = new byte[signature.length + body.length];
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        System.arraycopy(body, 0, bytes, signature.length, body.length);
        Path p = write(name + ".bin", bytes);
        return DeidentArtifactIntegrity.isValidVideoArtifact(p.toString());
    }
}
