package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.ManualStageSkip;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 처리 현황 목록({@code GET /v1/videos})의 <b>건너뛴 작업 묶음 필터</b> 회귀 가드.
 * [@design API-042] [@design ADR-050]
 *
 * <h2>왜 이 필터가 필요한가</h2>
 * <p>벤더 연동이 확정된 뒤 건너뛴 영상을 모아 되살려야 하는데, 일괄 요청이 한 번에 받는 건수에 상한이
 * 있어 <b>목록에서 대상을 골라내지 못하면 회수가 성립하지 않는다</b>.
 *
 * <h2>고정하는 계약 셋</h2>
 * <ol>
 *   <li><b>지금 건너뛴 상태만</b> 남는다 — 이미 되살린(해제된) 영상은 남지 않는다. 표식이 append-only 라
 *       존재 여부로 판정하면 되살린 영상까지 걸린다.</li>
 *   <li><b>하위호환</b> — 이 파라미터를 보내지 않던 호출의 결과가 조금도 달라지지 않는다.</li>
 *   <li><b>행이 증식하지 않는다</b> — 표식이 여러 개 쌓인 영상도 목록에 <b>한 번</b>만 나오고
 *       {@code totalElements} 가 틀어지지 않는다({@code EXISTS} 를 쓰는 이유).</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoListSkippedBundleFilterIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private BatchStatusService batchStatusService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    /** 이 클래스가 심은 영상만 고르는 검색어 — 다른 시드에 기대 건수가 종속되지 않게 한다. */
    private String tag;

    private Long skippedVlm;
    private Long clearedVlm;
    private Long skippedAutolabel;
    private Long untouched;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        tag = "skipfilterit" + System.nanoTime();

        skippedVlm = saveVideo();
        clearedVlm = saveVideo();
        skippedAutolabel = saveVideo();
        untouched = saveVideo();

        skip(skippedVlm, BatchStageBundle.VLM);
        // 되살린 영상 — 「지금 건너뛴 상태」가 아니므로 필터에 걸리면 안 된다.
        skip(clearedVlm, BatchStageBundle.VLM);
        clear(clearedVlm, BatchStageBundle.VLM);
        skip(skippedAutolabel, BatchStageBundle.AUTOLABEL);
    }

    private Long saveVideo() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + tag + "-" + System.nanoTime(), tag, "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        return videoRepository.save(raw).getRawSn();
    }

    private void skip(Long rawSn, BatchStageBundle bundle) {
        batchStatusService.recordManualStageSkip(rawSn, bundle,
                ManualStageSkip.REASON_PREFIX + "벤더 장애", "1");
    }

    private void clear(Long rawSn, BatchStageBundle bundle) {
        batchStatusService.recordManualStageSkipCleared(rawSn, bundle,
                ManualStageSkip.MANUAL_CLEARED_REASON, "1");
    }

    private org.springframework.test.web.servlet.ResultActions list(String extraQuery) throws Exception {
        return mockMvc.perform(get("/v1/videos?size=50&cctvNameKeyword=" + tag + extraQuery)
                .header("Authorization", "Bearer " + reviewerToken));
    }

    @Test
    @DisplayName("★건너뜀_필터는_지금_건너뛴_상태인_영상만_남긴다_되살린_영상은_빠진다")
    void filtersOnlyCurrentlySkipped() throws Exception {
        list("&skippedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].rawSn").value(skippedVlm));
    }

    @Test
    @DisplayName("★건너뜀_필터는_묶음별로_독립이다")
    void filterIsPerBundle() throws Exception {
        list("&skippedStage=AUTOLABEL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].rawSn").value(skippedAutolabel));
    }

    /**
     * ★★신규 파라미터는 optional 이고 BE 기본값을 바꾸지 않는다 — 보내지 않던 기존 호출의 결과가
     * 조금도 달라지면 안 된다.
     */
    @Test
    @DisplayName("★★하위호환_파라미터를_보내지_않으면_표식_유무와_무관하게_전건이_그대로_나온다")
    void backwardCompatibleWhenParamAbsent() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));

        // 빈 문자열도 「미지정」이다(FE 가 필터를 비운 상태로 보낼 수 있다).
        list("&skippedStage=")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    /**
     * ★표식은 append-only 라 한 영상에 여러 행이 쌓인다. 조인으로 붙이면 표식 개수만큼 목록 행이 늘어
     * {@code totalElements} 까지 틀어진다 — {@code EXISTS} 를 쓰는 이유다.
     */
    @Test
    @DisplayName("★★표식이_여러_개_쌓여도_목록에_한_번만_나오고_총건수가_틀어지지_않는다")
    void markersDoNotMultiplyRows() throws Exception {
        skip(skippedVlm, BatchStageBundle.VLM);
        clear(skippedVlm, BatchStageBundle.VLM);
        skip(skippedVlm, BatchStageBundle.VLM);

        list("&skippedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].rawSn").value(skippedVlm));
    }

    @Test
    @DisplayName("★재스킵하면_다시_필터에_걸린다_마지막_표식이_판정을_결정한다")
    void reSkippedVideoReappears() throws Exception {
        skip(clearedVlm, BatchStageBundle.VLM);

        list("&skippedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    /**
     * ★미지 값은 0건이 아니라 <b>400</b> 이다 — 조용히 0건을 주면 화면이 "건너뛴 영상이 없다"로 오독한다.
     * 이벤트 유형 키(등록되지 않으면 0건)와 축이 다르다: 그쪽은 운영자가 바꾸는 데이터, 이쪽은 코드로
     * 고정된 집합이다.
     */
    @Test
    @DisplayName("★지원하지_않는_값은_400이고_요청_값을_되비추지_않는다")
    void unsupportedValueIsRejected() throws Exception {
        list("&skippedStage=YOLO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        list("&skippedStage=%3Cscript%3Ealert(1)%3C/script%3E")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("script"))));
    }

    @Test
    @DisplayName("건너뛰지_않은_영상은_필터_결과에_없다")
    void untouchedVideoIsExcluded() throws Exception {
        list("&skippedStage=VLM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.rawSn == " + untouched + ")]").isEmpty());
    }

    /**
     * ★쿼리에 박아 둔 표식 코드값이 표식 소유자({@code ManualStageSkip})와 갈리면 필터가 <b>조용히</b>
     * 0건이 된다 — 조회 결과가 비는 것은 오류로 드러나지 않으므로 상수 동일성을 기계로 고정한다.
     */
    @Test
    @DisplayName("★★쿼리_상수는_표식_소유자의_상수와_같아야_한다_갈리면_조용히_0건이_된다")
    void queryConstantsMatchMarkerOwner() {
        assertThat(VideoRepository.MANUAL_SKIP_ERR_CD).isEqualTo(ManualStageSkip.ERR_CD_SKIPPED);
        assertThat(VideoRepository.MANUAL_SKIP_MARKER_ERR_CDS)
                .containsExactlyElementsOf(ManualStageSkip.MARKER_ERR_CDS);
        assertThat(VideoRepository.MANUAL_SKIP_STTS_CD).isEqualTo("SKIPPED");
    }
}
