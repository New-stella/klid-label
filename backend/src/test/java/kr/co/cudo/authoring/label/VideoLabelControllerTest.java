package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-196 {@code PUT /v1/videos/{rawSn}/labels} — <b>컨트롤러 계층</b> 통합 회귀.
 *
 * <h3>왜 서비스 단위 테스트로 대체할 수 없나</h3>
 * {@code VideoLabelSaveTxServiceTest} 는 Mockito 단위라 <b>{@code @PreAuthorize} · {@code @Valid} ·
 * JSON 바인딩 · {@code CustomException} → HTTP 상태코드 매핑</b>을 하나도 지나가지 않는다. 그 넷은
 * 이 경로의 실제 계약이고, 특히 인가 어노테이션이 빠지면 서비스 테스트는 전부 통과하는데 미인증
 * 사용자에게 열린다(fail-open).
 *
 * <p>API-195 조회 쪽 e2e({@code VersionControllerTest})와 <b>대칭</b>으로 둔다 — 2단계 흐름의 두 엔드포인트가
 * 같은 수준으로 덮여야 한쪽만 회귀하는 일이 없다.
 *
 * <h3>요청 스키마 세부에 강하게 결합하지 않는다</h3>
 * 확정 저장의 <b>요청 본문 형태는 변경 논의 중</b>이므로, 이 파일은 <b>권한·게이트·상태코드 축</b>을
 * 중심으로 단언한다. 본문 내용 검증(판번호 전수 검증·labelId 보존 등)은 서비스 단위 테스트와
 * {@code StartVersionRollbackReproIT} 가 담당한다.
 *
 * @design API-196
 * @req R6
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoLabelControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;
    private String workerNotAssignedToken;

    private Long rawSn;
    private Long srcSn;

    @BeforeEach
    void setUp() {
        reviewerToken          = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken    = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssignedToken = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-VIDLBL-" + System.nanoTime(), "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        srcSn = srcRepository.save(
                LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now())).getSrcSn();

        // 작업자 100 만 배정
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    /** 라벨 0건 + 판번호 0(신규 프레임의 초기값) — 스키마 세부에 의존하지 않는 최소 본문. */
    private String minimalBody() {
        return """
                {"frames":[{"srcSn":%d,"lblVer":0,"items":[]}]}""".formatted(srcSn);
    }

    @Test
    @DisplayName("PUT_videos_labels_토큰_없으면_401")
    void 토큰_없으면_401() throws Exception {
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT_videos_labels_미배정_WORKER_403 — 남의_영상을_확정할_수_없다")
    void 미배정_WORKER_403() throws Exception {
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + workerNotAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT_videos_labels_배정된_WORKER_는_확정_저장할_수_있다_200")
    void 배정된_WORKER_200() throws Exception {
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rawSn").value(rawSn))
                .andExpect(jsonPath("$.data.savedFrameCount").value(1));
    }

    @Test
    @DisplayName("PUT_videos_labels_REVIEWER_는_배정_없이도_확정_저장할_수_있다_200")
    void REVIEWER_200() throws Exception {
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalBody()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT_videos_labels_frames_가_비면_400 — 요청_검증이_실제로_돈다")
    void frames_가_비면_400() throws Exception {
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frames\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_videos_labels_필수값_누락은_400 — @Valid_가_실제로_배선돼_있다")
    void 필수값_누락은_400() throws Exception {
        // lblVer 누락 — DTO @NotNull 이 잡아야 한다(서비스까지 내려가면 NPE/500 이 된다).
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frames\":[{\"srcSn\":" + srcSn + ",\"items\":[]}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_videos_labels_loadedVersion_형식_위반은_400 — 감사_로그_인젝션_차단")
    void loadedVersion_형식_위반은_400() throws Exception {
        // 개행·자유 문자열은 감사 RSN 으로 흘러가면 로그 인젝션(CWE-117)이 된다.
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frames\":[{\"srcSn\":" + srcSn
                                + ",\"lblVer\":0,\"items\":[]}],\"loadedVersion\":\"3\\ninjected\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_videos_labels_그_영상에_속하지_않는_프레임은_404")
    void 소속이_아닌_프레임은_404() throws Exception {
        Long otherRawSn = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-VIDLBL-OTHER-" + System.nanoTime(), "CCTV-002", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/other.mp4", LocalDateTime.now(), 30)).getRawSn();
        Long otherSrcSn = srcRepository.save(
                LsDataSrc.create(otherRawSn, 0, "/raw/other0.jpg", LocalDateTime.now())).getSrcSn();

        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frames\":[{\"srcSn\":" + otherSrcSn
                                + ",\"lblVer\":0,\"items\":[]}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT_videos_labels_판번호가_어긋나면_409 — 영상_전체_미저장")
    void 판번호가_어긋나면_409() throws Exception {
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frames\":[{\"srcSn\":" + srcSn
                                + ",\"lblVer\":999,\"items\":[]}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PUT_videos_labels_비식별_신고_구간_영상은_412 — 작업락_유무와_무관")
    void 비식별_신고_구간은_412() throws Exception {
        // 신고 접수와 같은 표식(DE_IDNTF_YN='F') 을 세운다 — 게이트 판정의 단일 원천 컬럼이다.
        rawRepository.findById(rawSn).ifPresent(raw -> {
            raw.markDeidentified("F");
            rawRepository.saveAndFlush(raw);
        });

        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalBody()))
                .andExpect(status().isPreconditionFailed());
    }
}
