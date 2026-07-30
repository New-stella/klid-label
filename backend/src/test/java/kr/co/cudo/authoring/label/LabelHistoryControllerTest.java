package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.service.TrackEditService;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — 라벨 변경 이력 기록/조회 통합 테스트 (V112 마이그레이션 위 실 PostgreSQL).
 *
 * <p>V114 재구조화: "라벨 1건=1행" → "저장 이벤트=1행 + 종류별 건수". 한 번의 bulkUpsert 는
 * 라벨 N건이어도 저장 이벤트 1건(addCnt/mdfcnCnt 집계)으로 기록된다.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>저장 경로(PUT /v1/frames/{srcSn}/labels)에서 저장 이벤트 원자 기록(HIGH #1).</li>
 *   <li>조회 API(GET /v1/frames/{srcSn}/label-history) 최신순 페이징(HIGH #9).</li>
 *   <li>IDOR — 타인 배정 프레임 히스토리 조회 403(HIGH #8).</li>
 *   <li>페이지 크기 기본 20 / 상한 100 클램프(CWE-770).</li>
 *   <li>트랙 삭제 → 프레임 단위 DELETED 저장 이벤트(delCnt) 노출.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class LabelHistoryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager controlTxManager;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private LsDataLblHstryRepository historyRepository;
    @Autowired private TrackEditService trackEditService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerAssignedToken;     // user 100 - assigned
    private String workerNotAssignedToken;  // user 101 - NOT assigned

    private Long srcSn;
    private Long rawSn;

    @BeforeEach
    void setup() {
        reviewerToken          = JwtTestSupport.token(secret, "1",   "REVIEWER", "INTERNAL", issuer, 60);
        workerAssignedToken    = JwtTestSupport.token(secret, "100", "WORKER",   "INTERNAL", issuer, 60);
        workerNotAssignedToken = JwtTestSupport.token(secret, "101", "WORKER",   "INTERNAL", issuer, 60);

        // ★ 부모 영상만 <b>커밋</b> 시드 — 라벨 저장/트랙 삭제가 작업락을 REQUIRES_NEW 로 쓰므로
        //   (LS_AUTH_WORK_LOCK) ambient 미커밋 영상은 그 트랜잭션에서 보이지 않아 V146 FK 검증에 걸린다.
        rawSn = RawVideoFixture.newRawCommitted(controlTxManager, jdbcTemplate);

        LsDataSrc src = LsDataSrc.create(rawSn, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        src = srcRepository.save(src);
        srcSn = src.getSrcSn();

        // 작업자 100 만 배정 (작업자 101 은 미배정 — IDOR 차단 검증용)
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    @AfterEach
    void tearDown() {
        // ambient(롤백) 트랜잭션을 먼저 닫아야 미커밋 자식의 FK 키공유 잠금이 풀려 부모 삭제가 가능하다.
        RawVideoFixture.endAmbientTransaction();
        RawVideoFixture.deleteRaws(jdbcTemplate, rawSn);
    }

    private LabelBulkUpsertRequest newBboxRequest(Long id, String label) {
        return new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(id, "BBOX", null, label,
                        List.of(List.of(10.0, 10.0), List.of(50.0, 50.0)), null)));
    }

    @Test
    @DisplayName("라벨_신규저장시_ADDED_저장이벤트가_기록된다")
    void addedHistoryOnCreate() throws Exception {
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newBboxRequest(null, "person"))))
                .andExpect(status().isOk());

        List<LsDataLblHstry> hist = historyRepository.findBySrcSnOrderByRegDtDesc(srcSn);
        assertThat(hist).hasSize(1);
        assertThat(hist.get(0).getAddCnt()).isEqualTo(1);
        assertThat(hist.get(0).getMdfcnCnt()).isEqualTo(0);
        assertThat(hist.get(0).getRegId()).isEqualTo("100");
        assertThat(hist.get(0).getRegDt()).isNotNull();
        assertThat(hist.get(0).getChgDtlCn()).contains("ADDED");
    }

    @Test
    @DisplayName("라벨_추가수정은_단일_저장이벤트로_묶인다")
    void updatedHistoryOnEdit() throws Exception {
        LsDataLbl seed = labelRepository.save(LsDataLbl.createAutoBbox(srcSn, null, "car",
                "[[5.0,5.0],[40.0,40.0]]", new BigDecimal("0.9000"), null));

        // 기존 라벨 수정 1건 + 신규 라벨 1건을 한 요청으로 저장 → 단일 저장 이벤트(add=1, mdfcn=1).
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(seed.getLblSn(), "BBOX", null, "car",
                        List.of(List.of(10.0, 10.0), List.of(50.0, 50.0)), null),
                new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null)));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        List<LsDataLblHstry> hist = historyRepository.findBySrcSnOrderByRegDtDesc(srcSn);
        assertThat(hist).hasSize(1);
        assertThat(hist.get(0).getMdfcnCnt()).isEqualTo(1);
        assertThat(hist.get(0).getAddCnt()).isEqualTo(1);
        assertThat(hist.get(0).getRegId()).isEqualTo("100");
    }

    @Test
    @DisplayName("히스토리_기록은_저장과_동일_트랜잭션으로_원자적이다")
    void historyAtomicWithSave() throws Exception {
        // 유효 신규 라벨 + 음수좌표 라벨을 함께 전송 → 검증 실패로 400. 저장·이력 모두 롤백(부분기록 없음).
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", null, "valid",
                        List.of(List.of(10.0, 10.0), List.of(50.0, 50.0)), null),
                new LabelItemDto(null, "BBOX", null, "invalid",
                        List.of(List.of(-1.0, 10.0), List.of(50.0, 50.0)), null)));

        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        // 저장 실패 → 라벨도 이력도 남지 않음 (동일 트랜잭션 원자성).
        assertThat(labelRepository.findBySrcSn(srcSn)).isEmpty();
        assertThat(historyRepository.findBySrcSnOrderByRegDtDesc(srcSn)).isEmpty();
    }

    @Test
    @DisplayName("히스토리_조회는_최신순_페이징으로_반환한다")
    void getHistoryReturnsLatestFirst() throws Exception {
        // 3건 신규를 한 요청으로 저장 → 저장 이벤트 1건(addCnt=3)
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", null, "a", List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null),
                new LabelItemDto(null, "BBOX", null, "b", List.of(List.of(3.0, 3.0), List.of(4.0, 4.0)), null),
                new LabelItemDto(null, "BBOX", null, "c", List.of(List.of(5.0, 5.0), List.of(6.0, 6.0)), null)));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/frames/" + srcSn + "/label-history")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].addCnt").value(3))
                .andExpect(jsonPath("$.data.content[0].actor").value("100"))
                // 최신순 — LBL_HSTRY_SN DESC tiebreaker 로 첫 원소 PK 가 가장 큼
                .andExpect(jsonPath("$.data.content[0].lblHstrySn")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("타인_프레임_히스토리_조회시_403")
    void idorForbidden() throws Exception {
        mockMvc.perform(get("/v1/frames/" + srcSn + "/label-history")
                        .header("Authorization", "Bearer " + workerNotAssignedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("히스토리_조회_페이징_기본size_20_최대100")
    void pagingDefaultAndClamp() throws Exception {
        // 기본 size=20
        mockMvc.perform(get("/v1/frames/" + srcSn + "/label-history")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(20));

        // size=500 요청 시 100 으로 클램프 (CWE-770)
        mockMvc.perform(get("/v1/frames/" + srcSn + "/label-history")
                        .queryParam("size", "500")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @DisplayName("트랙삭제시_DELETED_저장이벤트가_조회API에_노출된다")
    void deletedHistoryExposedThroughApi() throws Exception {
        // given — 라벨 저장(ADDED 이벤트) 1건(트랙 없음) + 트랙 라벨(seed) 1건 직접 저장(이벤트 미기록).
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newBboxRequest(null, "person"))))
                .andExpect(status().isOk());
        labelRepository.save(LsDataLbl.createAutoBbox(srcSn, null, "car", "[]", BigDecimal.ZERO, "5"));

        // when — 트랙 "5" 삭제 (WORKER 100 본인 배정). 삭제 감사 이벤트가 실제로 기록되어야 한다.
        TokenClaims worker100 = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
        trackEditService.deleteTrackFrom(rawSn, "5", 0, worker100);

        // then — ADDED 이벤트 + DELETED 이벤트 = 2건. content[0] 은 최신(DELETED, delCnt=1, actor=100).
        mockMvc.perform(get("/v1/frames/" + srcSn + "/label-history")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].delCnt").value(1))
                .andExpect(jsonPath("$.data.content[0].actor").value("100"));
    }

    @Test
    @DisplayName("존재하지않는_라벨ID_수정요청시_신규라벨_ADDED로_처리된다")
    void nonexistentIdTreatedAsNewAdded() throws Exception {
        // id=999999 는 현재 프레임에 없는 라벨 → else 분기(신규 생성 + ADDED). (dead FORBIDDEN 방어 제거 후 동작 실증)
        long ghostId = 999_999L;
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newBboxRequest(ghostId, "person"))))
                .andExpect(status().isOk());

        // 신규 라벨이 생성되고(요청 id 재사용 아님) ADDED 저장 이벤트 1건.
        List<LsDataLbl> saved = labelRepository.findBySrcSn(srcSn);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getLblSn()).isNotEqualTo(ghostId);
        List<LsDataLblHstry> hist = historyRepository.findBySrcSnOrderByRegDtDesc(srcSn);
        assertThat(hist).hasSize(1);
        assertThat(hist.get(0).getAddCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("히스토리조회_임의_sort파라미터는_무시되고_최신순_고정정렬된다")
    void arbitrarySortParamIgnoredNoServerError() throws Exception {
        // 2건을 한 요청으로 저장 → 저장 이벤트 1건(addCnt=2).
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", null, "a", List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null),
                new LabelItemDto(null, "BBOX", null, "b", List.of(List.of(3.0, 3.0), List.of(4.0, 4.0)), null)));
        mockMvc.perform(put("/v1/frames/" + srcSn + "/labels")
                        .header("Authorization", "Bearer " + workerAssignedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // 매핑 불가한 임의 sort 필드 → 예전엔 PropertyReferenceException(500). 이제 서버 고정 정렬로 무시 → 200 + 최신순.
        mockMvc.perform(get("/v1/frames/" + srcSn + "/label-history")
                        .queryParam("sort", "notAField,asc")
                        .header("Authorization", "Bearer " + workerAssignedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].addCnt").value(2))
                .andExpect(jsonPath("$.data.content[0].lblHstrySn")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
    }
}
