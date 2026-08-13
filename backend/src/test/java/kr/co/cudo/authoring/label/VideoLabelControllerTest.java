package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.repository.LsOutputVerSnpshRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 이 파일은 <b>권한·게이트·상태코드 축</b>을 중심으로 단언한다. 본문 동작(회차 스냅샷 적용·생산이력
 * 복원·labelId 보존)은 서비스 단위 테스트와 {@code StartVersionRollbackReproIT} 가 담당한다.
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
    @Autowired private LsLabelVersionRepository labelVersionRepository;
    @Autowired private LsOutputVerSnpshRepository outputVerSnpshRepository;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTxManager;

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

        // 확정 저장은 회차 스냅샷을 읽는다 — 회차 1 을 승인 스냅샷으로 심어 둔다.
        seedApprovedVersion(1);
    }

    /**
     * 최소 본문 — 회차 + 전 프레임 판번호(고친 것 없음).
     *
     * <p>영상에 프레임이 1장뿐이라 그 1장이 곧 전 프레임이다(커버리지 강제 통과). 판번호 0 은 신규
     * 프레임의 초기값이다.
     */
    private String minimalBody() {
        return """
                {"loadedVersion":1,"frameVersions":[{"srcSn":%d,"lblVer":0}]}""".formatted(srcSn);
    }

    /** 확정 저장이 읽을 회차 스냅샷 + V183 매핑을 심는다(회차 실재 대조·본문 적용의 전제). */
    private void seedApprovedVersion(int versionNo) {
        LsLabelVersion version = labelVersionRepository.save(LsLabelVersion.create(
                rawSn, srcSn, "a".repeat(40), "{\"srcSn\":" + srcSn + ",\"dscdYn\":\"N\",\"items\":[]}",
                versionNo, LsLabelVersion.SAVE_REASON_APPROVED, "1"));
        new TransactionTemplate(controlTxManager).executeWithoutResult(status ->
                outputVerSnpshRepository.recordActiveSnapshots(rawSn, versionNo, LsLabelVersion.ACTIVE_YES));
        // 실제 매핑이 심렸는지 확인 — 안 심기면 "회차를 알 수 없는 프레임" 경로로 흘러 테스트 의미가 바뀐다.
        org.assertj.core.api.Assertions.assertThat(version.getLabelVersionSn()).isNotNull();
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
                        .content("{\"loadedVersion\":1,\"frameVersions\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_videos_labels_필수값_누락은_400 — @Valid_가_실제로_배선돼_있다")
    void 필수값_누락은_400() throws Exception {
        // lblVer 누락 — DTO @NotNull 이 잡아야 한다(서비스까지 내려가면 NPE/500 이 된다).
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loadedVersion\":1,\"frameVersions\":[{\"srcSn\":" + srcSn + "}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_videos_labels_loadedVersion_이_문자열이면_400 — 감사_로그_인젝션_차단")
    void loadedVersion_이_문자열이면_400() throws Exception {
        // 자유 문자열이 감사 RSN 으로 흘러가면 로그 인젝션(CWE-117)이 된다 — 정수 타입으로 닫힌다.
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loadedVersion\":\"3\\ninjected\",\"frameVersions\":[{\"srcSn\":"
                                + srcSn + ",\"lblVer\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_videos_labels_그_영상에_없는_회차이면_400")
    void 없는_회차이면_400() throws Exception {
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loadedVersion\":99,\"frameVersions\":[{\"srcSn\":" + srcSn
                                + ",\"lblVer\":0}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT_videos_labels_전_프레임을_덮지_않으면_400")
    void 전_프레임을_덮지_않으면_400() throws Exception {
        // 프레임을 1장 더 만들어 영상이 2장이 되게 한다 — 1장만 보내면 커버리지 미달이다.
        srcRepository.save(LsDataSrc.create(rawSn, 1, "/raw/1.jpg", LocalDateTime.now()));

        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalBody()))
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
                        .content("{\"loadedVersion\":1,\"frameVersions\":[{\"srcSn\":" + otherSrcSn
                                + ",\"lblVer\":0}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT_videos_labels_판번호가_어긋나면_409 — 영상_전체_미저장")
    void 판번호가_어긋나면_409() throws Exception {
        mockMvc.perform(put("/v1/videos/" + rawSn + "/labels")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loadedVersion\":1,\"frameVersions\":[{\"srcSn\":" + srcSn
                                + ",\"lblVer\":999}]}"))
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
