package kr.co.cudo.authoring.augment;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
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
    @Autowired private LsDataAugRvwRepository reviewRepository;

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

    /**
     * <b>생성이 성공한</b> 외부 위탁 증강 행 시드 — 웹훅 성공 인계 이후 상태(결과물 실재).
     * 결정(채택/반려) 가능 상태의 정본 픽스처다.
     */
    private LsDataAug seedGeneratedExternalAug(Long representativeSrcSn, String augType) {
        LsDataAug aug = augRepository.save(LsDataAug.createPending(representativeSrcSn, augType,
                new BigDecimal("90.00"), "system"));
        aug.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        return augRepository.saveAndFlush(aug);
    }

    /** 확정된 해상도 파생 aug 행(ACCEPTED) 시드. */
    private LsDataAug seedResolutionAug(Long representativeSrcSn, String presetCd) {
        return augRepository.save(LsDataAug.createResolutionAccepted(representativeSrcSn, presetCd, "rev1"));
    }

    /**
     * 외부 위탁 증강의 <b>확정된 파생 영상</b>(deIdntfYn='Y' + COMPLETED) 시드 + {@code NEW_RAW_SN} 매핑.
     *
     * <p>프로덕션은 {@code AugmentResultService.createAugmentedVideo} 가 파생 RAW 를 INSERT 한
     * <b>같은 트랜잭션</b>에서 {@code assignDerivativeRawSn} 을 호출한다 — 그 상태를 재현한다.
     */
    private LsDataRaw seedExternalDerivative(LsDataRaw parent, LsDataAug aug) {
        LsDataRaw d = LsDataRaw.createFromAugment(parent,
                "/nas/videos/" + parent.getRawSn() + "/" + aug.getAugTypeCd() + ".mp4",
                aug.getAugTypeCd(), aug.getDataAugSn());
        d.markDeidentified("Y");
        d.markCompleted();
        LsDataRaw saved = videoRepository.save(d);
        aug.assignDerivativeRawSn(saved.getRawSn());
        augRepository.saveAndFlush(aug);
        return saved;
    }

    /** 외부 위탁 증강 완전 시드 결과 — 증강 행까지 들고 있어야 검수 행 시드를 붙일 수 있다. */
    private record ExtFixture(LsDataRaw parent, List<LsDataSrc> parentFrames, LsDataAug aug,
                              LsDataRaw derivative, List<LsDataSrc> derivativeFrames) {
    }

    /** 부모 프레임 frameCount 개 + 생성 성공 외부 위탁 증강 + 파생 영상/프레임(동수) 완전 시드. */
    private ExtFixture seedFullExternalDerivative(String suffix, String augType, int frameCount) {
        LsDataRaw parent = seedParent(suffix, "Y");
        List<LsDataSrc> parentFrames = new ArrayList<>();
        for (long i = 0; i < frameCount; i++) {
            parentFrames.add(seedParentFrame(parent.getRawSn(), i, suffix));
        }
        LsDataAug aug = seedGeneratedExternalAug(parentFrames.get(0).getSrcSn(), augType);
        LsDataRaw derivative = seedExternalDerivative(parent, aug);
        List<LsDataSrc> derivativeFrames = new ArrayList<>();
        for (long i = 0; i < frameCount; i++) {
            derivativeFrames.add(seedDerivativeFrame(derivative.getRawSn(), i, suffix));
        }
        return new ExtFixture(parent, parentFrames, aug, derivative, derivativeFrames);
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
    // 수용 기준 5 — 외부 증강(WINTER/NIGHT/RAIN): 항목은 노출, 프레임 쌍은 미연동
    // ============================================================

    /**
     * <b>파생 매핑({@code NEW_RAW_SN})이 없는</b> 외부 위탁 항목 — V149 이전 요청(그랜드퍼더링) 또는
     * 콜백 전. 이 경우에만 프레임 쌍이 비고, 그때도 항목 자체는 노출된다(R9 생성 조건 확인 경로).
     *
     * <p>매핑이 있는 정상 형상에서는 프레임 쌍이 채워진다 —
     * {@link #externalAugmentResultReturnsFramePairs()} 참조.
     */
    @Test
    @DisplayName("WINTER_외부증강_잡은_항목으로_노출되되_프레임쌍은_비어_있다")
    void externalAugmentJobIsExposedWithoutFramePairs() throws Exception {
        LsDataRaw parent = seedParent("WINTER", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "WINTER");
        // 생성이 끝난(결과물 실재) 상태 — 이때만 결정 버튼이 뜬다(reviewable 두 축 중 결과물 축).
        seedGeneratedExternalAug(pf.getSrcSn(), LsDataAug.AUG_WINTER);

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].type").value("WINTER"))
                .andExpect(jsonPath("$.data.results[0].decision").value("PENDING"))
                // reviewable 은 <유지>된다(의도된 판단 — 되돌리지 말 것). false 로 막으면 파생 매핑이
                // 없는 그랜드퍼더링 항목이 영구히 결정 불가가 되고, 등재 게이트가 리뷰 축이라 파생
                // 영상이 작업목록에 영영 오르지 못한다(이미 배정된 WORKER 의 고아 배정).
                .andExpect(jsonPath("$.data.results[0].reviewable").value(true))
                // 외부 연동 전 — 프레임 쌍/총량은 비어 있다(해상도 파생 경로와 구분)
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(0))
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(0))
                // ★ Phase 6 잔여 D-1 — 0장인 <이유>를 사실대로 말한다.
                //   이 시나리오(생성 성공 + NEW_RAW_SN 없음)는 프레임 쌍이 <영원히> 0장인데,
                //   구 구현은 총량만 보고 PREPARING_FRAMES("생성이 완료되어 비교 이미지를 반입하고
                //   있습니다")를 내려 FE 가 <영원히 오지 않을 것을 곧 온다고> 표시했다. 이 테스트는
                //   그 시나리오를 정확히 시드하면서도 resultState 를 단언하지 않아 결함을 가려왔다.
                .andExpect(jsonPath("$.data.results[0].resultState").value("DERIVATIVE_UNLINKED"))
                // 증강행↔파생 RAW 연결 컬럼이 없어 유형만으로 짝짓지 않는다(추정 연결 금지)
                .andExpect(jsonPath("$.data.results[0].derivativeRawSn").doesNotExist())
                // V147 이전 방식으로 적재된 행은 생성 조건이 없다 → null
                .andExpect(jsonPath("$.data.results[0].prompt").doesNotExist())
                // 집계 축(생성 결과)은 전부 종결 → COMPLETED
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    // ============================================================
    // DEV_FIX(2026-07-31) FIX-A — 증강 3종도 비교 이미지를 낸다
    //   구 구현은 toExternalItems 가 framePairs=List.of(), totalFramePairs=0 을 <상수>로 반환해
    //   검수 가능한 유일한 유형(WINTER/NIGHT/RAIN)의 비교 이미지가 영구히 0장이었다. 그 상태에서
    //   FE 는 "외부 연동 이후 표시됩니다" 를 띄우면서 채택/거부 버튼을 활성으로 그렸다 — 이미지를
    //   한 장도 못 보고 승인하면 등재 게이트가 의례적 절차가 된다.
    // ============================================================

    /**
     * <b>계획서 명시 케이스</b> — 증강 3종 결과 화면에서 원본(부모 비식별) ↔ 증강 프레임 쌍이 반환된다.
     *
     * <p>짝짓기 근거는 <b>{@code LS_DATA_AUG.NEW_RAW_SN}</b>(V149)이다 — 유형이나 {@code VMS_CLIP_ID}
     * 마커로 추정하지 않는다(같은 종류 재요청이 허용돼 유형만으로는 다른 요청의 파생본을 가리킨다).
     */
    @Test
    @DisplayName("증강_3종_결과화면에서_원본과_증강_프레임쌍이_반환된다")
    void externalAugmentResultReturnsFramePairs() throws Exception {
        ExtFixture fx = seedFullExternalDerivative("EXTPAIR", LsDataAug.AUG_WINTER, 3);
        Long jobId = fx.parent().getRawSn();

        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].type").value("WINTER"))
                // NEW_RAW_SN 매핑이 그대로 내려간다(추정 없음)
                .andExpect(jsonPath("$.data.results[0].derivativeRawSn")
                        .value(fx.derivative().getRawSn().intValue()))
                // ★ 핵심 — 구 구현은 여기가 항상 0 이었다
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(3))
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(3))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].frameNo").value(0))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].originalUrl")
                        .value("/v1/frames/" + fx.parentFrames().get(0).getSrcSn() + "/deid-image"))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].augmentedUrl")
                        .value("/v1/frames/" + fx.derivativeFrames().get(0).getSrcSn() + "/deid-image"))
                .andExpect(jsonPath("$.data.results[0].framePairs[2].frameNo").value(2))
                // 비교 이미지가 실재한다 → 결정 가능
                .andExpect(jsonPath("$.data.results[0].resultState").value("READY"))
                .andExpect(jsonPath("$.data.results[0].reviewable").value(true))
                // 스토리지 경로는 여전히 노출하지 않는다(CWE-209/359)
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/nas/frames"))));
    }

    /** 프레임 축(page/size)은 외부 위탁 항목에서도 해상도 항목과 동일하게 동작한다. */
    @Test
    @DisplayName("외부증강_프레임쌍도_page_size_로_페이징된다")
    void externalAugmentFramePairsArePaged() throws Exception {
        ExtFixture fx = seedFullExternalDerivative("EXTFPAGE", LsDataAug.AUG_RAIN, 5);

        mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .param("page", "1").param("size", "2")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].type").value("RAIN"))
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(5))
                .andExpect(jsonPath("$.data.results[0].framePairs.length()").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[0].frameNo").value(2))
                .andExpect(jsonPath("$.data.results[0].framePairs[1].frameNo").value(3));
    }

    /**
     * <b>두 축의 독립성이 외부 위탁 항목에서도 유지된다</b> (Phase 5 에서 세 번 뒤집힌 불변식).
     *
     * <p>①항목 페이저를 넘겨도 그 페이지의 항목은 자기 프레임 쌍을 그대로 받고
     * ②프레임 페이저를 넘겨도 항목 구성은 바뀌지 않는다.
     */
    @Test
    @DisplayName("외부증강_프레임쌍은_항목축과_프레임축_어느_쪽에도_갇히지_않는다")
    void externalFramePairsAreNotTrappedByEitherAxis() throws Exception {
        ExtFixture fx = seedFullExternalDerivative("EXTAXIS", LsDataAug.AUG_WINTER, 5);
        Long jobId = fx.parent().getRawSn();
        // 더 최신인 항목 1건을 추가 — 최신순 정렬이라 itemPage=0 에는 이 NIGHT 이, 1에는 WINTER 가 온다.
        augRepository.saveAndFlush(LsDataAug.createPending(fx.parentFrames().get(0).getSrcSn(),
                LsDataAug.AUG_NIGHT, new BigDecimal("90.00"), "system"));

        String itemPage1 = resultJson(jobId, 0, 12, 1, 1);
        assertThat((List<Object>) JsonPath.read(itemPage1, "$.data.results[*].type"))
                .containsExactly("WINTER");
        assertThat((List<Object>) JsonPath.read(itemPage1, "$.data.results[0].framePairs[*].frameNo"))
                .as("항목 페이저를 넘긴 사용자도 비교 이미지에 도달해야 한다")
                .hasSize(5);

        // 프레임 축은 그 항목 안에서 독립적으로 동작한다.
        assertThat((List<Object>) JsonPath.read(
                resultJson(jobId, 2, 2, 1, 1), "$.data.results[0].framePairs[*].frameNo"))
                .containsExactly(4);

        // 프레임 페이지를 넘겨도 항목(탭) 구성은 그대로다.
        assertThat((List<Object>) JsonPath.read(resultJson(jobId, 1, 2, null, null),
                "$.data.results[*].type"))
                .isEqualTo(JsonPath.read(resultJson(jobId, 0, 2, null, null),
                        "$.data.results[*].type"));
    }

    /**
     * <b>프레임이 아직 없는 정상 상태</b>(반입이 비동기) 와 <b>영구 실패</b>가 응답에서 구분된다.
     *
     * <p>구 FE 는 쌍이 0장이면 무조건 "프레임별 비교 결과는 외부 연동 이후 표시됩니다" 를 띄웠다 —
     * 사실과 다르고, 무엇보다 "기다리면 온다" 와 "영영 안 온다" 가 화면에서 같아 보인다.
     */
    @Test
    @DisplayName("프레임_반입중과_추출_영구실패가_resultState_로_구분된다")
    void preparingFramesIsDistinguishedFromPermanentFailure() throws Exception {
        // given — 파생은 만들어졌지만 프레임 반입 전(정상). 프레임 0건.
        LsDataRaw parent = seedParent("EXTPREP", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "EXTPREP");
        LsDataAug preparing = seedGeneratedExternalAug(pf.getSrcSn(), LsDataAug.AUG_WINTER);
        seedExternalDerivative(parent, preparing);

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].totalFramePairs").value(0))
                .andExpect(jsonPath("$.data.results[0].resultState").value("PREPARING_FRAMES"))
                .andExpect(jsonPath("$.data.results[0].decision").value("PENDING"));

        // given — 같은 형상인데 비동기 확정이 영구 실패(dead-letter)했다. 상태 컬럼은 ACCEPTED 그대로.
        LsDataRaw parent2 = seedParent("EXTDEAD", "Y");
        LsDataSrc pf2 = seedParentFrame(parent2.getRawSn(), 0L, "EXTDEAD");
        LsDataAug dead = seedGeneratedExternalAug(pf2.getSrcSn(), LsDataAug.AUG_WINTER);
        seedExternalDerivative(parent2, dead);
        dead.markDeadLetter();
        augRepository.saveAndFlush(dead);

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent2.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].resultState").value("GENERATION_FAILED"))
                // ACCEPTED 로 남은 상태를 그대로 보이면 "채택됨" 이 되어 헤더(FAILED)와 상반된다
                .andExpect(jsonPath("$.data.results[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                // 두 축이 같은 사실을 말한다
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    /**
     * FIX-D — <b>생성 실패</b>와 <b>사람의 반려</b>는 둘 다 {@code decision=REJECTED} 라 FE 가
     * 구분할 수 없었다(실패인데 "누군가 거부함" 으로 보였다). {@code resultState} 가 그 축을 준다.
     */
    @Test
    @DisplayName("사람_반려와_생성_실패가_resultState_로_구분된다")
    void humanRejectionIsDistinguishedFromGenerationFailure() throws Exception {
        ExtFixture fx = seedFullExternalDerivative("EXTREJ", LsDataAug.AUG_WINTER, 2);
        Long jobId = fx.parent().getRawSn();
        reviewRepository.saveAndFlush(LsDataAugRvw.createRejected(
                fx.aug().getDataAugSn(), jobId, fx.parentFrames().get(0).getSrcSn(),
                "겨울 질감이 부자연스럽다", "1", LocalDateTime.now()));

        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.results[0].rejectReason").value("겨울 질감이 부자연스럽다"))
                // 사람이 반려했을 뿐 결과물은 정상 — 생성 실패가 아니다
                .andExpect(jsonPath("$.data.results[0].resultState").value("READY"));
    }

    // ------------------------------------------------------------
    // DEV_FIX(2026-07-31) — 결정 버튼은 <결정 가능할 때만> 뜬다
    //   reviewable 은 accept/reject 사전조건과 같은 두 축(결과물 실재 + 미결정)으로 계산된다.
    //   한쪽만 보면 "버튼이 보이는데 누르면 거부" 또는 "결과물 없이 승인 가능" 이 생긴다.
    // ------------------------------------------------------------

    /**
     * <b>생성 중</b>인 증강에는 결정 버튼이 뜨지 않는다.
     *
     * <p>중간 구현은 검수 축(검수 행 없음 → 미결정)만 보고 {@code reviewable=true} 를 내려, FE 가
     * 그린 채택 버튼을 누르는 <b>정상 동선</b>으로 결과물 없는 승인이 성립했다(등재 게이트 무력화).
     * {@code decision} 은 검수 축의 사실대로 PENDING 이지만 {@code reviewable=false} 라 FE 는 카드를
     * 감춘다({@code AugmentResultPanel.showDecision}).
     */
    @Test
    @DisplayName("생성이_끝나지_않은_증강은_결정_대상이_아니다_reviewable_false")
    void inFlightAugmentIsNotReviewable() throws Exception {
        LsDataRaw parent = seedParent("INFLIGHT", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "INFLIGHT");
        augRepository.save(LsDataAug.createPending(pf.getSrcSn(), LsDataAug.AUG_WINTER,
                new BigDecimal("90.00"), "system"));

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                .andExpect(jsonPath("$.data.results[0].decision").value("PENDING"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    /**
     * <b>생성 실패(dead-letter)</b> 증강은 결정 대상이 아니고, 화면에도 "미결정" 이 아니라 실패로 보인다.
     *
     * <p>구 구현({@code decision = AUG_PROC_STTS_CD})은 이걸 맞게 표시하고 있었는데 축 분리 중간
     * 구현이 <b>회귀</b>시켰다 — 실패한 증강 카드에 채택/반려 버튼이 뜨고 상태는 "미결정" 이었다.
     */
    @Test
    @DisplayName("생성_실패로_종결된_증강은_REJECTED로_표기되고_결정_대상이_아니다")
    void deadLetteredAugmentIsShownAsFailedAndNotReviewable() throws Exception {
        LsDataRaw parent = seedParent("DEADL", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "DEADL");
        LsDataAug failed = augRepository.save(LsDataAug.createPending(pf.getSrcSn(),
                LsDataAug.AUG_NIGHT, new BigDecimal("90.00"), "system"));
        failed.applyGenerationResult(LsDataAug.STTS_REJECTED);
        failed.markDeadLetter();
        augRepository.saveAndFlush(failed);

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                .andExpect(jsonPath("$.data.results[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    /**
     * <b>사용자 취소</b> 증강도 결정 대상이 아니며 CANCELED 로 표기된다 — FE 의 취소 표시 분기
     * ({@code DecisionCard} CANCELED)가 도달 가능해야 한다.
     */
    @Test
    @DisplayName("취소된_증강은_CANCELED로_표기되고_결정_대상이_아니다")
    void canceledAugmentIsShownAsCanceledAndNotReviewable() throws Exception {
        LsDataRaw parent = seedParent("CANCEL", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "CANCEL");
        LsDataAug canceled = augRepository.save(LsDataAug.createPending(pf.getSrcSn(),
                LsDataAug.AUG_RAIN, new BigDecimal("90.00"), "system"));
        canceled.markCanceled();
        augRepository.saveAndFlush(canceled);

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                .andExpect(jsonPath("$.data.results[0].decision").value("CANCELED"));
    }

    /**
     * 결정이 끝난 뒤에는 그 결과가 그대로 보이고 버튼은 사라진다(재결정 409 와 화면 정합).
     */
    @Test
    @DisplayName("이미_결정된_증강은_그_결정이_표시되고_reviewable_false")
    void decidedAugmentShowsDecisionAndIsNotReviewable() throws Exception {
        LsDataRaw parent = seedParent("DECIDED", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "DECIDED");
        LsDataAug generated = seedGeneratedExternalAug(pf.getSrcSn(), LsDataAug.AUG_WINTER);
        reviewRepository.saveAndFlush(LsDataAugRvw.createRejected(
                generated.getDataAugSn(), parent.getRawSn(), pf.getSrcSn(),
                "겨울 질감이 부자연스럽다", "1", LocalDateTime.now()));

        mockMvc.perform(get("/v1/augments/{jobId}/result", parent.getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].reviewable").value(false))
                .andExpect(jsonPath("$.data.results[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.results[0].rejectReason").value("겨울 질감이 부자연스럽다"));
    }

    /**
     * DEV_FIX MED-4 → HIGH-1 (2차) — 항목 중복 금지는 <b>항목 축({@code itemPage}/{@code itemSize})</b>
     * 위에서 성립한다.
     *
     * <p>1차 수정은 프레임 축({@code page}/{@code size}) 창을 항목에도 그대로 적용해 중복을 없앴지만,
     * 프레임 쌍이 0건인 영상(순수 외부 위탁)에서는 FE 페이저가 렌더되지 않아 2페이지로 갈 UI 자체가
     * 없었다 → 13번째 항목이 <b>화면에서 소실</b>됐다. 축을 분리한 뒤에도 "한 항목 페이지 안에서
     * 중복 없음 + 전 항목 도달 가능" 은 그대로 유지돼야 한다.
     */
    @Test
    @DisplayName("외부증강_항목이_항목페이지마다_중복되지_않는다")
    void externalItemsAreNotDuplicatedAcrossItemPages() throws Exception {
        LsDataRaw parent = seedParent("EXTPAGE", "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, "EXTPAGE");
        for (String type : List.of(LsDataAug.AUG_WINTER, LsDataAug.AUG_NIGHT, LsDataAug.AUG_RAIN)) {
            augRepository.save(LsDataAug.createPending(pf.getSrcSn(), type,
                    new BigDecimal("90.00"), "system"));
        }
        Long jobId = parent.getRawSn();

        List<Object> page0 = externalItemIds(jobId, 0, 2);
        List<Object> page1 = externalItemIds(jobId, 1, 2);
        List<Object> page2 = externalItemIds(jobId, 2, 2);

        // 항목 페이지 간 중복 0건 + 3건 전부 도달 가능(R9 — 생성 조건 역추적 경로 유지).
        List<Object> all = new ArrayList<>(page0);
        all.addAll(page1);
        all.addAll(page2);
        assertThat(all)
                .as("구 구현은 페이지마다 external 전량을 다시 실어 9건(3×3)이 됐다")
                .hasSize(3)
                .doesNotHaveDuplicates();
        // 기본 화면(itemPage=0)에 항목이 실려야 결정 전 조건 확인 동선이 성립한다.
        assertThat(page0).isNotEmpty();
    }

    // ============================================================
    // DEV_FIX HIGH-1 — 항목 축 / 프레임 축 분리
    // ============================================================

    /**
     * HIGH-1 재현 시나리오 — 외부 위탁 13건(WINTER 12 + RAIN 1), 해상도 파생 <b>없음</b>.
     *
     * <p>1차 수정 후에는 {@code pageOf(external, page=0, size=12)} 가 WINTER 12건만 남기고 RAIN 을
     * 잘라냈는데, 프레임 쌍이 0건이라 FE 페이저가 렌더되지 않아 2페이지로 갈 수단이 없었고 응답에
     * 항목 축 총량도 없어 <b>2페이지의 존재조차 알 수 없었다</b>. 축 분리 후에는 ① 기본 요청에
     * 13건이 모두 실리고(항목 기본 창 20) ② {@code itemPage} 로도 도달 가능해야 한다.
     */
    @Test
    @DisplayName("외부증강_13건_중_13번째도_조회로_도달할_수_있다")
    void thirteenthExternalItemIsReachable() throws Exception {
        Long jobId = seedThirteenExternalAugs("EXT13");

        // 기본 요청(구 FE 와 동일하게 항목 축 파라미터 미전송) — 항목 기본 창(20) 안에 13건 전부
        String json = resultJson(jobId, null, null, null, null);
        List<Object> types = JsonPath.read(json, "$.data.results[*].type");
        assertThat(types).hasSize(13).contains("RAIN");

        // 항목 축 페이징으로도 13번째 항목에 도달할 수 있어야 한다(itemSize=12 → 2페이지).
        // 정렬이 최신순(DESC)이므로 2페이지에 남는 것은 <가장 먼저 요청한> WINTER 1건이다.
        List<Object> itemPage1 = JsonPath.read(
                resultJson(jobId, null, null, 1, 12), "$.data.results[*].type");
        assertThat(itemPage1)
                .as("13번째 항목은 항목 축 2페이지에서 조회돼야 한다")
                .containsExactly("WINTER");
    }

    /**
     * 흡수 항목(MED) — 결과 항목 축 정렬을 <b>최신순(DESC)</b>으로 뒤집었다.
     *
     * <p>오름차순이면 1페이지에서 잘려나가는 쪽이 <b>가장 최신 = 유일하게 PENDING 인 결정 대상</b>
     * (직전 요청분)이라, 항목 페이저가 아직 없는 FE(Phase 5 대상)에서 방금 요청한 결과에 도달할
     * 수단이 없었다. {@code rules/api-design.md} 의 기본 정렬({@code createdAt,desc})과도 일치한다.
     */
    @Test
    @DisplayName("결과항목이_최신순으로_정렬된다")
    void externalItemsAreSortedNewestFirst() throws Exception {
        Long jobId = seedThirteenExternalAugs("EXTDESC");

        List<Object> ids = JsonPath.read(
                resultJson(jobId, null, null, null, null), "$.data.results[*].id");
        List<Object> types = JsonPath.read(
                resultJson(jobId, null, null, null, null), "$.data.results[*].type");

        assertThat(types.get(0))
                .as("가장 나중에 요청한 RAIN 이 맨 앞이어야 한다(최신순)")
                .isEqualTo("RAIN");
        List<Long> asLongs = ids.stream().map(id -> ((Number) id).longValue()).toList();
        assertThat(asLongs)
                .as("동일 시각 요청은 PK 내림차순으로 결정적 순서를 갖는다")
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // 첫 항목 페이지만 봐도 최신(=결정 대상) 항목에 도달할 수 있어야 한다.
        List<Object> firstPage = JsonPath.read(
                resultJson(jobId, null, null, 0, 1), "$.data.results[?(@.type in ['WINTER','NIGHT','RAIN'])].type");
        assertThat(firstPage).containsExactly("RAIN");
    }

    /**
     * 흡수 항목(MED) → <b>DEV_FIX MED-5</b> — 항목 축의 원소는 <b>외부 위탁 + 해상도 파생 전부</b>다.
     *
     * <p>구 구현 둘 다 결함이 있었다: ①모든 {@code itemPage} 에 해상도 항목을 실으면 페이지를
     * 이어붙이는 클라이언트가 <b>중복 수집</b>한다 ②{@code itemPage==0} 에만 실으면 항목 페이저를 넘긴
     * 사용자가 <b>프레임 쌍(비교 이미지)에 도달할 수 없다</b>(프레임 축이 항목 축에 갇힌다).
     *
     * <p>해상도 파생을 항목 축의 정식 원소로 세면 셋이 동시에 성립한다 — 중복 0 · 전 항목 도달 가능 ·
     * {@code results.length} ↔ {@code totalElements} 정합.
     */
    @Test
    @DisplayName("해상도파생이_모든_itemPage_에_중복_적재되지_않는다")
    void resolutionItemsAreNotDuplicatedAcrossItemPages() throws Exception {
        Fixture fx = seedFullResolutionDerivative("ITEMDUP", 3);
        Long jobId = fx.parent().getRawSn();
        // 외부 위탁 2건 + 해상도 파생 1건 → totalElements=3, itemSize=2 이면 totalPages=2
        for (String type : List.of(LsDataAug.AUG_WINTER, LsDataAug.AUG_RAIN)) {
            augRepository.save(LsDataAug.createPending(fx.parentFrames().get(0).getSrcSn(), type,
                    new BigDecimal("90.00"), "system"));
        }

        String page0 = resultJson(jobId, null, null, 0, 2);
        assertThat((int) (Integer) JsonPath.read(page0, "$.data.totalElements"))
                .as("항목 축 총량은 results 에 실리는 모든 항목을 센다(표준 페이징 규약)").isEqualTo(3);
        assertThat((int) (Integer) JsonPath.read(page0, "$.data.totalPages")).isEqualTo(2);

        List<Object> all = new ArrayList<>((List<Object>) JsonPath.read(page0, "$.data.results[*].type"));
        all.addAll(JsonPath.read(resultJson(jobId, null, null, 1, 2), "$.data.results[*].type"));

        assertThat(all)
                .as("전 항목이 정확히 한 번씩만 모인다 — 이어붙이는 클라이언트의 중복 수집 방지")
                .hasSize(3)
                .containsExactlyInAnyOrder("WINTER", "RAIN", "RESL_720P");

        // 총량 밖 페이지는 비어야 한다.
        assertThat((List<Object>) JsonPath.read(
                resultJson(jobId, null, null, 2, 2), "$.data.results[*].type")).isEmpty();
    }

    /**
     * DEV_FIX MED-5 — <b>항목 페이저를 넘겨도 프레임 쌍에 도달할 수 있다</b>.
     *
     * <p>구 구현은 {@code itemPage == 0} 에만 해상도 항목을 실어, {@code ?itemPage=1} 로 호출하면
     * 해상도 파생의 {@code framePairs} 가 통째로 사라졌다 — Phase 2 가 세운 "두 축은 서로 독립"
     * 불변식의 <b>역방향 파손</b>(항목이 프레임 축에 갇히던 것을 고쳤더니 프레임이 항목 축에 갇혔다).
     */
    @Test
    @DisplayName("itemPage_를_넘겨도_프레임쌍은_그대로_조회된다")
    void framePairsRemainReachableOnLaterItemPages() throws Exception {
        Fixture fx = seedFullResolutionDerivative("ITEMAXIS", 3);
        Long jobId = fx.parent().getRawSn();
        for (String type : List.of(LsDataAug.AUG_WINTER, LsDataAug.AUG_RAIN)) {
            augRepository.save(LsDataAug.createPending(fx.parentFrames().get(0).getSrcSn(), type,
                    new BigDecimal("90.00"), "system"));
        }

        // itemSize=2 → 0페이지는 외부 위탁 2건, 1페이지에 해상도 파생이 온다.
        String itemPage1 = resultJson(jobId, 0, 12, 1, 2);
        assertThat((List<Object>) JsonPath.read(itemPage1, "$.data.results[*].type"))
                .containsExactly("RESL_720P");
        assertThat((List<Object>) JsonPath.read(itemPage1, "$.data.results[0].framePairs[*].srcSn"))
                .as("항목 페이저를 넘긴 사용자도 비교 이미지에 도달할 수 있어야 한다")
                .hasSize(3);
        assertThat((int) (Integer) JsonPath.read(itemPage1, "$.data.results[0].totalFramePairs"))
                .isEqualTo(3);

        // 프레임 축은 그 항목 안에서 독립적으로 동작한다(항목 페이지가 1이어도 page/size 가 먹는다).
        assertThat((List<Object>) JsonPath.read(
                resultJson(jobId, 1, 2, 1, 2), "$.data.results[0].framePairs[*].frameNo"))
                .containsExactly(2);
    }

    /** HIGH-1 — 항목 축 총량이 없으면 클라이언트가 2페이지의 존재를 알 수 없다(rules/api-design.md). */
    @Test
    @DisplayName("응답에_항목축_총량이_포함된다")
    void responseCarriesItemAxisTotals() throws Exception {
        Long jobId = seedThirteenExternalAugs("EXTTOTAL");

        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .param("itemSize", "12")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(13))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.itemPage").value(0))
                .andExpect(jsonPath("$.data.itemSize").value(12));
    }

    /**
     * HIGH-1 부수 증상 — 1차 수정에서는 프레임 페이지를 넘기면 external 항목이 슬라이스에서 빠져
     * <b>탭 구성이 페이지마다 바뀌었다</b>. 축이 분리되면 프레임 페이지 이동은 항목 구성을 바꾸지 않는다.
     */
    @Test
    @DisplayName("프레임_페이지를_넘겨도_항목_구성이_바뀌지_않는다")
    void framePagingDoesNotChangeItemComposition() throws Exception {
        Fixture fx = seedFullResolutionDerivative("AXISMIX", 15);
        Long jobId = fx.parent().getRawSn();
        for (String type : List.of(LsDataAug.AUG_WINTER, LsDataAug.AUG_RAIN)) {
            augRepository.save(LsDataAug.createPending(fx.parentFrames().get(0).getSrcSn(), type,
                    new BigDecimal("90.00"), "system"));
        }

        List<Object> onFramePage0 = JsonPath.read(
                resultJson(jobId, 0, 12, null, null), "$.data.results[*].type");
        List<Object> onFramePage1 = JsonPath.read(
                resultJson(jobId, 1, 12, null, null), "$.data.results[*].type");

        assertThat(onFramePage1)
                .as("프레임 축 이동은 항목(탭) 구성에 영향을 주지 않는다")
                .isEqualTo(onFramePage0);
        assertThat(onFramePage0).contains("WINTER", "RAIN", "RESL_720P");
    }

    /**
     * 하위호환 — FE 는 아직 구 파라미터({@code page}/{@code size})만 보낸다(항목 축 배선은 Phase 5).
     * 신규 파라미터는 optional 이고 <b>BE 기본값은 불변</b>이어야 한다(rules/api-design.md).
     */
    @Test
    @DisplayName("기존_page_size_파라미터만_보내도_동작이_변하지_않는다")
    void legacyPageSizeParamsKeepWorking() throws Exception {
        Fixture fx = seedFullResolutionDerivative("LEGACY", 5);
        Long jobId = fx.parent().getRawSn();
        augRepository.save(LsDataAug.createPending(fx.parentFrames().get(0).getSrcSn(),
                LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));

        // page/size 만 전송 — 프레임 쌍 페이징은 기존 동작 그대로(page=1,size=2 → frameNo 2,3)
        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .param("page", "1").param("size", "2")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                // 항목 순서 = 외부 위탁(검수 대상) → 해상도 파생(내부 생성물)
                .andExpect(jsonPath("$.data.results[0].type").value("WINTER"))
                .andExpect(jsonPath("$.data.results[1].type").value("RESL_720P"))
                .andExpect(jsonPath("$.data.results[1].framePairs.length()").value(2))
                .andExpect(jsonPath("$.data.results[1].framePairs[0].frameNo").value(2))
                // 항목 축은 기본값(0 / 20)으로 적용돼 외부 위탁 + 해상도 파생이 함께 실린다
                .andExpect(jsonPath("$.data.itemPage").value(0))
                .andExpect(jsonPath("$.data.itemSize").value(20))
                // MED-5 — 총량은 results 전체(외부 1 + 해상도 1)를 센다
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // 기본 요청(파라미터 0개)도 종전 기본값(0 / 12)을 유지한다
        mockMvc.perform(get("/v1/augments/{jobId}/result", jobId)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(12));
    }

    /** 입력 검증(CWE-20/770) — 항목 축도 프레임 축과 동일한 범위 규약을 갖는다. */
    @Test
    @DisplayName("항목축_페이징_파라미터가_범위를_벗어나면_400")
    void rejectsOutOfRangeItemPaging() throws Exception {
        mockMvc.perform(get("/v1/augments/{jobId}/result", 999999L)
                        .param("itemSize", "101")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/augments/{jobId}/result", 999999L)
                        .param("itemSize", "0")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/augments/{jobId}/result", 999999L)
                        .param("itemPage", "-1")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isBadRequest());
    }

    /** 외부 위탁 13건(WINTER 12 → RAIN 1 순) 시드 후 jobId 반환. */
    private Long seedThirteenExternalAugs(String suffix) {
        LsDataRaw parent = seedParent(suffix, "Y");
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), 0L, suffix);
        for (int i = 0; i < 12; i++) {
            augRepository.save(LsDataAug.createPending(pf.getSrcSn(), LsDataAug.AUG_WINTER,
                    new BigDecimal("90.00"), "system"));
        }
        augRepository.save(LsDataAug.createPending(pf.getSrcSn(), LsDataAug.AUG_RAIN,
                new BigDecimal("90.00"), "system"));
        return parent.getRawSn();
    }

    /** 지정 항목 페이지의 외부 위탁(WINTER/NIGHT/RAIN) 항목 id 목록. */
    private List<Object> externalItemIds(Long jobId, int itemPage, int itemSize) throws Exception {
        String json = resultJson(jobId, null, null, itemPage, itemSize);
        return JsonPath.read(json, "$.data.results[?(@.type in ['WINTER','NIGHT','RAIN'])].id");
    }

    /** 결과 조회 원문 — null 파라미터는 <b>전송하지 않는다</b>(BE 기본값 적용 경로 검증). */
    private String resultJson(Long jobId, Integer page, Integer size,
                              Integer itemPage, Integer itemSize) throws Exception {
        var request = get("/v1/augments/{jobId}/result", jobId)
                .header("Authorization", "Bearer " + reviewerToken);
        if (page != null) {
            request = request.param("page", String.valueOf(page));
        }
        if (size != null) {
            request = request.param("size", String.valueOf(size));
        }
        if (itemPage != null) {
            request = request.param("itemPage", String.valueOf(itemPage));
        }
        if (itemSize != null) {
            request = request.param("itemSize", String.valueOf(itemSize));
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
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
