package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * I-1 회귀 — {@code GET /v1/videos?sort=capturedAt,desc} 정렬 키 매핑/폴백 검증.
 *
 * <p>FE 는 외부 정렬 키 {@code capturedAt} 을 보내지만 엔티티 실제 필드는 {@code shtDt}(촬영시각)다.
 * 이전에는 임의 프로퍼티가 Pageable 로 직행해 {@code PropertyReferenceException} → 500 으로 노출되었다.
 * 본 테스트는 (1) capturedAt 정렬이 200 + shtDt 매핑으로 동작하고 (2) allowlist 밖 임의 정렬 키도
 * 500 이 아니라 기본 정렬로 폴백되는 것을 보증한다 (CWE-20 입력 검증, fail-secure).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoListSortMappingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository rawDataStatusRepository;

    private final JdbcTemplate jdbc;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;

    VideoListSortMappingTest(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private LsDataRaw seedVideoWithShtDt(String clipId, LocalDateTime shtDt) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                shtDt, 30);
        return videoRepository.save(raw);
    }

    /**
     * 등록일을 명시적으로 고정한다 — {@code regDt} 는 엔티티 생성 시 {@code LocalDateTime.now()} 라
     * 한 테스트 안에서 적재한 행들이 마이크로초 단위로만 갈린다. 기본 정렬({@code regDt DESC})의 결과
     * 순서를 <b>단언</b>하려면 값이 결정적이어야 하므로 직접 덮어쓴다.
     */
    private void forceRegDt(Long rawSn, LocalDateTime regDt) {
        jdbc.update("UPDATE LS_DATA_RAW SET REG_DT = ? WHERE RAW_SN = ?", Timestamp.valueOf(regDt), rawSn);
    }

    @Test
    @DisplayName("sort_capturedAt_desc_요청시_500이_아니라_200_OK_shtDt_정렬로_동작")
    void capturedAtSortMapsToShtDtNo500() throws Exception {
        // given: shtDt 가 서로 다른 영상 2건 (regDt 는 동일 트랜잭션이라 거의 같음 → 보조키 shtDt 로 순서 결정)
        LsDataRaw older = seedVideoWithShtDt("CLIP-SORT-OLD", LocalDateTime.of(2026, 1, 1, 0, 0));
        LsDataRaw newer = seedVideoWithShtDt("CLIP-SORT-NEW", LocalDateTime.of(2026, 6, 1, 0, 0));

        // when / then: capturedAt → shtDt 매핑되어 200 + 최신 shtDt 영상이 먼저 노출 (500 금지)
        mockMvc.perform(get("/v1/videos?sort=capturedAt,desc&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(newer.getRawSn()))
                .andExpect(jsonPath("$.data.content[1].id").value(older.getRawSn()));
    }

    @Test
    @DisplayName("allowlist_밖_임의_정렬_키는_500이_아니라_기본_정렬로_폴백")
    void unknownSortKeyFallsBackNo500() throws Exception {
        // given: 영상 1건
        LsDataRaw v = seedVideoWithShtDt("CLIP-SORT-UNKNOWN", LocalDateTime.of(2026, 3, 1, 0, 0));

        // when / then: 존재하지 않는 프로퍼티로 정렬 요청 → PropertyReferenceException(500) 이 아니라 200
        mockMvc.perform(get("/v1/videos?sort=notAField,desc&page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(v.getRawSn()));
    }

    /**
     * 상한 초과 요청이 <b>실제로 기본 정렬 순서</b>를 돌려주는지 검증한다.
     *
     * <p>구 버전은 응답 앞 2건만 {@code containsExactlyInAnyOrder} 로 봤는데, 픽스처가
     * {@code LS_DATA_RAW} 를 전부 DELETE 하고 이 테스트가 2건만 적재하므로 <b>표에 행이 2개뿐</b>이라
     * 어떤 순서로 정렬돼도 통과했다 — {@code SortAllowlist.resolveLenient} 의 상한 체크를 삭제해도 GREEN.
     *
     * <p>그래서 ① 행을 3건 넣고 ② {@code regDt} 를 명시 고정해 <b>기본 정렬(regDt DESC)과 요청 정렬
     * (capturedAt=shtDt ASC)의 결과 순서가 완전히 역순</b>이 되게 배치한 뒤 ③ 순서를
     * {@code containsExactly} 로 단언한다. 상한 체크가 사라지면 {@code shtDt ASC, regDt ASC} 가
     * 실제 {@code ORDER BY} 로 전개되어 순서가 뒤집히므로 RED 가 된다.
     */
    @Test
    @DisplayName("대량_sort_파라미터_요청도_400이_아니라_200_기본정렬_순서로_폴백")
    void massiveSortParametersFallBackToDefaultSort() throws Exception {
        // given: 등록일 오름차순 = 촬영일 오름차순 (→ regDt DESC 와 shtDt ASC 결과가 정확히 역순)
        LsDataRaw first = seedVideoWithShtDt("CLIP-SORT-MANY-1", LocalDateTime.of(2026, 1, 2, 0, 0));
        LsDataRaw second = seedVideoWithShtDt("CLIP-SORT-MANY-2", LocalDateTime.of(2026, 3, 2, 0, 0));
        LsDataRaw third = seedVideoWithShtDt("CLIP-SORT-MANY-3", LocalDateTime.of(2026, 6, 2, 0, 0));
        forceRegDt(first.getRawSn(), LocalDateTime.of(2026, 5, 1, 0, 0));
        forceRegDt(second.getRawSn(), LocalDateTime.of(2026, 5, 2, 0, 0));
        forceRegDt(third.getRawSn(), LocalDateTime.of(2026, 5, 3, 0, 0));

        // allowlist 등록 키를 반복 전개한 대량 sort (CWE-770 시도) — 상한(고유 필드 4)을 크게 초과
        StringBuilder query = new StringBuilder("/v1/videos?page=0&size=20");
        for (int i = 0; i < 200; i++) {
            query.append(i % 2 == 0 ? "&sort=capturedAt,asc" : "&sort=regDt,asc");
        }

        // when: 400/500 이 아니라 200 이어야 한다(지금까지 200 이던 호출의 하위호환).
        String body = mockMvc.perform(get(query.toString())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();

        // then: 요청한 capturedAt ASC 가 한 항목도 ORDER BY 로 전개되지 않고 기본 정렬 regDt DESC 순서다.
        //   (상한 체크가 없으면 shtDt ASC 가 적용돼 first→second→third 로 뒤집힌다.)
        List<Number> ids = com.jayway.jsonpath.JsonPath.read(body, "$.data.content[*].id");
        assertThat(ids.stream().map(Number::longValue))
                .as("상한 초과 정렬은 전체가 기본 정렬(regDt DESC)로 폴백되어야 한다")
                .containsExactly(third.getRawSn(), second.getRawSn(), first.getRawSn());
    }

    @Test
    @DisplayName("정렬_미지정시_기본_regDt_DESC_정렬_회귀_보장")
    void noSortDefaultRegDtDesc() throws Exception {
        seedVideoWithShtDt("CLIP-SORT-A", LocalDateTime.of(2026, 2, 1, 0, 0));
        seedVideoWithShtDt("CLIP-SORT-B", LocalDateTime.of(2026, 2, 2, 0, 0));

        mockMvc.perform(get("/v1/videos?page=0&size=20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    // ------------------------------------------------------------------
    // 검수 완료 시각(reviewCompletedAt) 정렬 — 대시보드 "최근 완료 영상" 카드
    // ------------------------------------------------------------------

    /**
     * 검수 승인 상태 row 를 만들고 검수 완료 시각({@code LS_RAW_DATA_STATUS.UPD_DT})을 고정한다.
     *
     * <p>{@code transitionTo} 는 {@code updDt} 를 {@code now()} 로 찍기 때문에 한 테스트 안에서 만든
     * 행들이 마이크로초 단위로만 갈린다 — 정렬 <b>순서</b>를 단언하려면 값이 결정적이어야 하므로
     * {@link #forceRegDt} 와 동일하게 직접 덮어쓴다.
     */
    private void approveAt(Long rawSn, LocalDateTime approvedAt) {
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(LsRawDataStatus.STTS_APPROVED);
        rawDataStatusRepository.save(stts);
        jdbc.update("UPDATE LS_RAW_DATA_STATUS SET UPD_DT = ? WHERE RAW_DATA_ID = ?",
                Timestamp.valueOf(approvedAt), rawSn);
    }

    /**
     * 적재 순서(regDt)와 검수 완료 순서(reviewCompletedAt)가 <b>정확히 역순</b>인 3건을 심는다.
     *
     * <p>두 축이 역순이어야 "정렬 키가 실제로 검수 완료 시각을 탔는가"를 단언으로 구분할 수 있다 —
     * 같은 방향이면 구 동작({@code ORDER BY v.regDt DESC} 하드코딩)에서도 통과해 테스트가 무효다.
     *
     * @return {@code [regDt 최신, 중간, regDt 최구]} = {@code [승인 최구, 중간, 승인 최신]}
     */
    private List<LsDataRaw> seedApprovedWithReversedAxes(String prefix) {
        LsDataRaw newestReg = seedVideoWithShtDt(prefix + "-1", LocalDateTime.of(2026, 1, 1, 0, 0));
        LsDataRaw middle = seedVideoWithShtDt(prefix + "-2", LocalDateTime.of(2026, 1, 2, 0, 0));
        LsDataRaw oldestReg = seedVideoWithShtDt(prefix + "-3", LocalDateTime.of(2026, 1, 3, 0, 0));
        forceRegDt(newestReg.getRawSn(), LocalDateTime.of(2026, 5, 3, 0, 0));
        forceRegDt(middle.getRawSn(), LocalDateTime.of(2026, 5, 2, 0, 0));
        forceRegDt(oldestReg.getRawSn(), LocalDateTime.of(2026, 5, 1, 0, 0));
        approveAt(newestReg.getRawSn(), LocalDateTime.of(2026, 6, 1, 0, 0));
        approveAt(middle.getRawSn(), LocalDateTime.of(2026, 6, 2, 0, 0));
        approveAt(oldestReg.getRawSn(), LocalDateTime.of(2026, 6, 3, 0, 0));
        return List.of(newestReg, middle, oldestReg);
    }

    private List<Long> responseIds(String query) throws Exception {
        String body = mockMvc.perform(get(query)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = com.jayway.jsonpath.JsonPath.read(body, "$.data.content[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    @Test
    @DisplayName("검수완료_영상_목록을_검수완료시각_내림차순으로_정렬한다")
    void approvedListSortsByReviewCompletedAtDesc() throws Exception {
        // given: 적재 순서와 승인 순서가 역순인 검수완료 3건
        List<LsDataRaw> seeded = seedApprovedWithReversedAxes("CLIP-RVDT");

        // when: 대시보드 "최근 완료 영상" 카드와 동일한 요청
        List<Long> ids = responseIds(
                "/v1/videos?reviewStatusCd=APPROVED&sort=reviewCompletedAt,desc&page=0&size=20");

        // then: 검수 완료 시각 최신순 = 적재 순서의 정확한 역순
        //   (JPQL 에 ORDER BY v.regDt DESC 가 남아 있으면 순서가 뒤집혀 RED)
        assertThat(ids)
                .as("검수 완료 시각 내림차순이어야 한다")
                .containsExactly(seeded.get(2).getRawSn(), seeded.get(1).getRawSn(), seeded.get(0).getRawSn());
    }

    @Test
    @DisplayName("정렬을_지정하지_않으면_기존대로_수신시각_내림차순이다")
    void approvedListDefaultSortStaysRegDtDesc() throws Exception {
        // given: 위와 동일 픽스처 (적재 순서 ↔ 승인 순서 역순)
        List<LsDataRaw> seeded = seedApprovedWithReversedAxes("CLIP-RVDF");

        // when: 정렬 미지정 — 영상 처리 현황·증강 요청 화면의 기존 호출 형태
        List<Long> ids = responseIds("/v1/videos?reviewStatusCd=APPROVED&page=0&size=20");

        // then: BE 기본 정렬은 불변(regDt DESC) — 검수 완료 시각 정렬로 바뀌면 안 된다
        assertThat(ids)
                .as("정렬 미지정 시 기본 정렬(regDt DESC)이 유지되어야 한다")
                .containsExactly(seeded.get(0).getRawSn(), seeded.get(1).getRawSn(), seeded.get(2).getRawSn());
    }

    @Test
    @DisplayName("미등록_정렬키는_400이_아니라_기본정렬로_폴백한다")
    void approvedListUnknownSortKeyFallsBack() throws Exception {
        // given
        List<LsDataRaw> seeded = seedApprovedWithReversedAxes("CLIP-RVDU");

        // when: 미등록 키 (lenient 규약 — 이 엔드포인트는 변경 전에도 200 이었다)
        List<Long> ids = responseIds(
                "/v1/videos?reviewStatusCd=APPROVED&sort=notAField,desc&page=0&size=20");

        // then: 400/500 이 아니라 200 + 기본 정렬 폴백
        assertThat(ids).containsExactly(
                seeded.get(0).getRawSn(), seeded.get(1).getRawSn(), seeded.get(2).getRawSn());
    }

    @Test
    @DisplayName("검수상태_필터_없이_검수완료시각_정렬을_요청해도_500이_아니라_기본정렬로_폴백한다")
    void reviewCompletedSortWithoutJoinFallsBack() throws Exception {
        // given: 검수 상태 필터가 없으면 LS_RAW_DATA_STATUS 조인 쿼리를 타지 않는다
        //        (조인 alias 를 참조하는 정렬 키가 그대로 흘러가면 PropertyReferenceException → 500)
        List<LsDataRaw> seeded = seedApprovedWithReversedAxes("CLIP-RVDN");

        // when
        List<Long> ids = responseIds("/v1/videos?sort=reviewCompletedAt,desc&page=0&size=20");

        // then: 해당 키는 drop 되고 기본 정렬(regDt DESC)로 폴백된다
        assertThat(ids).containsExactly(
                seeded.get(0).getRawSn(), seeded.get(1).getRawSn(), seeded.get(2).getRawSn());
    }
}
