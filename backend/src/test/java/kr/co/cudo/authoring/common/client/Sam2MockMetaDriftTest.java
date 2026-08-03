package kr.co.cudo.authoring.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.client.dto.AiMockMeta;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai-server SAM2/YOLO 응답의 <b>mock 메타 계약 드리프트</b> 검증 (C-ISSUE-81, CWE-345).
 *
 * <p>배경: BE 는 ai-server 가 붙여 보내는 {@code mock}/{@code source}/{@code mock_reason} 을 읽어
 * 신뢰 불가 좌표(모델 미로드 시 시드 폴리곤 복사본)를 결과에서 제외한다. <b>클라이언트 DTO 가 이 필드를
 * 선언하지 않으면 신호가 조용히 사라져</b> mock 좌표가 정상 결과로 자동 적용된다 — 실제로 track 경로가
 * 그 상태였다(C-ISSUE-81). 따라서 "ai-server 가 내보내는 mock 메타를 BE DTO 가 전부 보유한다"를
 * 계약으로 고정한다.
 *
 * <p><b>실행 환경 의존 제거(구 구현 결함 정정)</b>: 예전에는 계약 정밀검증이
 * {@code assumeTrue(Files.exists("../ai-server/app/schemas.py"))} 였다. ai-server 트리가 없는
 * 분리 CI·배포 환경에서는 <b>아무것도 검증하지 않은 채 GREEN</b> 이 되어 가드가 사실상 사라졌다.
 * 이제 계약을 BE 테스트 리소스({@code contracts/ai-server-sam2-schema.json})로 동봉해
 * <b>항상 실행</b>하고, ai-server 소스가 함께 있는 환경에서는 스냅샷↔실제 스키마 교차검증까지 한다.
 */
class Sam2MockMetaDriftTest {

    /** ai-server 스키마의 mock 메타 3필드(snake_case 원문). */
    private static final Set<String> MOCK_META = new LinkedHashSet<>(
            Arrays.asList("mock", "source", "mock_reason"));

    /** BE 에 동봉한 ai-server 계약 스냅샷(classpath). */
    private static final String SNAPSHOT = "contracts/ai-server-sam2-schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("SAM2 track DTO 는 mock 메타 3필드를 모두 보유한다")
    void trackDtoHasMockMeta() {
        assertThat(snakeComponents(Sam2TrackResponse.class)).containsAll(MOCK_META);
    }

    @Test
    @DisplayName("SAM2 segment DTO 는 mock 메타 3필드를 모두 보유한다")
    void segmentDtoHasMockMeta() {
        assertThat(snakeComponents(Sam2Response.class)).containsAll(MOCK_META);
    }

    @Test
    @DisplayName("ai-server 가 보낸 track mock 응답 JSON 이 DTO 로 손실 없이 역직렬화된다")
    void trackMockJsonDeserializes() throws Exception {
        // ai-server _mock_track 실제 형태(시드 폴리곤 복사 + score 0.9).
        String json = """
                {"track_id":"t-1","polygon":[[10.0,10.0],[30.0,10.0],[30.0,30.0]],
                 "score":0.9,"mock":true,"source":"mock","mock_reason":"weights_missing"}
                """;

        Sam2TrackResponse res = objectMapper.readValue(json, Sam2TrackResponse.class);

        assertThat(res.trackId()).isEqualTo("t-1");
        assertThat(res.mock()).isTrue();
        assertThat(res.source()).isEqualTo("mock");
        assertThat(res.mockReason()).isEqualTo("weights_missing");
        assertThat(res.untrusted()).isTrue();
    }

    @Test
    @DisplayName("mock 메타가 없는 응답은 mock=false 로 채워지되 신뢰 불가로 판정된다")
    void trackLegacyJsonIsUntrustedDespiteFalseMockFlag() throws Exception {
        String json = "{\"track_id\":\"t-2\",\"polygon\":[[1.0,1.0],[2.0,1.0],[2.0,2.0]],\"score\":0.7}";

        Sam2TrackResponse res = objectMapper.readValue(json, Sam2TrackResponse.class);

        // primitive boolean 기본값 — 이 값만 보면 fail-open 이다.
        assertThat(res.mock()).isFalse();
        // 긍정 증명(source="model") 부재 → 신뢰 불가.
        assertThat(res.untrusted()).isTrue();
    }

    @Test
    @DisplayName("BE DTO 가 ai-server 계약 스냅샷의 필드를 모두 보유한다(항상 실행 — 환경 의존 없음)")
    void beDtosSatisfySnapshot() throws Exception {
        assertSnapshotContract();
    }

    @Test
    @DisplayName("ai-server schemas.py 가 스냅샷 계약과 일치한다(ai-server 트리 존재 시 필수 검증)")
    void liveAiServerSchemaMatchesSnapshot() throws Exception {
        Path aiRoot = Path.of("..", "ai-server");
        if (!Files.isDirectory(aiRoot)) {
            // 분리 CI(ai-server 미체크아웃) — 구 구현처럼 '검증 0건 GREEN' 이 되지 않도록,
            // 이 경로에서도 동봉 스냅샷 계약을 반드시 강제한다.
            assertSnapshotContract();
            return;
        }
        Path py = aiRoot.resolve("app").resolve("schemas.py");
        assertThat(Files.exists(py))
                .as("ai-server 트리는 있는데 %s 가 없다 — 계약 경로 파손", py)
                .isTrue();

        String source = Files.readString(py);
        JsonNode snapshot = readSnapshot();
        String modelValue = snapshot.get("source_model_value").asText();

        for (JsonNode model : snapshot.get("models")) {
            String aiModel = model.get("ai_server_model").asText();
            Set<String> aiFields = pydanticFields(source, aiModel);
            List<String> required = required(model);

            // ① ai-server 가 계약 필드를 계속 내보내는가(필드 삭제·개명 감지).
            assertThat(aiFields)
                    .as("ai-server %s 가 계약 필드를 잃었다", aiModel)
                    .containsAll(required);
            // ② BE DTO 가 계약 필드를 소화하는가(신호 유실 감지).
            //    track 은 ai-server 필드 전량 일치까지 요구한다(신규 필드 추가도 즉시 드러남).
            //    segment/yolo 는 ai-server 가 BE 미소비 엔벨로프(success/message/error_code)를
            //    더 내보내므로 required 기준으로만 본다.
            boolean strict = model.path("be_consumes_all_fields").asBoolean(false);
            assertThat(snakeComponents(beDto(model)))
                    .as("BE DTO 가 ai-server %s 필드를 소화하지 못한다", aiModel)
                    .containsAll(strict ? aiFields : new LinkedHashSet<>(required));
            // ③ 실모델 출처 값이 BE 판정 상수와 같은가 — 다르면 전량 fail-closed 로 기능이 죽는다.
            assertThat(sourceDefault(source, aiModel))
                    .as("ai-server %s.source 기본값이 BE 신뢰 상수와 다르다", aiModel)
                    .isEqualTo(modelValue);
        }
    }

    /** 동봉 스냅샷 계약 강제 — BE DTO 필드 보유 + 신뢰 상수 일치. */
    private void assertSnapshotContract() throws Exception {
        JsonNode snapshot = readSnapshot();

        assertThat(snapshot.get("source_model_value").asText())
                .as("BE 신뢰 판정 상수(AiMockMeta.SOURCE_MODEL)가 ai-server 계약과 달라졌다")
                .isEqualTo(AiMockMeta.SOURCE_MODEL);

        List<String> mockMeta = new ArrayList<>();
        snapshot.get("mock_meta_fields").forEach(n -> mockMeta.add(n.asText()));
        assertThat(mockMeta).containsExactlyElementsOf(MOCK_META);

        assertThat(snapshot.get("models")).isNotEmpty();
        for (JsonNode model : snapshot.get("models")) {
            Class<?> dto = beDto(model);
            assertThat(snakeComponents(dto))
                    .as("%s 가 ai-server %s 계약 필드를 보유하지 않는다",
                            dto.getSimpleName(), model.get("ai_server_model").asText())
                    .containsAll(required(model));
            // mock 메타는 어느 DTO에서도 빠지면 안 된다(신호 유실 = fail-open 재발).
            assertThat(snakeComponents(dto)).containsAll(MOCK_META);
        }
    }

    private JsonNode readSnapshot() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SNAPSHOT)) {
            assertThat(in).as("계약 스냅샷 리소스(%s)가 없다 — 드리프트 가드가 무력화된다", SNAPSHOT)
                    .isNotNull();
            return objectMapper.readTree(in);
        }
    }

    private Class<?> beDto(JsonNode model) throws ClassNotFoundException {
        return Class.forName(model.get("be_dto").asText());
    }

    private List<String> required(JsonNode model) {
        List<String> out = new ArrayList<>();
        model.get("required_fields").forEach(n -> out.add(n.asText()));
        return out;
    }

    /** record 컴포넌트를 snake_case 로 환산(camelCase → snake_case). */
    private Set<String> snakeComponents(Class<?> recordType) {
        Set<String> out = new LinkedHashSet<>();
        for (RecordComponent rc : recordType.getRecordComponents()) {
            out.add(rc.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase());
        }
        return out;
    }

    /** pydantic 모델 본문에서 필드명(`name: type`)을 추출한다. */
    private Set<String> pydanticFields(String source, String className) {
        String body = modelBody(source, className);

        Set<String> fields = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^\\s{4}([a-z_][a-z0-9_]*)\\s*:\\s*\\S").matcher(body);
        while (m.find()) {
            String name = m.group(1);
            if (!"model_config".equals(name)) {
                fields.add(name);
            }
        }
        return fields;
    }

    /** pydantic 모델의 {@code source: str = Field(default="...")} 기본값을 추출한다. */
    private String sourceDefault(String source, String className) {
        Matcher m = Pattern.compile("(?m)^\\s{4}source\\s*:\\s*str\\s*=\\s*Field\\(default=\"([^\"]+)\"")
                .matcher(modelBody(source, className));
        assertThat(m.find()).as("%s 에 source 기본값 선언이 없다", className).isTrue();
        return m.group(1);
    }

    private String modelBody(String source, String className) {
        int begin = source.indexOf("class " + className + "(BaseModel):");
        assertThat(begin).as("ai-server 에 %s 가 있어야 한다", className).isGreaterThanOrEqualTo(0);
        int end = source.indexOf("\nclass ", begin + 1);
        return end < 0 ? source.substring(begin) : source.substring(begin, end);
    }
}
