package kr.co.cudo.authoring.notice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.notice.entity.LsNotice;
import kr.co.cudo.authoring.notice.repository.LsNoticeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시판(공지) 도메인 통합 테스트.
 * 권한 분기 · 발행 멱등 · 검색(LIKE 이스케이프) · 페이징 · 정렬 검증.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class NoticeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsNoticeRepository noticeRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;
    private String portalToken;

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "2", "WORKER", "INTERNAL", issuer, 60);
        portalToken = JwtTestSupport.token(secret, "3", "PORTAL_USER", "PORTAL", issuer, 60);
    }

    private ObjectNode createBody(String title, String content, boolean pinned) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        body.put("content", content);
        body.put("pinned", pinned);
        return body;
    }

    private long createNotice(String title, String content, boolean pinned) throws Exception {
        MvcResult res = mockMvc.perform(post("/v1/notices")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody(title, content, pinned))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private long createAndPublish(String title, String content, boolean pinned) throws Exception {
        long id = createNotice(title, content, pinned);
        mockMvc.perform(post("/v1/notices/" + id + "/publish")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    @DisplayName("REVIEWER가_공지_작성시_DRAFT로_생성됨")
    void reviewerCreateProducesDraft() throws Exception {
        mockMvc.perform(post("/v1/notices")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody("공지1", "본문", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pubStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.pubDt").doesNotExist());
    }

    @Test
    @DisplayName("WORKER가_공지_작성_시도시_403")
    void workerCreateForbidden() throws Exception {
        mockMvc.perform(post("/v1/notices")
                        .header("Authorization", "Bearer " + workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody("공지", "본문", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PORTAL_USER가_공지_목록_조회시_403")
    void portalUserListForbidden() throws Exception {
        mockMvc.perform(get("/v1/notices")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("WORKER_목록조회시_DRAFT는_제외되고_PUBLISHED만_반환")
    void workerListExcludesDraft() throws Exception {
        createNotice("초안공지", "draft body", false);
        createAndPublish("발행공지", "published body", false);

        mockMvc.perform(get("/v1/notices")
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].pubStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.content[0].title").value("발행공지"));
    }

    @Test
    @DisplayName("REVIEWER_목록조회시_DRAFT_포함_고정글_상단정렬")
    void reviewerListIncludesDraftAndPinnedFirst() throws Exception {
        createNotice("일반초안", "draft", false);
        createAndPublish("고정발행", "pinned published", true);

        mockMvc.perform(get("/v1/notices")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                // 고정글(pinned=true) 이 상단
                .andExpect(jsonPath("$.data.content[0].pinned").value(true))
                .andExpect(jsonPath("$.data.content[0].title").value("고정발행"));
    }

    @Test
    @DisplayName("WORKER가_DRAFT_상세조회시_404")
    void workerDraftDetailNotFound() throws Exception {
        long id = createNotice("초안상세", "draft body", false);

        mockMvc.perform(get("/v1/notices/" + id)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("발행_처리시_PBLCN_STTS_CD가_PUBLISHED이고_PBLCN_DT가_설정됨")
    void publishSetsStatusAndDate() throws Exception {
        long id = createNotice("발행대상", "body", false);

        mockMvc.perform(post("/v1/notices/" + id + "/publish")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pubStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.pubDt").exists());
    }

    @Test
    @DisplayName("이미_발행된_공지_재발행시_PBLCN_DT_불변")
    void republishKeepsPubDt() throws Exception {
        long id = createAndPublish("멱등발행", "body", false);
        LocalDateTimeHolder first = new LocalDateTimeHolder(
                noticeRepository.findById(id).orElseThrow().getPubDt());

        mockMvc.perform(post("/v1/notices/" + id + "/publish")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        LsNotice after = noticeRepository.findById(id).orElseThrow();
        assertThat(after.getPubDt()).isEqualTo(first.value);
        assertThat(after.isPublished()).isTrue();
    }

    @Test
    @DisplayName("제목_검색시_해당_키워드_포함_공지만_반환")
    void searchByTitleFiltersResults() throws Exception {
        createAndPublish("안전 점검 안내", "body1", false);
        createAndPublish("시스템 업데이트", "body2", false);

        mockMvc.perform(get("/v1/notices")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .param("field", "TITLE")
                        .param("keyword", "안전"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("안전 점검 안내"));
    }

    @Test
    @DisplayName("검색어에_퍼센트_포함시_와일드카드로_동작하지_않음")
    void percentKeywordIsEscaped() throws Exception {
        createAndPublish("정상제목", "body", false);

        // "%" 는 리터럴로 취급되어 어떤 제목과도 매칭되지 않아야 한다.
        mockMvc.perform(get("/v1/notices")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .param("field", "TITLE")
                        .param("keyword", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    @DisplayName("목록은_페이징되어_size_초과시_분할반환")
    void listIsPaged() throws Exception {
        for (int i = 0; i < 3; i++) {
            createAndPublish("페이징공지" + i, "body", false);
        }

        mockMvc.perform(get("/v1/notices")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    @DisplayName("존재하지_않는_공지_조회시_404")
    void getMissingNotFound() throws Exception {
        mockMvc.perform(get("/v1/notices/999999")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("잘못된_검색필드_지정시_400")
    void invalidSearchFieldRejected() throws Exception {
        mockMvc.perform(get("/v1/notices")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .param("field", "PASSWORD")
                        .param("keyword", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    /** PBLCN_DT 의 첫 발행 시각을 캡처해 멱등 비교에 사용하는 소형 홀더. */
    private static final class LocalDateTimeHolder {
        private final java.time.LocalDateTime value;

        private LocalDateTimeHolder(java.time.LocalDateTime value) {
            this.value = value;
        }
    }
}
