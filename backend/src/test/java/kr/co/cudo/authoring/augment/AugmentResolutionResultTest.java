package kr.co.cudo.authoring.augment;

import jakarta.persistence.EntityManagerFactory;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 — 해상도 파생 증강 결과 API({@code GET /v1/augments/{jobId}/result}) 검증.
 *
 * <p>구 구현은 {@code results} 를 무조건 빈 배열로 하드코딩해 FE 증강 결과 화면이 "외부 연동 이후 표시"
 * 안내만 렌더했다. 본 테스트는 <b>해상도 파생(RESL_*)</b> 에 대해 실제 프레임 쌍(부모 비식별 ↔ 파생
 * 리스케일)이 반환되는지, 그리고 HIGH 방어 5건(신고 게이트 412 / 원본 경로 미노출 / 프레임 조인 정확도 /
 * N+1 / FE 계약 유지)이 성립하는지 검증한다.
 *
 * <p>공유 PostgreSQL(Testcontainers) 오염 방지 — 시드 clipId 는 {@code AUGRES-} 고유 접두사를 쓰고
 * 단언은 시드한 rawSn 으로만 좁힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentResolutionResultTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;

    @Qualifier("controlEntityManagerFactory")
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private String workerToken;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    // ============================================================
    // 시드 헬퍼
    // ============================================================

    /** 원본(비식별 완료) 영상 + 프레임 N개(비식별 경로 보유) 시드. */
    private LsDataRaw seedParent(String suffix, String deIdntfYn) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGRES-" + suffix, "CCTV-AUGRES-" + suffix, "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/videos/AUGRES-" + suffix + "-orig.mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified(deIdntfYn);
        return videoRepository.save(parent);
    }

    /** 프레임 시드 — 원본/비식별 경로를 모두 채운다(부모 프레임 전제). */
    private LsDataSrc seedParentFrame(Long rawSn, long frameNo, String suffix) {
        LsDataSrc f = LsDataSrc.create(rawSn, frameNo, frameNo,
                "/nas/frames/raw/" + suffix + "/f" + frameNo + ".jpg", LocalDateTime.now());
        f.attachDeidPath("/nas/frames/deid/" + suffix + "/f" + frameNo + ".jpg");
        return srcRepository.save(f);
    }

    /** 부모 프레임 시드 — <b>비식별 경로 없음</b>(좌측에 보여줄 이미지가 없어 쌍이 성립하지 않는 케이스). */
    private LsDataSrc seedParentFrameWithoutDeid(Long rawSn, long frameNo, String suffix) {
        return srcRepository.save(LsDataSrc.create(rawSn, frameNo, frameNo,
                "/nas/frames/raw/" + suffix + "/f" + frameNo + ".jpg", LocalDateTime.now()));
    }

    /** 부모 프레임 시드 — 비식별 경로를 <b>지정한 문자열 그대로</b> 심는다(공백 판정 경계 검증용). */
    private LsDataSrc seedParentFrameWithDeidPath(Long rawSn, long frameNo, String deidPath) {
        return srcRepository.save(LsDataSrc.create(rawSn, frameNo, frameNo,
                "/nas/frames/raw/PAIRWS/f" + frameNo + ".jpg", deidPath, LocalDateTime.now()));
    }

    /** 파생 프레임 시드 — 원본 픽셀 부재(정책 A) 라 비식별 경로만 채운다. */
    private LsDataSrc seedDerivativeFrame(Long rawSn, long frameNo, String suffix) {
        return srcRepository.save(LsDataSrc.create(rawSn, frameNo, frameNo, null,
                "/nas/frames/deid/" + suffix + "/r" + frameNo + ".jpg", LocalDateTime.now()));
    }

    /** 확정된 해상도 파생 RAW 시드(deIdntfYn='Y' + COMPLETED). */
    private LsDataRaw seedDerivative(LsDataRaw parent, String presetCd) {
        LsDataRaw d = LsDataRaw.createFromResolution(parent,
                "/nas/videos/" + parent.getRawSn() + "/" + presetCd + ".mp4", presetCd);
        d.markDeidentified("Y");
        d.markCompleted();
        return videoRepository.save(d);
    }

    /**
     * 확정된 해상도 파생 RAW 를 <b>지정한 {@code VMS_CLIP_ID}</b> 로 시드한다.
     * 실데이터 포맷 드리프트(구형 {@code _RES_RES_480P_} 접두 등) 재현용 —
     * {@code createFromResolution} 은 현행 포맷만 생성하므로 저장 전에 clipId 를 갈아끼운다.
     */
    private LsDataRaw seedDerivativeWithClipId(LsDataRaw parent, String presetCd, String clipId) {
        LsDataRaw d = LsDataRaw.createFromResolution(parent,
                "/nas/videos/" + parent.getRawSn() + "/" + presetCd + ".mp4", presetCd);
        ReflectionTestUtils.setField(d, "vmsClipId", clipId);
        d.markDeidentified("Y");
        d.markCompleted();
        return videoRepository.save(d);
    }

    /** 확정된 해상도 파생 aug 행(ACCEPTED) 시드. */
    private LsDataAug seedResolutionAug(Long representativeSrcSn, String presetCd) {
        return augRepository.save(LsDataAug.createResolutionAccepted(representativeSrcSn, presetCd, "rev1"));
    }

    private record Fixture(LsDataRaw parent, List<LsDataSrc> parentFrames,
                           LsDataRaw derivative, List<LsDataSrc> derivativeFrames) {
    }

    /** 부모 + 프레임 frameCount 개 + RESL_720P 파생(프레임 동수) 완전 시드. */
    private Fixture seedFullResolutionDerivative(String suffix, int frameCount) {
        LsDataRaw parent = seedParent(suffix, "Y");
        List<LsDataSrc> parentFrames = new ArrayList<>();
        for (long i = 0; i < frameCount; i++) {
            parentFrames.add(seedParentFrame(parent.getRawSn(), i, suffix));
        }
        seedResolutionAug(parentFrames.get(0).getSrcSn(), LsDataAug.AUG_RESL_720P);
        LsDataRaw derivative = seedDerivative(parent, LsDataAug.AUG_RESL_720P);
        List<LsDataSrc> derivativeFrames = new ArrayList<>();
        for (long i = 0; i < frameCount; i++) {
            derivativeFrames.add(seedDerivativeFrame(derivative.getRawSn(), i, suffix));
        }
        return new Fixture(parent, parentFrames, derivative, derivativeFrames);
    }

    // ============================================================
    // 수용 기준 1·2·6 — results / framePairs / FE 계약
    // ============================================================

    @Test
    @DisplayName("해상도파생_결과조회시_results와_framePairs가_비식별이미지_URL로_반환된다")
    void returnsResolutionFramePairs() throws Exception {
        Fixture fx = seedFullResolutionDerivative("PAIRS", 3);
        Long jobId = fx.parent().getRawSn();

        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // FE 계약(H5) — 기존 응답 키 유지
                .andExpect(jsonPath("$.data.jobId").value(jobId.intValue()))
                .andExpect(jsonPath("$.data.status").exists())
                .andExpect(jsonPath("$.data.message").exists())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].type").value("RESL_720P"))
                .andExpect(jsonPath("$.data.results[0].videoId").value(jobId.intValue()))
                .andExpect(jsonPath("$.data.results[0].cctvName").isNotEmpty())
                .andExpect(jsonPath("$.data.results[0].decision").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(3))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].srcSn")
                        .value(fx.derivativeFrames().get(0).getSrcSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].frameNo").value(0))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].originalUrl")
                        .value("/v1/frames/" + fx.parentFrames().get(0).getSrcSn() + "/deid-image"))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].augmentedUrl")
                        .value("/v1/frames/" + fx.derivativeFrames().get(0).getSrcSn() + "/deid-image"));
    }

    @Test
    @DisplayName("해상도_3종은_각각_별도_results_항목으로_반환된다")
    void returnsOneResultPerPreset() throws Exception {
        LsDataRaw parent = seedParent("3TYPE", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "3TYPE");
        for (String preset : List.of(LsDataAug.AUG_RESL_1080P, LsDataAug.AUG_RESL_720P, LsDataAug.AUG_RESL_480P)) {
            seedResolutionAug(pf.getSrcSn(), preset);
            LsDataRaw d = seedDerivative(parent, preset);
            seedDerivativeFrame(d.getRawSn(), 0L, "3TYPE-" + preset);
        }

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(3))
                .andExpect(jsonPath("$.data.results[0].type").value("RESL_1080P"))
                .andExpect(jsonPath("$.data.results[1].type").value("RESL_720P"))
                .andExpect(jsonPath("$.data.results[2].type").value("RESL_480P"));
    }

    // ============================================================
    // 파생 판별 단일 원천(AugTypeParser) — 실데이터 clipId 포맷 드리프트
    // ============================================================

    @Test
    @DisplayName("구형_드리프트_clipId_RES_RES_480P_파생도_결과에_포함된다")
    void includesLegacyDriftClipIdDerivative() throws Exception {
        LsDataRaw parent = seedParent("DRIFT", "Y");
        LsDataSrc p0 = seedParentFrame(parent.getRawSn(), 0L, "DRIFT");
        LsDataSrc p1 = seedParentFrame(parent.getRawSn(), 1L, "DRIFT");
        seedResolutionAug(p0.getSrcSn(), LsDataAug.AUG_RESL_480P);
        // 실데이터(cudo_246) 드리프트 — 구형 RES 접두. 자체 contains("_RESL_480P_") 매칭으로는 미발견.
        LsDataRaw d = seedDerivativeWithClipId(parent, LsDataAug.AUG_RESL_480P,
                parent.getVmsClipId() + "_RES_RES_480P_1784683613093");
        LsDataSrc d0 = seedDerivativeFrame(d.getRawSn(), 0L, "DRIFT");
        seedDerivativeFrame(d.getRawSn(), 1L, "DRIFT");

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].type").value("RESL_480P"))
                .andExpect(jsonPath("$.data.results[0].derivativeRawSn").value(d.getRawSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].srcSn").value(d0.getSrcSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].originalUrl")
                        .value("/v1/frames/" + p0.getSrcSn() + "/deid-image"))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].originalUrl")
                        .value("/v1/frames/" + p1.getSrcSn() + "/deid-image"));
    }

    @Test
    @DisplayName("정규_단일접두_clipId_RESL_720P_파생도_계속_동작한다_회귀")
    void includesCanonicalSinglePrefixClipIdDerivative() throws Exception {
        LsDataRaw parent = seedParent("CANON", "Y");
        LsDataSrc p0 = seedParentFrame(parent.getRawSn(), 0L, "CANON");
        seedResolutionAug(p0.getSrcSn(), LsDataAug.AUG_RESL_720P);
        LsDataRaw d = seedDerivativeWithClipId(parent, LsDataAug.AUG_RESL_720P,
                parent.getVmsClipId() + "_RESL_720P_1784683613093");
        seedDerivativeFrame(d.getRawSn(), 0L, "CANON");

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].type").value("RESL_720P"))
                .andExpect(jsonPath("$.data.results[0].derivativeRawSn").value(d.getRawSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(1));
    }

    @Test
    @DisplayName("판별불가_clipId_파생은_결과에서_제외되고_다른_파생은_정상_반환된다")
    void unresolvableClipIdDerivativeIsExcludedWithoutBreakingOthers() throws Exception {
        LsDataRaw parent = seedParent("UNRESOLV", "Y");
        LsDataSrc p0 = seedParentFrame(parent.getRawSn(), 0L, "UNRESOLV");
        seedResolutionAug(p0.getSrcSn(), LsDataAug.AUG_RESL_480P);
        seedResolutionAug(p0.getSrcSn(), LsDataAug.AUG_RESL_720P);
        // 480P — 마커 없는 비정형 clipId (파서 판별 실패 → WARN 로그 후 제외)
        LsDataRaw broken = seedDerivativeWithClipId(parent, LsDataAug.AUG_RESL_480P,
                parent.getVmsClipId() + "-plain-derivative");
        seedDerivativeFrame(broken.getRawSn(), 0L, "UNRESOLV-BROKEN");
        // 720P — 정상 파생
        LsDataRaw ok = seedDerivativeWithClipId(parent, LsDataAug.AUG_RESL_720P,
                parent.getVmsClipId() + "_RESL_RESL_720P_1784683613093");
        seedDerivativeFrame(ok.getRawSn(), 0L, "UNRESOLV-OK");

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].type").value("RESL_720P"))
                .andExpect(jsonPath("$.data.results[0].derivativeRawSn").value(ok.getRawSn().intValue()));
    }

    // ============================================================
    // 수용 기준 3 — 페이징
    // ============================================================

    @Test
    @DisplayName("framePairs는_기본_12장까지만_반환된다")
    void defaultFramePairPageSizeIs12() throws Exception {
        Fixture fx = seedFullResolutionDerivative("DEF12", 15);
        Long jobId = fx.parent().getRawSn();

        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(12))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(12))
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(15));
    }

    @Test
    @DisplayName("page와_size로_framePairs_페이징이_동작한다")
    void framePairPagingWorks() throws Exception {
        Fixture fx = seedFullResolutionDerivative("PAGE", 5);

        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .param("page", "1").param("size", "2")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].frameNo").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].frameNo").value(3));
    }

    @Test
    @DisplayName("부모_비식별경로가_일부_없으면_totalFramePairs가_실제_표시가능한_쌍_수와_일치한다")
    void totalFramePairsCountsOnlyPairableFrames() throws Exception {
        LsDataRaw parent = seedParent("PAIRCNT", "Y");
        // 부모 15장 중 짝수 frameNo(8장)만 비식별 경로 보유 → 쌍 성립 8건
        LsDataSrc p0 = null;
        for (long i = 0; i < 15; i++) {
            LsDataSrc f = (i % 2 == 0)
                    ? seedParentFrame(parent.getRawSn(), i, "PAIRCNT")
                    : seedParentFrameWithoutDeid(parent.getRawSn(), i, "PAIRCNT");
            if (i == 0) {
                p0 = f;
            }
        }
        seedResolutionAug(p0.getSrcSn(), LsDataAug.AUG_RESL_720P);
        LsDataRaw d = seedDerivative(parent, LsDataAug.AUG_RESL_720P);
        for (long i = 0; i < 15; i++) {
            seedDerivativeFrame(d.getRawSn(), i, "PAIRCNT");
        }

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                // 총량(페이저 산출 근거)이 실제 표시 가능한 쌍 수와 일치해야 한다
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(8))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(8))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].frameNo").value(0))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].frameNo").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[7].frameNo").value(14));
    }

    @Test
    @DisplayName("부모_비식별경로가_탭_개행_공백이어도_totalFramePairs가_실제_쌍_수와_일치한다")
    void totalFramePairsMatchesWhenParentPathIsWhitespace() throws Exception {
        // given: 총량(count 쿼리 trim)과 표시(서비스 판정)의 공백 기준이 어긋나면 총량 ≠ 카드 수가 된다.
        //        탭/개행 경로는 SQL TRIM 으로 지워지지 않아 count 에 포함되므로 표시도 포함해야 한다.
        LsDataRaw parent = seedParent("PAIRWS", "Y");
        LsDataSrc p0 = seedParentFrame(parent.getRawSn(), 0, "PAIRWS");          // 정상 경로
        seedParentFrameWithDeidPath(parent.getRawSn(), 1, "\t");                  // 탭
        seedParentFrameWithDeidPath(parent.getRawSn(), 2, "\n");                  // 개행
        seedParentFrameWithDeidPath(parent.getRawSn(), 3, "   ");                 // 스페이스만 → 부재
        seedParentFrameWithoutDeid(parent.getRawSn(), 4, "PAIRWS");               // 비식별 경로 없음
        seedParentFrame(parent.getRawSn(), 5, "PAIRWS");                          // 정상 경로

        seedResolutionAug(p0.getSrcSn(), LsDataAug.AUG_RESL_720P);
        LsDataRaw d = seedDerivative(parent, LsDataAug.AUG_RESL_720P);
        for (long i = 0; i < 6; i++) {
            seedDerivativeFrame(d.getRawSn(), i, "PAIRWS");
        }

        // when / then: 총량과 실제 쌍 수가 모두 4(frameNo 0,1,2,5)
        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(4))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(4))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].frameNo").value(0))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].frameNo").value(1))
                .andExpect(jsonPath("$.data.results[0].framePairs[2].frameNo").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[3].frameNo").value(5));
    }

    @Test
    @DisplayName("쌍이_안되는_프레임은_페이지에서도_빠져_마지막_페이지가_비지_않는다")
    void pagingSkipsUnpairableFramesSoNoEmptyPage() throws Exception {
        LsDataRaw parent = seedParent("PAIRPAGE", "Y");
        LsDataSrc p0 = null;
        for (long i = 0; i < 15; i++) {
            LsDataSrc f = (i % 2 == 0)
                    ? seedParentFrame(parent.getRawSn(), i, "PAIRPAGE")
                    : seedParentFrameWithoutDeid(parent.getRawSn(), i, "PAIRPAGE");
            if (i == 0) {
                p0 = f;
            }
        }
        seedResolutionAug(p0.getSrcSn(), LsDataAug.AUG_RESL_480P);
        LsDataRaw d = seedDerivative(parent, LsDataAug.AUG_RESL_480P);
        for (long i = 0; i < 15; i++) {
            seedDerivativeFrame(d.getRawSn(), i, "PAIRPAGE");
        }

        // 쌍 8건(frameNo 0,2,4,6,8,10,12,14) 을 size=3 으로 나누면 마지막 페이지(2)는 12,14
        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .param("page", "2").param("size", "3")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(8))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].frameNo").value(12))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].frameNo").value(14));
    }

    @Test
    @DisplayName("size가_최대치를_초과하면_400")
    void rejectsOversizedPage() throws Exception {
        mockMvc.perform(get("/v1/augments/{jobId}/result", 999999L)
                        .param("size", "101")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("page가_음수이거나_size가_0이하면_400")
    void rejectsNegativePaging() throws Exception {
        mockMvc.perform(get("/v1/augments/{jobId}/result", 999999L)
                        .param("page", "-1")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/augments/{jobId}/result", 999999L)
                        .param("size", "0")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    // ============================================================
    // H1 — 비식별 신고 게이트 (수용 기준 4)
    // ============================================================

    @Test
    @DisplayName("H1_부모영상이_비식별신고_구간이면_412로_차단된다")
    void blocksWhenParentUnderDeidentReport() throws Exception {
        Fixture fx = seedFullResolutionDerivative("REPORT", 2);
        LsDataRaw parent = videoRepository.findById(fx.parent().getRawSn()).orElseThrow();
        parent.markDeidentified("F"); // 비식별 누락 신고 구간
        videoRepository.save(parent);

        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    @DisplayName("H1_신고가_없으면_정상_200_음성케이스")
    void allowsWhenNotUnderDeidentReport() throws Exception {
        Fixture fx = seedFullResolutionDerivative("NOREPORT", 2);

        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(2));
    }

    // ============================================================
    // H2 — 원본(PII) 파일 경로 미노출
    // ============================================================

    @Test
    @DisplayName("H2_응답에_스토리지_파일경로가_포함되지_않는다")
    void responseNeverLeaksStoragePaths() throws Exception {
        Fixture fx = seedFullResolutionDerivative("NOPATH", 2);

        String body = mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("/nas/frames/raw");
        assertThat(body).doesNotContain("/nas/frames/deid");
        assertThat(body).doesNotContain("/nas/videos");
        assertThat(body).doesNotContain(".jpg");
        assertThat(body).doesNotContain(".mp4");
    }

    // ============================================================
    // H3 — 프레임 조인 정확도 ((RAW_SN, FRM_NO) 명시 키)
    // ============================================================

    @Test
    @DisplayName("H3_부모와_파생의_프레임수가_다르고_frameNo가_불연속이어도_같은_frameNo_쌍만_생성된다")
    void pairsOnlyMatchingFrameNos() throws Exception {
        LsDataRaw parent = seedParent("JOIN", "Y");
        // 부모: frameNo 0, 5, 9 (불연속)
        LsDataSrc p0 = seedParentFrame(parent.getRawSn(), 0L, "JOIN");
        LsDataSrc p5 = seedParentFrame(parent.getRawSn(), 5L, "JOIN");
        seedParentFrame(parent.getRawSn(), 9L, "JOIN");
        seedResolutionAug(p0.getSrcSn(), LsDataAug.AUG_RESL_480P);
        LsDataRaw d = seedDerivative(parent, LsDataAug.AUG_RESL_480P);
        // 파생: frameNo 0, 5, 7 (7 은 부모에 없음 → 쌍 불가)
        LsDataSrc d0 = seedDerivativeFrame(d.getRawSn(), 0L, "JOIN");
        LsDataSrc d5 = seedDerivativeFrame(d.getRawSn(), 5L, "JOIN");
        seedDerivativeFrame(d.getRawSn(), 7L, "JOIN");

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].frameNo").value(0))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].srcSn").value(d0.getSrcSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].originalUrl")
                        .value("/v1/frames/" + p0.getSrcSn() + "/deid-image"))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].frameNo").value(5))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].srcSn").value(d5.getSrcSn().intValue()))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].originalUrl")
                        .value("/v1/frames/" + p5.getSrcSn() + "/deid-image"));
    }

    // ============================================================
    // H4 — N+1 (쿼리 수가 페이지 크기에 비례하지 않음)
    // ============================================================

    @Test
    @DisplayName("H4_쿼리횟수가_페이지크기에_비례하지_않는다_배치조회")
    void queryCountDoesNotScaleWithPageSize() throws Exception {
        Fixture fx = seedFullResolutionDerivative("NPLUS1", 20);
        Long jobId = fx.parent().getRawSn();

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);

        // 워밍업 — 토큰 검증 캐시 등 요청 부수 조회를 두 측정에서 동일 조건으로 맞춘다.
        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .param("size", "2")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        stats.clear();
        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .param("size", "2")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        long small = stats.getPrepareStatementCount();

        stats.clear();
        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .param("size", "20")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(20));
        long large = stats.getPrepareStatementCount();

        assertThat(large)
                .as("페이지 크기 2 → %d 쿼리, 20 → %d 쿼리 (배치 조회면 동일해야 함)", small, large)
                .isEqualTo(small);
    }

    // ============================================================
    // 수용 기준 5 — 외부 증강(WINTER/NIGHT/RAIN) 회귀 0
    // ============================================================

    @Test
    @DisplayName("WINTER_외부증강_잡은_기존과_동일하게_빈_results를_유지한다")
    void externalAugmentJobKeepsEmptyResults() throws Exception {
        LsDataRaw parent = seedParent("WINTER", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "WINTER");
        augRepository.save(LsDataAug.createPending(pf.getSrcSn(), LsDataAug.AUG_WINTER,
                new BigDecimal("90.00"), "system"));

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(0))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    @DisplayName("해상도_파생이_PENDING이면_생성_미완료라_results에_포함되지_않는다")
    void pendingResolutionDerivativeIsNotExposed() throws Exception {
        LsDataRaw parent = seedParent("PENDING", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "PENDING");
        augRepository.save(LsDataAug.createResolutionPending(pf.getSrcSn(), LsDataAug.AUG_RESL_720P, "rev1"));
        seedDerivative(parent, LsDataAug.AUG_RESL_720P);

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(0));
    }

    @Test
    @DisplayName("존재하지_않는_jobId면_빈_results와_PROCESSING을_반환한다")
    void unknownJobIdReturnsEmptyResults() throws Exception {
        mockMvc.perform(get("/v1/augments/{jobId}/result", 9_999_999L)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(0))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    @DisplayName("REVIEWER가_아니면_403")
    void nonReviewerForbidden() throws Exception {
        mockMvc.perform(get("/v1/augments/{jobId}/result", 1L)
                        .header("Authorization", "Bearer " + workerToken))
                .andExpect(status().isForbidden());
    }
}
