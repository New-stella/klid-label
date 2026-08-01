package kr.co.cudo.authoring.augment;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import kr.co.cudo.authoring.augment.config.AugmentDiscardProperties;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 — 증강 결과 조회({@code GET /v1/augments/{jobId}/result})의 <b>폐기 축 노출</b>(작업 A)과
 * <b>항목 축 "센 뒤 드롭" 해소</b>(작업 B) 통합 검증.
 *
 * <h2>작업 A — 폐기 축</h2>
 * <p>REVIEWER 가 반려한 증강 결과는 {@code LS_DATA_AUG_DSCD} 에 표식이 남고 유예 경과 후 배치가
 * 실삭제한다. 그런데 <b>그 정보를 내려주는 GET 이 하나도 없어</b> 화면이 복구 버튼도 유예 안내도 만들 수
 * 없었다. 판정 분기표 자체는 {@code AugmentDiscardStateMapperTest} 가 단위로 고정하고, 여기서는
 * <b>배선</b>(배치 조회 · 응답 직렬화 · {@code resultState} 최우선 판정 · 경로 미노출)을 실 DB 로 고정한다.
 *
 * <h2>작업 B — 총량과 실제 항목의 집합 일치</h2>
 * <p>구 구현은 {@code itemTotal} 을 확정한 <b>뒤</b> 페이지 슬라이스 루프에서 해상도 파생 항목만
 * {@code continue} 로 드롭해, {@code totalElements} 가 실제 {@code results} 합계보다 컸다(FE 페이저가
 * 존재하지 않는 페이지를 그렸다). 드롭 술어를 항목 구성 시점으로 옮겨 두 값이 <b>같은 집합</b>에서
 * 나오게 한다. ⚠ 외부 위탁 항목은 신고 구간이어도 <b>항목은 남기고 이미지만 뺀다</b> — 이 비대칭은
 * 의도된 동작이라 "일관성" 을 이유로 통일하지 않는다.
 *
 * <h2>테스트 위생</h2>
 * <p>공유 PostgreSQL(Testcontainers) 오염 방지 — 시드 clipId 는 {@code AUGDSC-} 고유 접두사를 쓰고,
 * {@code @AfterEach} 가 <b>자기 시드 PK 만</b> FK 역순으로 정리한다. 이 클래스는 라벨 마스터
 * ({@code LS_LABEL}·{@code LS_LABEL_ATTR})를 <b>시드하지도 삭제하지도 않는다</b>(전 프로젝트 공유 자산).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentResultDiscardStateTest {

    /** 폐기 원장에만 적재되는 NAS 실경로 — 응답 어디에도 새어나가면 안 된다(CWE-209/359). */
    private static final String SECRET_VIDEO_PATH = "/nas-storage/deid/videos/augment/secret-augdsc.mp4";
    /** 반려 사유 — 이미 {@code rejectReason} 으로 나가는 축이라 폐기 축에 중복해서 싣지 않는다. */
    private static final String DISCARD_REASON = "겨울 질감이 부자연스럽다";
    /**
     * N+1 가드 전용 — 표식의 <b>대상</b>이 무관한 자리에 쓰는 placeholder rawSn.
     * 폐기 원장은 비석이라 FK 가 없어(V156) 실재하지 않는 값을 넣어도 무결성이 깨지지 않는다.
     */
    private static final long UNRELATED_RAW_SN = 999_999_999L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugDscdRepository discardRepository;
    @Autowired private LsDataAugRvwRepository reviewRepository;
    @Autowired private AugmentDiscardProperties discardProperties;

    @Qualifier("controlEntityManagerFactory")
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private final JdbcTemplate jdbc;

    /** 시드 정리 대상 — 삽입 순서대로 모아 <b>역순</b>으로 지운다(파생 → 부모, ORGNL_RAW_SN 은 비-CASCADE). */
    private final List<Long> rawSns = new ArrayList<>();
    private final List<Long> dataAugSns = new ArrayList<>();

    private String reviewerToken;

    AugmentResultDiscardStateTest(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    /**
     * 자기 시드만 FK 역순으로 되돌린다.
     *
     * <p>{@code LS_DATA_AUG} 는 {@code SRC_SN} 에 FK 가 없어 RAW CASCADE(V146)로 정리되지 않고,
     * {@code LS_DATA_AUG_DSCD} 는 <b>비석</b>이라 아예 FK 가 없다 — 명시 삭제하지 않으면 조용히 고아가
     * 남는다. {@code LS_DATA_RAW.ORGNL_RAW_SN} 도 self-reference 라 CASCADE 대상이 아니므로 파생을 먼저
     * 지운다(삽입 역순).
     */
    @AfterEach
    void cleanup() {
        for (Long dataAugSn : dataAugSns) {
            jdbc.update("DELETE FROM LS_DATA_AUG_DSCD WHERE DATA_AUG_SN = ?", dataAugSn);
            jdbc.update("DELETE FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = ?", dataAugSn);
            jdbc.update("DELETE FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", dataAugSn);
        }
        for (int i = rawSns.size() - 1; i >= 0; i--) {
            Long rawSn = rawSns.get(i);
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
        dataAugSns.clear();
        rawSns.clear();
    }

    // ============================================================
    // 시드 헬퍼
    // ============================================================

    private LsDataRaw seedParent(String suffix) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGDSC-" + suffix + "-" + UUID.randomUUID(), "CCTV-AUGDSC-" + suffix, "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/videos/AUGDSC-" + suffix + "-orig.mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        LsDataRaw saved = videoRepository.save(parent);
        rawSns.add(saved.getRawSn());
        return saved;
    }

    private LsDataSrc seedParentFrame(Long rawSn, long frameNo, String suffix) {
        LsDataSrc f = LsDataSrc.create(rawSn, frameNo, frameNo,
                "/nas/frames/raw/" + suffix + "/f" + frameNo + ".jpg", LocalDateTime.now());
        f.attachDeidPath("/nas/frames/deid/" + suffix + "/f" + frameNo + ".jpg");
        return srcRepository.save(f);
    }

    private LsDataSrc seedDerivativeFrame(Long rawSn, long frameNo, String suffix) {
        return srcRepository.save(LsDataSrc.create(rawSn, frameNo, frameNo, null,
                "/nas/frames/deid/" + suffix + "/r" + frameNo + ".jpg", LocalDateTime.now()));
    }

    /** 생성이 성공한(결과물 실재) 외부 위탁 증강 행. */
    private LsDataAug seedGeneratedExternalAug(Long srcSn, String augType) {
        LsDataAug aug = augRepository.save(LsDataAug.createPending(srcSn, augType,
                new BigDecimal("90.00"), "system"));
        aug.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        LsDataAug saved = augRepository.saveAndFlush(aug);
        dataAugSns.add(saved.getDataAugSn());
        return saved;
    }

    /** 외부 위탁 파생 영상 + {@code NEW_RAW_SN} 매핑(생성 트랜잭션과 동일 형상). */
    private LsDataRaw seedExternalDerivative(LsDataRaw parent, LsDataAug aug, String deIdntfYn) {
        LsDataRaw d = LsDataRaw.createFromAugment(parent,
                "/nas/videos/" + parent.getRawSn() + "/" + aug.getAugTypeCd() + ".mp4",
                aug.getAugTypeCd(), aug.getDataAugSn());
        d.markDeidentified(deIdntfYn);
        d.markCompleted();
        LsDataRaw saved = videoRepository.save(d);
        rawSns.add(saved.getRawSn());
        aug.assignDerivativeRawSn(saved.getRawSn());
        augRepository.saveAndFlush(aug);
        return saved;
    }

    /**
     * <b>생성이 영구 실패</b>(dead-letter)로 종결된 외부 위탁 증강 행 — 검수 행도 폐기 표식도 없다.
     *
     * <p>이 형상이 복구 가시성 결함의 핵심 케이스다: {@code decision} 은 {@code REJECTED} 로 나가지만
     * (실패를 실패로 보이기 위해) 복구 API 는 <b>되돌릴 결정이 없어</b> 404 를 낸다.
     */
    private LsDataAug seedDeadLetterExternalAug(Long srcSn, String augType) {
        LsDataAug aug = augRepository.save(LsDataAug.createPending(srcSn, augType,
                new BigDecimal("90.00"), "system"));
        aug.applyGenerationResult(LsDataAug.STTS_REJECTED);
        aug.markDeadLetter();
        LsDataAug saved = augRepository.saveAndFlush(aug);
        dataAugSns.add(saved.getDataAugSn());
        return saved;
    }

    /** 사람이 반려한 검수 행(REJECTED) — 폐기 표식과 <b>독립</b>으로 심는다. */
    private void seedRejectedReview(LsDataAug aug, LsDataRaw parent, LsDataSrc parentFrame) {
        reviewRepository.saveAndFlush(LsDataAugRvw.createRejected(aug.getDataAugSn(),
                parent.getRawSn(), parentFrame.getSrcSn(), DISCARD_REASON, "1",
                LocalDateTime.now().minusDays(1)));
    }

    private LsDataAug seedResolutionAug(Long srcSn, String presetCd) {
        LsDataAug aug = augRepository.saveAndFlush(
                LsDataAug.createResolutionAccepted(srcSn, presetCd, "rev1"));
        dataAugSns.add(aug.getDataAugSn());
        return aug;
    }

    private LsDataRaw seedResolutionDerivative(LsDataRaw parent, String presetCd,
                                               String deIdntfYn, String clipId) {
        LsDataRaw d = LsDataRaw.createFromResolution(parent,
                "/nas/videos/" + parent.getRawSn() + "/" + presetCd + ".mp4", presetCd);
        if (clipId != null) {
            ReflectionTestUtils.setField(d, "vmsClipId", clipId);
        }
        d.markDeidentified(deIdntfYn);
        d.markCompleted();
        LsDataRaw saved = videoRepository.save(d);
        rawSns.add(saved.getRawSn());
        return saved;
    }

    /**
     * <b>종류를 판별할 수 없는</b> 확정 해상도 파생 — 항목 드롭 경로 재현용.
     *
     * <p>판별축이 {@code VMS_CLIP_ID} 마커에서 {@code LS_DATA_RAW.AUG_TYPE_CD} 컬럼으로 바뀐 뒤
     * (V148 신설 + V149 백필) clipId 를 아무리 망가뜨려도 판별은 흔들리지 않는다. 실제로 남아 있는
     * 드롭 조건은 <b>컬럼이 비어 있는 과거 파생</b>(백필이 마커를 해석하지 못해 NULL 로 남긴 행)이므로
     * 그 형상을 재현한다. clipId 도 함께 어긋나게 둬서 "clipId 는 더 이상 구원 수단이 아니다" 를 고정한다.
     */
    private LsDataRaw seedTypeUnresolvedResolutionDerivative(LsDataRaw parent, String presetCd,
                                                             String clipId) {
        LsDataRaw d = LsDataRaw.createFromResolution(parent,
                "/nas/videos/" + parent.getRawSn() + "/" + presetCd + ".mp4", presetCd);
        ReflectionTestUtils.setField(d, "vmsClipId", clipId);
        ReflectionTestUtils.setField(d, "augTypeCd", null);
        d.markDeidentified("Y");
        d.markCompleted();
        LsDataRaw saved = videoRepository.save(d);
        rawSns.add(saved.getRawSn());
        return saved;
    }

    /** 폐기 표식(열린 상태) 기록. */
    private LsDataAugDscd seedDiscardMark(LsDataAug aug, LsDataRaw derivative, LocalDateTime at) {
        LsDataAugDscd row = LsDataAugDscd.mark(aug.getDataAugSn(), derivative.getRawSn(),
                derivative.getOrgnlRawSn(), DISCARD_REASON, "1", at,
                aug.getAugTypeCd(), aug.getPromptCn());
        row.recordVideoPath(SECRET_VIDEO_PATH);
        return discardRepository.saveAndFlush(row);
    }

    private void markRestored(LsDataAugDscd row) {
        jdbc.update("UPDATE LS_DATA_AUG_DSCD SET RSTR_DT = CURRENT_TIMESTAMP, RSTR_RSN = '오판' "
                + "WHERE DATA_AUG_DSCD_SN = ?", row.getDataAugDscdSn());
    }

    private void markDbDeleted(LsDataAugDscd row) {
        jdbc.update("UPDATE LS_DATA_AUG_DSCD SET DEL_DT = CURRENT_TIMESTAMP "
                + "WHERE DATA_AUG_DSCD_SN = ?", row.getDataAugDscdSn());
    }

    private void markPurgeClaimed(LsDataAugDscd row) {
        jdbc.update("UPDATE LS_DATA_AUG_DSCD SET DEL_PRCS_DT = CURRENT_TIMESTAMP "
                + "WHERE DATA_AUG_DSCD_SN = ?", row.getDataAugDscdSn());
    }

    /** 부모 + 프레임 1장 + 생성 성공 외부 증강 + 파생(프레임 1장) 완전 시드. */
    private record ExtFixture(LsDataRaw parent, LsDataSrc parentFrame, LsDataAug aug, LsDataRaw derivative) { }

    private ExtFixture seedExternal(String suffix, String augType, String derivativeDeIdntfYn) {
        LsDataRaw parent = seedParent(suffix);
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, suffix);
        LsDataAug aug = seedGeneratedExternalAug(pf.getSrcSn(), augType);
        LsDataRaw derivative = seedExternalDerivative(parent, aug, derivativeDeIdntfYn);
        seedDerivativeFrame(derivative.getRawSn(), 0L, suffix);
        return new ExtFixture(parent, pf, aug, derivative);
    }

    private String resultJson(Long jobId) throws Exception {
        return mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ============================================================
    // 작업 A — 폐기 축 노출 (배선)
    // ============================================================

    @Test
    @DisplayName("반려된_외부증강_항목은_폐기시각과_실삭제예정일시를_내려준다")
    void discardedItemCarriesDiscardAxis() throws Exception {
        // given
        ExtFixture fx = seedExternal("MARKED", LsDataAug.AUG_WINTER, "Y");
        seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(1));

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].discard").exists())
                .andExpect(jsonPath("$.data.results[0].discard.discardedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].discard.purgeAt").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].discard.purged").value(false))
                .andExpect(jsonPath("$.data.results[0].discard.restorable").value(true));
    }

    @Test
    @DisplayName("복구된_항목은_폐기축이_null_이다")
    void restoredItemHasNoDiscardAxis() throws Exception {
        // given — 반려 표식을 찍고 유예 내 복구. 행은 남고 RSTR_DT 만 찍힌다.
        ExtFixture fx = seedExternal("RESTORED", LsDataAug.AUG_NIGHT, "Y");
        markRestored(seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(2)));

        // when / then — 지금은 폐기 상태가 아니다("곧 삭제됨" 오표시 금지).
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].discard").doesNotExist());
    }

    @Test
    @DisplayName("반려_복구_재반려_이력이_쌓여도_최신_열린행_기준으로_판정한다")
    void latestOpenMarkWins() throws Exception {
        // given — 오래된 폐기(복구됨) 1행 + 최신 폐기(열림) 1행
        ExtFixture fx = seedExternal("REMARK", LsDataAug.AUG_RAIN, "Y");
        markRestored(seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(30)));
        LocalDateTime remarkedAt = LocalDateTime.now().minusDays(1).withNano(0);
        seedDiscardMark(fx.aug(), fx.derivative(), remarkedAt);

        // when
        String json = resultJson(fx.parent().getRawSn());

        // then — 최신(열린) 행의 시각이어야 한다. 30일 전 표식을 보면 "이미 삭제됐어야 함" 이 된다.
        String discardedAt = JsonPath.read(json, "$.data.results[0].discard.discardedAt");
        assertThat(LocalDateTime.parse(discardedAt))
                .as("최신 폐기 표식의 시각으로 판정해야 한다")
                .isAfter(LocalDateTime.now().minusDays(2));
        assertThat((Boolean) JsonPath.read(json, "$.data.results[0].discard.purged")).isFalse();
        assertThat((Boolean) JsonPath.read(json, "$.data.results[0].discard.restorable")).isTrue();
    }

    /**
     * 실삭제된 항목의 <b>전 필드가 한 방향으로 맞춰진다</b> — 응답 자기모순 금지.
     *
     * <h3>왜 {@code resultState} 만 보면 안 되는가 (DEV_FIX MEDIUM ②③④)</h3>
     * <p>실삭제 스윕은 한 트랜잭션에서 {@code LS_DATA_AUG_RVW} → {@code LS_DATA_AUG} 를 지우고 비석에
     * {@code DEL_DT} 를 찍는다. 조회가 증강 스냅샷을 잡은 뒤 그 커밋이 끼어들면 <b>검수 행만 사라진
     * 상태</b>를 읽는데, 구 구현은 세 값을 각각 다른 근거로 계산해 한 응답이 <b>"영구히 삭제됐다"</b> 와
     * <b>"아직 결정 대기이니 채택/반려하라"</b>({@code decision=PENDING} + {@code reviewable=true}) 를
     * 동시에 말했다. {@code reviewable} 은 FE 가 버튼을 그리는 근거라 그 버튼을 누르는 것은 우회가
     * 아니라 <b>정상 동선</b>이고 결과는 404 다.
     *
     * <p>구 테스트는 정확히 이 상태를 만들어놓고 {@code totalFramePairs}·{@code resultState}·
     * {@code discard.*} 만 단언해, {@code decision}/{@code reviewable} 축이 <b>커버리지 밖</b>이면서
     * "PURGED 처리가 완결됐다" 는 잘못된 통과 신호를 냈다(뮤테이션으로 실증됨). 세 축을 함께 고정한다.
     *
     * <p>파생 프레임을 <b>실제로 심는다</b>(구 시드는 0장이라 프레임 축 단언에 판별력이 없었다) —
     * 수정 전이라면 {@code totalFramePairs=1} + 죽은 이미지 링크 1건이 실려 실패한다.
     */
    @Test
    @DisplayName("실삭제된_항목은_PURGED_REJECTED_reviewable_false_프레임0_이_한꺼번에_맞춰진다")
    void purgedItemIsPurgedNotPreparing() throws Exception {
        // given — 조회 트랜잭션이 잡은 aug 스냅샷과 폐기 표식 사이에 스윕이 커밋된 좁은 창.
        //         검수 행은 이미 지워졌고(스윕 DELETE_ORDER), 프레임 스냅샷은 삭제 <이전> 것이다.
        LsDataRaw parent = seedParent("PURGED");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "PURGED");
        LsDataAug aug = seedGeneratedExternalAug(pf.getSrcSn(), LsDataAug.AUG_WINTER);
        LsDataRaw derivative = seedExternalDerivative(parent, aug, "Y");
        seedDerivativeFrame(derivative.getRawSn(), 0L, "PURGED");
        markDbDeleted(seedDiscardMark(aug, derivative, LocalDateTime.now().minusDays(10)));

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // ③ 죽은 이미지 링크(이미 CASCADE 로 사라진 srcSn)를 싣지 않는다.
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(0))
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(0))
                .andExpect(jsonPath("$.data.results[0].resultState").value("PURGED"))
                // ② 폐기 표식의 존재 자체가 "사람이 반려했다" 는 증거다(검수 행은 스윕이 먼저 지웠다).
                .andExpect(jsonPath("$.data.results[0].decision").value("REJECTED"))
                // ② 누르면 404 인 버튼을 FE 가 그리지 않게 한다.
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                .andExpect(jsonPath("$.data.results[0].discard.purged").value(true))
                .andExpect(jsonPath("$.data.results[0].discard.restorable").value(false));
    }

    /**
     * 폐기 표식이 <b>열려 있는</b>(아직 실삭제 전) 항목도 결정 대상이 아니다.
     *
     * <p>{@code decision}/{@code reviewable} 판정 입력은 "실삭제됐는가" 가 아니라 <b>매퍼가 돌려준 폐기
     * 축의 존재</b>다. 표식이 열려 있는 동안 채택 버튼이 뜨면, 유예 대기 중인(=이미 반려된) 결과물이
     * 다시 등재될 수 있다. 프레임 쌍은 <b>그대로 유지</b>된다 — 아직 실재하므로 비교 이미지를 보고
     * 복구 여부를 판단해야 한다(실삭제분과 다른 지점).
     */
    @Test
    @DisplayName("유예중인_폐기표식_항목도_REJECTED_이며_결정버튼이_뜨지_않는다")
    void openDiscardMarkIsNotReviewable() throws Exception {
        // given — 반려 표식만 있고 검수 행은 없는 상태(스윕이 검수 행을 먼저 지운 창 + 표식 열림).
        ExtFixture fx = seedExternal("OPENMARK", LsDataAug.AUG_WINTER, "Y");
        seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(1));

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                // 아직 지워지지 않았으므로 비교 이미지는 남는다(복구 판단 재료).
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(1))
                .andExpect(jsonPath("$.data.results[0].resultState").value("READY"))
                .andExpect(jsonPath("$.data.results[0].discard.purged").value(false))
                .andExpect(jsonPath("$.data.results[0].discard.restorable").value(true));
    }

    /**
     * 복구된 항목은 <b>다시 결정할 수 있다</b> — 폐기 판정이 복구를 덮지 않는다.
     *
     * <p>복구는 리뷰를 재오픈해 다시 채택/반려를 고르게 하는 것이 목적이므로, 매퍼가 {@code null} 을
     * 준 항목({@code RSTR_DT} 있음)은 {@code decision}/{@code reviewable} 분기에 걸리면 안 된다.
     * ②의 수정이 "원시 행" 을 입력으로 삼았다면 이 테스트가 깨진다(판정 입력 고정).
     */
    @Test
    @DisplayName("복구된_항목은_다시_결정할_수_있다_PENDING_reviewable_true")
    void restoredItemIsDecidableAgain() throws Exception {
        // given
        ExtFixture fx = seedExternal("REDECIDE", LsDataAug.AUG_NIGHT, "Y");
        markRestored(seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(2)));

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].discard").doesNotExist())
                .andExpect(jsonPath("$.data.results[0].decision").value("PENDING"))
                .andExpect(jsonPath("$.data.results[0].reviewable").value(true))
                .andExpect(jsonPath("$.data.results[0].resultState").value("READY"));
    }

    @Test
    @DisplayName("실삭제_클레임중인_항목은_restorable_false")
    void claimedItemIsNotRestorable() throws Exception {
        // given
        ExtFixture fx = seedExternal("CLAIMED", LsDataAug.AUG_WINTER, "Y");
        markPurgeClaimed(seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(10)));

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].discard.purged").value(false))
                .andExpect(jsonPath("$.data.results[0].discard.restorable").value(false));
    }

    @Test
    @DisplayName("그랜드퍼더링_항목은_폐기표식이_없어_discard_가_null")
    void itemWithoutMarkHasNullDiscard() throws Exception {
        // given — 폐기 표식을 만들지 않는다(NEW_RAW_SN 매핑 없는 구 요청 또는 반려 전).
        ExtFixture fx = seedExternal("NOMARK", LsDataAug.AUG_WINTER, "Y");

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].discard").doesNotExist())
                .andExpect(jsonPath("$.data.results[0].resultState").value("READY"));
    }

    @Test
    @DisplayName("해상도파생_항목은_폐기축이_항상_null")
    void resolutionItemNeverCarriesDiscardAxis() throws Exception {
        // given — 해상도 파생은 내부 생성물이라 accept/reject 가 차단돼 폐기 표식이 생길 수 없다.
        LsDataRaw parent = seedParent("RESLNULL");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "RESLNULL");
        seedResolutionAug(pf.getSrcSn(), LsDataAug.AUG_RESL_720P);
        LsDataRaw d = seedResolutionDerivative(parent, LsDataAug.AUG_RESL_720P, "Y", null);
        seedDerivativeFrame(d.getRawSn(), 0L, "RESLNULL");

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].type").value("RESL_720P"))
                .andExpect(jsonPath("$.data.results[0].discard").doesNotExist());
    }

    @Test
    @DisplayName("응답_JSON_어디에도_파일경로가_포함되지_않는다")
    void discardAxisNeverLeaksStoragePath() throws Exception {
        // given — 폐기 원장에는 NAS 실경로(VDO_FILE_PATH)와 폐기 사유(DSCD_RSN)가 실려 있다.
        ExtFixture fx = seedExternal("NOPATH", LsDataAug.AUG_WINTER, "Y");
        seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(1));

        // when
        String body = resultJson(fx.parent().getRawSn());

        // then
        assertThat(body).doesNotContain(SECRET_VIDEO_PATH);
        assertThat(body).doesNotContain("/nas-storage");
        assertThat(body).doesNotContain("/nas/videos");
        assertThat(body).doesNotContain("/nas/frames");
        assertThat(body).doesNotContain(".mp4");
        assertThat(body).doesNotContain(".jpg");
        assertThat(body)
                .as("폐기 원장의 경로/사유 컬럼명을 응답 키로 만들지 않는다")
                .doesNotContain("vdoFilePath").doesNotContain("discardReason");
    }

    /**
     * N+1 회귀 가드 — <b>폐기 조회는 항목 수에 비례하지 않는다</b>.
     *
     * <h3>왜 "폐기 표식 있는 영상 vs 없는 영상" 비교로는 잡히지 않는가</h3>
     * <p>항목별 단건 조회({@code findFirstByDataAugSn…})는 <b>표식이 없어도</b> 항목마다 1회 돈다(빈
     * 결과를 받을 뿐). 그래서 두 영상의 <b>증가분</b>은 N+1 구현에서도 같아져 A/B 비교가 무력하다.
     * 유일하게 판별력이 있는 형상은 <b>항목당 부수 조회가 0 인</b> 항목 집합이다 — 파생 매핑
     * ({@code NEW_RAW_SN})이 없는 외부 위탁 항목은 프레임 슬라이스 쿼리도 신고 게이트 조회도 돌지
     * 않으므로(둘 다 파생 rawSn 이 필요하다), 항목 수를 1 → 5 로 늘렸을 때 쿼리 수가 <b>그대로여야</b>
     * 한다. 폐기 조회가 항목별이면 정확히 4회 늘어난다.
     *
     * <p>이 형상은 실재한다 — V155 이전 요청·콜백 도착 전 항목이 그렇다. 다만 <b>표식은 인위적</b>이다:
     * 운영에서는 매핑 없는 증강에 표식을 만들지 않지만({@code markDiscarded} 가 skip), 이 테스트가 재는
     * 것은 <b>쿼리 형태</b>뿐이라 표식 대상 rawSn 의 실재 여부는 무관하다(폐기 원장은 FK 가 없다).
     */
    @Test
    @DisplayName("폐기정보_조회는_항목수와_무관하게_쿼리_1회만_실행된다")
    void discardLookupIsBatched() throws Exception {
        // given
        LsDataRaw parent = seedParent("NPLUS1");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "NPLUS1");
        for (int i = 0; i < 5; i++) {
            LsDataAug aug = seedGeneratedExternalAug(pf.getSrcSn(), LsDataAug.AUG_WINTER);
            discardRepository.saveAndFlush(LsDataAugDscd.mark(aug.getDataAugSn(),
                    UNRELATED_RAW_SN, null, DISCARD_REASON, "1",
                    LocalDateTime.now().minusDays(1), aug.getAugTypeCd(), null));
        }
        Long jobId = parent.getRawSn();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);

        // 워밍업 — 토큰 검증 캐시 등 요청 부수 조회를 두 측정에서 동일 조건으로 맞춘다.
        queryCount(stats, jobId, 1);
        queryCount(stats, jobId, 5);

        // when
        long oneItem = queryCount(stats, jobId, 1);
        long fiveItems = queryCount(stats, jobId, 5);

        // then
        assertThat(fiveItems)
                .as("항목 1건 → %d 쿼리, 5건 → %d 쿼리 (폐기 조회가 배치면 동일해야 한다)",
                        oneItem, fiveItems)
                .isEqualTo(oneItem);
    }

    private long queryCount(Statistics stats, Long jobId, int itemSize) throws Exception {
        stats.clear();
        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .param("itemSize", String.valueOf(itemSize))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    @Test
    @DisplayName("기존_응답_필드는_의미와_값이_그대로다")
    void existingFieldsAreUnchanged() throws Exception {
        // given
        ExtFixture fx = seedExternal("COMPAT", LsDataAug.AUG_WINTER, "Y");

        // when / then — 폐기 축은 additive 이며 기존 필드의 의미·값을 바꾸지 않는다.
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(fx.parent().getRawSn().intValue()))
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.message").exists())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(12))
                .andExpect(jsonPath("$.data.itemPage").value(0))
                .andExpect(jsonPath("$.data.itemSize").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.results[0].id").value(fx.aug().getDataAugSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].videoId").value(fx.parent().getRawSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].cctvName").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].type").value("WINTER"))
                .andExpect(jsonPath("$.data.results[0].decision").value("PENDING"))
                .andExpect(jsonPath("$.data.results[0].reviewable").value(true))
                .andExpect(jsonPath("$.data.results[0].derivativeRawSn")
                        .value(fx.derivative().getRawSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(1))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].resultState").value("READY"));
    }

    // ============================================================
    // DEV_FIX HIGH-① — 복구 가시성(restoreEligible)은 <BE 사전조건>이 정한다
    //
    // 화면은 `decision === 'REJECTED' && !discard.purged` 로 복구 버튼을 그렸는데, decision 의
    // REJECTED 는 세 입력(사람의 반려 · 폐기 표식 · 생성 영구 실패)에서 나오고 복구 API 는 앞 둘만
    // 받는다. 아래 테스트들은 "복구 API 가 받아주는 형상"과 "restoreEligible" 이 정확히 같은
    // 집합인지를 형상별로 고정한다 — 두 판정이 갈리면 화면이 죽은 버튼을 그린다.
    // ============================================================

    /**
     * <b>생성이 영구 실패한 항목에는 복구 버튼이 뜨면 안 된다</b> — 이 결함의 재현 케이스.
     *
     * <p>dead-letter 로 끝난 증강은 검수 행도 폐기 표식도 없어 {@code restoreWithoutMark} 가
     * <b>100% 404</b>({@code "복구할 폐기 이력이 없습니다."})다. 그런데 {@code decision} 은
     * {@code REJECTED}(실패를 실패로 보인다) · {@code discard} 는 {@code null} 이라, 화면이 그 두 값으로
     * 재유도하면 버튼이 뜨고 <b>재조회해도 값이 그대로라 무한 재시도</b>가 된다.
     *
     * <p>{@code resultState=GENERATION_FAILED} 를 함께 단언해 <b>같은 항목</b>이 "생성에 실패해 비교
     * 이미지가 없다" 와 "복구 가능" 을 동시에 말하지 않음을 고정한다.
     */
    @Test
    @DisplayName("생성_영구실패_항목은_REJECTED_로_보이지만_restoreEligible_은_false_다")
    void deadLetterItemIsNotRestoreEligible() throws Exception {
        // given — 생성 실패 롤업 + dead-letter 마커. 검수 행 없음, 폐기 표식 없음.
        LsDataRaw parent = seedParent("DEADLTR");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "DEADLTR");
        seedDeadLetterExternalAug(pf.getSrcSn(), LsDataAug.AUG_WINTER);

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.results[0].resultState").value("GENERATION_FAILED"))
                .andExpect(jsonPath("$.data.results[0].discard").doesNotExist())
                // ★ 복구 API 가 받지 않는 형상이므로 화면도 버튼을 그리면 안 된다.
                .andExpect(jsonPath("$.data.results[0].restoreEligible").value(false));
    }

    /**
     * <b>표식 없는 사람의 반려는 복구 가능하다</b> — {@code discard} 를 축으로 쓰면 놓치는 형상.
     *
     * <p>{@code markDiscarded} 는 매핑 없는 그랜드퍼더링·파생 행 부재에서 <b>표식 없이</b> 반환하고,
     * {@code restoreWithoutMark} 가 검수 재오픈으로 복구를 받아준다. 즉 {@code discard != null} 을
     * 가시성 조건으로 쓰면 <b>복구 가능한 항목에 버튼이 사라진다</b>.
     */
    @Test
    @DisplayName("폐기표식_없는_사람의_반려는_restoreEligible_true_다")
    void rejectedReviewWithoutMarkIsRestoreEligible() throws Exception {
        // given — 검수 행만 REJECTED, 폐기 표식 없음.
        ExtFixture fx = seedExternal("NOMARKREJ", LsDataAug.AUG_WINTER, "Y");
        seedRejectedReview(fx.aug(), fx.parent(), fx.parentFrame());

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.results[0].discard").doesNotExist())
                .andExpect(jsonPath("$.data.results[0].restoreEligible").value(true));
    }

    /**
     * 열린 폐기 표식 + <b>REJECTED 검수 행</b> = 운영의 정상 반려 형상 → 복구 가능.
     *
     * <p>검수 행을 함께 심는 것이 <b>정확한 재현</b>이다(DEV_FIX 2차 LOW-②) — 표식은
     * {@code reject()} 트랜잭션 안에서만 생기므로 열린 표식에는 항상 {@code REJECTED} 검수 행이
     * 동반한다. 구 시드는 표식만 심어놓고 {@code true} 를 단언했는데, 그 형상은 {@code restore()} 가
     * {@code reopen} 할 대상이 없어 <b>409</b> 다(= 죽은 버튼). 그 시드는 "열린 표식이면 복구 가능"
     * 이라는 <b>틀린 사전조건</b>을 고정하고 있었다.
     */
    @Test
    @DisplayName("열린_폐기표식_항목은_restoreEligible_true_다")
    void openMarkIsRestoreEligible() throws Exception {
        // given
        ExtFixture fx = seedExternal("OPENREST", LsDataAug.AUG_NIGHT, "Y");
        seedRejectedReview(fx.aug(), fx.parent(), fx.parentFrame());
        seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(1));

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].restoreEligible").value(true));
    }

    @Test
    @DisplayName("실삭제된_항목은_restoreEligible_false_다")
    void purgedItemIsNotRestoreEligible() throws Exception {
        // given — 되돌릴 대상이 물리적으로 사라졌다(restoreWithoutMark 가 409).
        ExtFixture fx = seedExternal("PURGEREST", LsDataAug.AUG_RAIN, "Y");
        markDbDeleted(seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(10)));

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].resultState").value("PURGED"))
                .andExpect(jsonPath("$.data.results[0].discard.purged").value(true))
                .andExpect(jsonPath("$.data.results[0].restoreEligible").value(false));
    }

    /**
     * <b>{@code restoreEligible} 과 {@code discard.restorable} 은 서로 다른 축이다</b> — 합치지 말 것.
     *
     * <p>실삭제 클레임({@code DEL_PRCS_DT})이 잡히면 매퍼는 힌트를 보수적으로 {@code false} 로 내지만,
     * 복구 자체는 <b>성립</b>한다(최종 DELETE 가 표식을 재평가해 삭제가 0건에 그친다). 그래서 BE
     * 사전조건을 그대로 계산하는 이 축은 {@code true} 다. 한 응답에서 두 값이 <b>갈리는 것이 정상</b>임을
     * 고정해, 나중에 "일관성" 을 이유로 한쪽을 다른 쪽으로 대체하지 못하게 한다.
     *
     * <p>검수 행({@code REJECTED})을 함께 심는다 — 복구의 <b>공통 수용 조건</b>이라 이것이 없으면
     * 클레임 여부와 무관하게 {@code restore()} 가 409 다(DEV_FIX 2차 LOW-②). 클레임 축만으로
     * 두 값이 갈린다는 것을 보이려면 나머지 조건은 <b>충족된</b> 형상이어야 한다.
     */
    @Test
    @DisplayName("실삭제_클레임중_항목은_discard_restorable_false_지만_restoreEligible_은_true_다")
    void claimedItemSplitsTwoAxes() throws Exception {
        // given
        ExtFixture fx = seedExternal("CLAIMREST", LsDataAug.AUG_WINTER, "Y");
        seedRejectedReview(fx.aug(), fx.parent(), fx.parentFrame());
        markPurgeClaimed(seedDiscardMark(fx.aug(), fx.derivative(), LocalDateTime.now().minusDays(10)));

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].discard.purged").value(false))
                .andExpect(jsonPath("$.data.results[0].discard.restorable").value(false))
                .andExpect(jsonPath("$.data.results[0].restoreEligible").value(true));
    }

    @Test
    @DisplayName("아직_결정하지_않은_항목은_restoreEligible_false_다")
    void undecidedItemIsNotRestoreEligible() throws Exception {
        // given — 되돌릴 결정이 없다(404).
        ExtFixture fx = seedExternal("PENDREST", LsDataAug.AUG_WINTER, "Y");

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].decision").value("PENDING"))
                .andExpect(jsonPath("$.data.results[0].restoreEligible").value(false));
    }

    @Test
    @DisplayName("해상도파생_항목은_restoreEligible_이_항상_false")
    void resolutionItemIsNeverRestoreEligible() throws Exception {
        // given — 검수·폐기 체계 밖이라 되돌릴 결정이 존재할 수 없다.
        LsDataRaw parent = seedParent("RESLREST");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "RESLREST");
        seedResolutionAug(pf.getSrcSn(), LsDataAug.AUG_RESL_720P);
        LsDataRaw d = seedResolutionDerivative(parent, LsDataAug.AUG_RESL_720P, "Y", null);
        seedDerivativeFrame(d.getRawSn(), 0L, "RESLREST");

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].type").value("RESL_720P"))
                .andExpect(jsonPath("$.data.results[0].restoreEligible").value(false));
    }

    // ============================================================
    // 작업 B — 항목 축 "센 뒤 드롭" 해소
    // ============================================================

    @Test
    @DisplayName("종류미해석_해상도항목은_총량에서도_제외되어_페이저와_카드수가_일치한다")
    void unresolvedResolutionItemIsExcludedFromTotal() throws Exception {
        // given — 해상도 파생의 AUG_TYPE_CD 가 비어 있어(V149 백필 미해석분) 종류를 판별할 수 없다.
        //         구 구현은 itemTotal 을 센 뒤 슬라이스 루프에서 드롭해 totalElements=2, results=1 이었다.
        LsDataRaw parent = seedParent("UNRESOLV");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "UNRESOLV");
        seedResolutionAug(pf.getSrcSn(), LsDataAug.AUG_RESL_480P);
        LsDataRaw broken = seedTypeUnresolvedResolutionDerivative(parent, LsDataAug.AUG_RESL_480P,
                parent.getVmsClipId() + "-plain-derivative");
        seedDerivativeFrame(broken.getRawSn(), 0L, "UNRESOLV");
        LsDataAug external = seedGeneratedExternalAug(pf.getSrcSn(), LsDataAug.AUG_WINTER);
        seedExternalDerivative(parent, external, "Y");

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].type").value("WINTER"))
                .andExpect(jsonPath("$.data.totalElements")
                        .value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    @DisplayName("신고보류_해상도항목은_총량에서도_제외된다")
    void withheldResolutionItemIsExcludedFromTotal() throws Exception {
        // given — 파생본이 비식별 누락 신고 구간('F'). 해상도 항목은 결정 UI 가 없어 통째로 제외한다.
        //         (부모는 'Y' — 부모가 'F' 면 진입 자체가 412 다.)
        LsDataRaw parent = seedParent("RESLWH");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "RESLWH");
        seedResolutionAug(pf.getSrcSn(), LsDataAug.AUG_RESL_720P);
        LsDataRaw d = seedResolutionDerivative(parent, LsDataAug.AUG_RESL_720P, "F", null);
        seedDerivativeFrame(d.getRawSn(), 0L, "RESLWH");

        // when / then — 총량과 항목 수가 모두 0(페이저가 존재하지 않는 페이지를 그리지 않는다).
        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    @DisplayName("신고보류_외부위탁항목은_총량에_남고_이미지만_빠진다")
    void withheldExternalItemKeepsItemAndDropsImagesOnly() throws Exception {
        // given — 외부 위탁 파생본이 신고 구간. 항목을 지우면 그 증강의 결정 상태·생성 조건까지
        //         화면에서 사라져 REVIEWER 가 무슨 일이 있었는지 알 수 없다(의도된 비대칭).
        ExtFixture fx = seedExternal("EXTWH", LsDataAug.AUG_WINTER, "F");

        // when / then
        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.results[0].type").value("WINTER"))
                .andExpect(jsonPath("$.data.results[0].resultState").value("WITHHELD"))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(0))
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(0));
    }

    @Test
    @DisplayName("총량과_전_페이지_results_합계가_항상_일치한다")
    void totalElementsMatchesSumOfAllPages() throws Exception {
        // given — 노출 3건(외부 2 + 해상도 1) + 드롭 대상 2건(종류 미해석 · 신고 보류)
        LsDataRaw parent = seedParent("SUMPAGE");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "SUMPAGE");
        for (String type : List.of(LsDataAug.AUG_WINTER, LsDataAug.AUG_RAIN)) {
            LsDataAug aug = seedGeneratedExternalAug(pf.getSrcSn(), type);
            seedExternalDerivative(parent, aug, "Y");
        }
        seedResolutionAug(pf.getSrcSn(), LsDataAug.AUG_RESL_1080P);
        seedResolutionDerivative(parent, LsDataAug.AUG_RESL_1080P, "Y", null);
        seedResolutionAug(pf.getSrcSn(), LsDataAug.AUG_RESL_720P);
        seedResolutionDerivative(parent, LsDataAug.AUG_RESL_720P, "F", null); // 신고 보류 → 제외
        seedResolutionAug(pf.getSrcSn(), LsDataAug.AUG_RESL_480P);
        seedTypeUnresolvedResolutionDerivative(parent, LsDataAug.AUG_RESL_480P,
                parent.getVmsClipId() + "-plain"); // AUG_TYPE_CD 미채움 → 종류 미해석 → 제외

        // when — itemSize=2 로 전 페이지를 순회한다.
        Long jobId = parent.getRawSn();
        int total = JsonPath.read(itemPage(jobId, 0, 2), "$.data.totalElements");
        int collected = 0;
        for (int page = 0; page * 2 < Math.max(total, 1); page++) {
            collected += ((List<?>) JsonPath.read(itemPage(jobId, page, 2), "$.data.results")).size();
        }

        // then
        assertThat(total).isEqualTo(3);
        assertThat(collected)
                .as("totalElements 와 전 페이지 results 합계는 같은 집합에서 나와야 한다")
                .isEqualTo(total);
    }

    private String itemPage(Long jobId, int itemPage, int itemSize) throws Exception {
        return mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .param("itemPage", String.valueOf(itemPage))
                        .param("itemSize", String.valueOf(itemSize))
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 유예 설정이 응답 계산에 실제로 쓰이는지(배선) — 값 자체 분기는 매퍼 단위 테스트가 고정한다. */
    @Test
    @DisplayName("purgeAt_은_폐기시각에_설정된_유예일수를_더한_값이다")
    void purgeAtUsesConfiguredGraceDays() throws Exception {
        // given
        ExtFixture fx = seedExternal("GRACE", LsDataAug.AUG_WINTER, "Y");
        LocalDateTime at = LocalDateTime.now().minusDays(1).withNano(0);
        seedDiscardMark(fx.aug(), fx.derivative(), at);

        // when
        String json = resultJson(fx.parent().getRawSn());

        // then
        String purgeAt = JsonPath.read(json, "$.data.results[0].discard.purgeAt");
        assertThat(LocalDateTime.parse(purgeAt))
                .isEqualTo(at.plusDays(discardProperties.graceDays()));
    }
}
