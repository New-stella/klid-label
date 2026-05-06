package kr.co.cudo.authoring.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestJsonlWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("manifest_jsonl_writer_헤더_라인_먼저_쓰기")
    void writeVideoHeaderProducesThreeLines() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ManifestJsonlWriter w = new ManifestJsonlWriter(out)) {
            w.writeVideoHeader("video.mp4", 1920, 1080, 300L);
        }
        List<String> lines = lines(out);
        assertThat(lines).hasSize(3);

        JsonNode v = MAPPER.readTree(lines.get(0));
        assertThat(v.get("version").asText()).isEqualTo("1.1");

        JsonNode t = MAPPER.readTree(lines.get(1));
        assertThat(t.get("type").asText()).isEqualTo("video");

        JsonNode p = MAPPER.readTree(lines.get(2));
        JsonNode props = p.get("properties");
        assertThat(props.get("name").asText()).isEqualTo("video.mp4");
        assertThat(props.get("resolution").get(0).asInt()).isEqualTo(1920);
        assertThat(props.get("resolution").get(1).asInt()).isEqualTo(1080);
        assertThat(props.get("length").asLong()).isEqualTo(300L);
        assertThat(props.get("chapters").isArray()).isTrue();
        assertThat(props.get("chapters").size()).isZero();
    }

    @Test
    @DisplayName("manifest_jsonl_writer_프레임_메타_라인당_1건_생성")
    void writeKeyFrameProducesOneLinePerFrame() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ManifestJsonlWriter w = new ManifestJsonlWriter(out)) {
            w.writeVideoHeader("video.mp4", 640, 480, 100L);
            w.writeKeyFrame(0L, 0L, "abc123");
            w.writeKeyFrame(12L, 12012L, "def456");
            w.writeKeyFrame(24L, 24024L, "ghi789");
        }
        List<String> lines = lines(out);
        // 헤더 3줄 + 키프레임 3줄
        assertThat(lines).hasSize(6);

        JsonNode k1 = MAPPER.readTree(lines.get(3));
        assertThat(k1.get("number").asLong()).isZero();
        assertThat(k1.get("pts").asLong()).isZero();
        assertThat(k1.get("checksum").asText()).isEqualTo("abc123");

        JsonNode k2 = MAPPER.readTree(lines.get(4));
        assertThat(k2.get("number").asLong()).isEqualTo(12L);
        assertThat(k2.get("pts").asLong()).isEqualTo(12012L);
    }

    @Test
    @DisplayName("manifest_jsonl_writer_특수문자_포함_파일명_정상_이스케이프")
    void specialCharactersInFilenameAreEscaped() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String tricky = "한글 이름 \"with quotes\" \\ backslash.mp4";
        try (ManifestJsonlWriter w = new ManifestJsonlWriter(out)) {
            w.writeVideoHeader(tricky, 1280, 720, 50L);
        }
        List<String> lines = lines(out);
        assertThat(lines).hasSize(3);

        // 라인이 JSON 으로 라운드트립 가능해야 함 (이스케이프 정상)
        JsonNode props = MAPPER.readTree(lines.get(2)).get("properties");
        assertThat(props.get("name").asText()).isEqualTo(tricky);
    }

    @Test
    @DisplayName("manifest_jsonl_writer_헤더_없이_키프레임_쓰기_금지")
    void writeKeyFrameWithoutHeaderFails() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ManifestJsonlWriter w = new ManifestJsonlWriter(out)) {
            assertThatThrownBy(() -> w.writeKeyFrame(0L, 0L, "abc"))
                    .isInstanceOf(IllegalStateException.class);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private List<String> lines(ByteArrayOutputStream out) {
        String body = out.toString(StandardCharsets.UTF_8);
        // 마지막 \n 분리 후 빈 라인 제거.
        return Arrays.stream(body.split("\n", -1))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
