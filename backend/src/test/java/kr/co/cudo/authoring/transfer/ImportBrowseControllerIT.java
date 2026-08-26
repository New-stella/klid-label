package kr.co.cudo.authoring.transfer;

import com.jayway.jsonpath.JsonPath;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이관 대상 위치 탐색 API 통합 시험 — {@code /api/v1/imports/folders}, {@code /api/v1/imports/files}.
 *
 * <h3>여기서 확인하는 것은 <b>계약면</b>이다</h3>
 * <p>목록에 무엇이 담기고 무엇이 빠지는지, 상한에 걸리면 어떻게 답하는지는 진짜 바로가기와 수천 건이
 * 필요해 {@code ImportBrowseServiceTest} 가 임시 폴더 위에서 고정한다. 이 시험은 그 규칙이
 * <b>HTTP 계약</b>으로 나올 때의 축만 본다 — 인가, 상태코드가 범위 밖(400)과 부재(404)로 갈리는지,
 * 응답이 그 값을 다시 넣어도 통과하는 자리를 주는지.
 *
 * <h3>표본 폴더는 실물이다</h3>
 * <p>이 형식은 우리가 만든 것이 아니라 받는 것이다. 저장소에 함께 둔 표본을 그대로 읽고, 그 폴더가
 * 들어 있는 {@code docs} 를 읽기 허용 루트로 지정한다.
 *
 * <h3>⚠ 컨텍스트 설정을 바꾸지 말 것</h3>
 * <p>이 클래스의 {@code @SpringBootTest} 설정은 {@code ImportScanControllerIT} 와 <b>글자 그대로
 * 같아야</b> 한다. 그래야 캐시된 컨텍스트를 함께 쓰고 새 컨텍스트가 늘지 않는다
 * ({@code TestContextDiversityRatchetTest} 가 그 수에 상한을 박아 두었다). 같은 이유로
 * {@code @DynamicPropertySource}·{@code @MockBean} 을 여기에 더하지 않는다 — 하나라도 붙는 순간
 * 별개 컨텍스트가 되어 테스트 워커 힙에 그만큼 상주한다.
 *
 * @design DOMAIN-017
 * @design API-221
 * @design API-222
 * @design AC-120
 * @design AC-048
 */
@SpringBootTest(properties = "authoring.storage.external-read-roots=../docs")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportBrowseControllerIT {

    private static final String FOLDERS = "/v1/imports/folders";
    private static final String FILES = "/v1/imports/files";

    @Autowired private MockMvc mockMvc;
    @Autowired private ImportSourcePolicy sourcePolicy;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
    }

    // ------------------------------------------------------------------ 루트 목록

    @Test
    @DisplayName("위치를_지정하지_않으면_허용_저장소_루트_목록이_그대로_돌아온다")
    void 위치를_지정하지_않으면_허용_저장소_루트_목록이_그대로_돌아온다() throws Exception {
        // 검사·적재가 받아들이는 범위와 <같은 자리>에서 나온다(AC-048).
        List<Path> roots = sourcePolicy.readableRoots();

        var expectation = mockMvc.perform(get(FOLDERS).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 기준 위치가 하나로 정해지지 않으므로 둘 다 비어 있다.
                .andExpect(jsonPath("$.data.path").doesNotExist())
                .andExpect(jsonPath("$.data.parent").doesNotExist())
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.entries.length()").value(roots.size()));
        for (int i = 0; i < roots.size(); i++) {
            expectation.andExpect(jsonPath("$.data.entries[" + i + "].path").value(roots.get(i).toString()));
        }
    }

    // ------------------------------------------------------------------ 정상 탐색

    @Test
    @DisplayName("폴더_탐색은_한_단계_아래만_돌려주고_실제로_닿는_자리를_싣는다")
    void 폴더_탐색은_한_단계_아래만_돌려주고_실제로_닿는_자리를_싣는다() throws Exception {
        Path parent = sampleFolder().getParent();

        mockMvc.perform(get(FOLDERS)
                        .param("path", parent.toString())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries.length()").value(1))
                .andExpect(jsonPath("$.data.entries[0].name").value("00000073"))
                // 표기가 아니라 실제로 닿는 자리를 돌려준다(CWE-367).
                .andExpect(jsonPath("$.data.path").value(parent.toRealPath().toString()))
                // ★한 단계 위가 허용 저장소 루트(docs)여도 범위 안이라 그 자리를 그대로 싣는다 —
                //  비우면 루트 바로 아래에서 「상위로」가 꺼져 사람이 갇힌다.
                .andExpect(jsonPath("$.data.parent").value(readableRootOfDocs()));
    }

    @Test
    @DisplayName("지금_자리가_허용_저장소_루트면_위가_비어서_돌아온다")
    void 지금_자리가_허용_저장소_루트면_위가_비어서_돌아온다() throws Exception {
        // 루트에서 한 단계 위는 범위 밖이다 — 비어서 돌아오고, 화면은 그때 루트 목록으로 돌아간다.
        mockMvc.perform(get(FOLDERS)
                        .param("path", readableRootOfDocs())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parent").doesNotExist());
    }

    @Test
    @DisplayName("돌려준_위를_그대로_다시_넣으면_받아들여져_한_단계씩_올라갈_수_있다")
    void 돌려준_위를_그대로_다시_넣으면_받아들여져_한_단계씩_올라갈_수_있다() throws Exception {
        // ★이 왕복이 결함 가드다 — 응답이 준 위를 다시 넣었을 때 거부되거나 근거 없이 비면
        //  화면의 「상위로」가 그 자리에서 끊긴다.
        // ⚠ 본문은 반드시 UTF-8 로 디코드한다 — 표본 폴더 이름이 한글이라 기본 디코딩으로 읽으면
        //  경로가 깨져 다음 요청이 404 로 떨어진다(이 시험이 실제로 그렇게 한 번 빨갛게 떴다).
        String up = JsonPath.read(mockMvc.perform(get(FOLDERS)
                        .param("path", sampleFolder().toString())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.parent");

        String upUp = JsonPath.read(mockMvc.perform(get(FOLDERS)
                        .param("path", up)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.parent");

        // docs 까지 올라오면 그 위는 범위 밖이라 비고, 거기서 루트 목록으로 돌아간다.
        mockMvc.perform(get(FOLDERS)
                        .param("path", upUp)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parent").doesNotExist());
    }

    @Test
    @DisplayName("탐색이_돌려준_위치는_다시_넣어도_같은_판정을_통과한다")
    void 탐색이_돌려준_위치는_다시_넣어도_같은_판정을_통과한다() throws Exception {
        // 화면은 이 값을 그대로 입력칸에 넣는다 — 왕복이 어긋나면 고른 값이 곧바로 거부된다.
        String echoed = sampleFolder().toRealPath().toString();

        mockMvc.perform(get(FOLDERS)
                        .param("path", echoed)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value(echoed))
                // 루트가 아닌 자리는 위로 갈 수 있다.
                .andExpect(jsonPath("$.data.parent")
                        .value(sampleFolder().getParent().toRealPath().toString()));
    }

    @Test
    @DisplayName("영상_파일_탐색에는_영상이_아닌_파일이_담기지_않는다")
    void 영상_파일_탐색에는_영상이_아닌_파일이_담기지_않는다() throws Exception {
        // 표본 폴더는 이미지와 문서로만 차 있다 — 하나도 담기지 않는 것이 정답이다.
        mockMvc.perform(get(FILES)
                        .param("path", sampleFolder().toString())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries.length()").value(0))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.path").value(sampleFolder().toRealPath().toString()));
    }

    @Test
    @DisplayName("영상_파일_탐색은_위치를_생략할_수_없다")
    void 영상_파일_탐색은_위치를_생략할_수_없다() throws Exception {
        mockMvc.perform(get(FILES).header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    // ------------------------------------------------------------------ 거부 (AC-048)

    @Test
    @DisplayName("허용_범위_밖_경로는_400이고_거부_사유에_입력_원문이_실리지_않는다")
    void 허용_범위_밖_경로는_400이고_거부_사유에_입력_원문이_실리지_않는다() throws Exception {
        for (String endpoint : List.of(FOLDERS, FILES)) {
            mockMvc.perform(get(endpoint)
                            .param("path", "/etc")
                            .header("Authorization", "Bearer " + reviewerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
                    // CWE-209 — 어디를 물었는지 응답이 되풀이하지 않는다.
                    .andExpect(content().string(not(containsString("/etc"))));
        }
    }

    @Test
    @DisplayName("상위로_거슬러_올라가는_표기는_400이다")
    void 상위로_거슬러_올라가는_표기는_400이다() throws Exception {
        String traversal = sampleFolder().resolve("../../../backend").toString();

        for (String endpoint : List.of(FOLDERS, FILES)) {
            mockMvc.perform(get(endpoint)
                            .param("path", traversal)
                            .header("Authorization", "Bearer " + reviewerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("허용_범위_안이지만_없는_경로는_404로_범위_밖_거부와_갈린다")
    void 허용_범위_안이지만_없는_경로는_404로_범위_밖_거부와_갈린다() throws Exception {
        for (String endpoint : List.of(FOLDERS, FILES)) {
            mockMvc.perform(get(endpoint)
                            .param("path", sampleFolder().resolveSibling("00000000").toString())
                            .header("Authorization", "Bearer " + reviewerToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("지정한_위치가_폴더가_아니면_400이다")
    void 지정한_위치가_폴더가_아니면_400이다() throws Exception {
        String file = sampleFolder().resolve("00000001.jpg").toString();

        mockMvc.perform(get(FOLDERS)
                        .param("path", file)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("경로가_허용_길이를_넘으면_400이다")
    void 경로가_허용_길이를_넘으면_400이다() throws Exception {
        String tooLong = sampleFolder() + "/" + "a".repeat(ImportSourcePolicy.FOLDER_PATH_MAX);

        mockMvc.perform(get(FOLDERS)
                        .param("path", tooLong)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("검수자가_아니면_경로의_유효성과_무관하게_같은_방식으로_거부한다")
    void 검수자가_아니면_경로의_유효성과_무관하게_같은_방식으로_거부한다() throws Exception {
        // 있는 자리든 없는 자리든 응답이 같아야 한다 — 갈리면 응답이 그 위치의 존재를 알려준다.
        for (String path : List.of(sampleFolder().toString(),
                sampleFolder().resolveSibling("00000000").toString())) {
            for (String endpoint : List.of(FOLDERS, FILES)) {
                mockMvc.perform(get(endpoint)
                                .param("path", path)
                                .header("Authorization", "Bearer " + workerToken))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
            }
        }
        // 루트 목록도 검수자 전용이다.
        mockMvc.perform(get(FOLDERS).header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("토큰이_없으면_인증_실패다")
    void 토큰이_없으면_인증_실패다() throws Exception {
        mockMvc.perform(get(FOLDERS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(FILES).param("path", sampleFolder().toString()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 보조

    /** 허용 저장소 루트로 지정한 {@code docs} 의 실경로 — 응답이 싣는 값과 같은 형태로 맞춘다. */
    private static String readableRootOfDocs() throws IOException {
        return Paths.get("..", "docs").toRealPath().toString();
    }

    /**
     * 표본 폴더 — 디렉터리 이름이 한글이라 파일시스템마다 자모 결합 형태가 다르다(맥은 분리형으로
     * 저장한다). 문자열을 이어 붙이면 플랫폼에 따라 못 찾으므로 부모를 훑어 정규화 후 비교한다.
     */
    private static Path sampleFolder() {
        Path docs = Paths.get("..", "docs");
        try (Stream<Path> entries = Files.list(docs)) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> "1차어노테이션".equals(
                            Normalizer.normalize(p.getFileName().toString(), Normalizer.Form.NFC)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "표본 산출물 폴더를 찾을 수 없다. 이 시험은 실물에 고정돼 있다."))
                    .resolve("00000073");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
