package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.review.entity.LsDataIssue;
import kr.co.cudo.authoring.review.repository.IssueRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 상세({@code GET /v1/videos/{rawSn}}) 프레임 미리보기의 <b>이슈 점</b> 종단 검증.
 * [@design API-043] [@design SCREEN-009]
 *
 * <h2>왜 단위시험만으로 부족한가</h2>
 * <p>이슈 여부의 판정은 <b>두 조각</b>으로 나뉜다 — 「미해소 문의가 달린 프레임을 고르는 조회」와
 * 「그 집합으로 항목을 채우는 조립」이다. 단위시험은 조립만 보고 조회를 스텁으로 대체하므로,
 * 「영상 단위 문의(프레임을 가리키지 않는 문의)는 어느 프레임도 이슈로 만들지 않는다」가 실제로
 * 성립하는지는 <b>실 DB 로 두 조각을 이어야만</b> 드러난다. 조회가 그 행을 걸러 내지 않으면
 * 문의 한 건으로 미리보기 전체에 점이 번진다.
 *
 * <h2>고정하는 계약</h2>
 * <ol>
 *   <li>해소되지 않은 문의가 달린 프레임만 {@code hasIssue=true} 다.</li>
 *   <li>해소된 문의만 달린 프레임은 {@code false} 다 — 판정 축은 <b>해소 여부</b>다.</li>
 *   <li>프레임을 가리키지 않는 <b>영상 단위 문의</b>는 어느 프레임도 {@code true} 로 만들지 않는다.</li>
 *   <li>반려 사유 성격의 항목은 등록 시점부터 해소 상태라 <b>자연히</b> 빠진다(타입 조건이 아니라
 *       해소 여부로 빠지는 것이 요점이다).</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoDetailFramePreviewIssueIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private IssueRepository issueRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private Long rawSn;

    /** 미해소 문의가 달린 프레임. */
    private Long openInquiryFrame;
    /** 해소된 문의만 달린 프레임. */
    private Long resolvedInquiryFrame;
    /** 반려 사유만 달린 프레임(반려는 등록 시점부터 해소 상태다). */
    private Long rejectionFrame;
    /** 아무 문의도 없는 프레임. */
    private Long cleanFrame;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-previewissue-" + System.nanoTime(), "CCTV-previewissue", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        rawSn = videoRepository.save(raw).getRawSn();

        openInquiryFrame = saveFrame(0, 0L);
        resolvedInquiryFrame = saveFrame(1, 30L);
        rejectionFrame = saveFrame(2, 60L);
        cleanFrame = saveFrame(3, 90L);

        issueRepository.save(LsDataIssue.createInquiry(rawSn, "여기 얼굴이 남아 있습니다", "9", openInquiryFrame));

        LsDataIssue resolved = LsDataIssue.createInquiry(rawSn, "확인 부탁드립니다", "9", resolvedInquiryFrame);
        resolved.resolve();
        issueRepository.save(resolved);

        // 반려 사유 — createInquiry 가 아니라 create 로 만들며 등록 시점부터 해소 상태다.
        LsDataIssue rejection = LsDataIssue.create(rawSn, "라벨 누락", "1");
        org.springframework.test.util.ReflectionTestUtils.setField(rejection, "srcSn", rejectionFrame);
        issueRepository.save(rejection);

        // ★영상 단위 문의 — 프레임을 가리키지 않는다(프레임 식별자 없음).
        issueRepository.save(LsDataIssue.createInquiry(rawSn, "전체적으로 다시 봐 주세요", "9", null));
    }

    private Long saveFrame(long frameNo, Long videoFrameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, videoFrameNo,
                "/nas/frames/raw/" + rawSn + "/frame-" + frameNo + ".jpg", null);
        return srcRepository.save(src).getSrcSn();
    }

    private ResultActions detail() throws Exception {
        return mockMvc.perform(get("/v1/videos/" + rawSn)
                .header("Authorization", "Bearer " + reviewerToken));
    }

    private String issueOf(Long srcSn) {
        return "$.data.framePreviews[?(@.srcSn == " + srcSn + ")].hasIssue";
    }

    @Test
    @DisplayName("★★미해소_문의가_달린_프레임만_이슈로_표시된다_영상_단위_문의는_어느_프레임도_만들지_않는다")
    void onlyFramesWithUnresolvedInquiryAreMarked() throws Exception {
        detail()
                .andExpect(status().isOk())
                // ① 미해소 문의가 달린 프레임 — 참
                .andExpect(jsonPath(issueOf(openInquiryFrame)).value(true))
                // ② 해소된 문의만 달린 프레임 — 거짓(판정 축은 해소 여부다)
                .andExpect(jsonPath(issueOf(resolvedInquiryFrame)).value(false))
                // ③ 반려 사유만 달린 프레임 — 거짓. 타입으로 걸러서가 아니라 등록 시점부터 해소 상태라 빠진다
                .andExpect(jsonPath(issueOf(rejectionFrame)).value(false))
                // ④ ★영상 단위 문의가 있어도 아무 문의 없는 프레임은 거짓이다
                .andExpect(jsonPath(issueOf(cleanFrame)).value(false));
    }

    /**
     * 시각은 <b>추출 순번이 아니라 실제 영상 내 위치</b>로 계산된다. 이 영상의 두 값은 다르므로
     * (순번 0·1·2·3 ↔ 위치 0·30·60·90) 순번으로 계산하면 값이 갈린다.
     */
    @Test
    @DisplayName("★시각은_실제_영상_내_위치로_계산되고_기존_세_필드는_그대로다")
    void timestampUsesVideoPositionAndKeepsExistingFields() throws Exception {
        // fps 메타가 없는 영상이라 해석기 폴백(30.0)이 쓰인다 — 위치 30 = 1000ms, 60 = 2000ms.
        detail()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.framePreviews[?(@.srcSn == " + openInquiryFrame + ")].timestampMs")
                        .value(0))
                .andExpect(jsonPath("$.data.framePreviews[?(@.srcSn == " + resolvedInquiryFrame + ")].timestampMs")
                        .value(1000))
                .andExpect(jsonPath("$.data.framePreviews[?(@.srcSn == " + rejectionFrame + ")].timestampMs")
                        .value(2000))
                // 기존 세 필드는 값·이름 그대로다(프레임 번호는 여전히 추출 순번이다).
                .andExpect(jsonPath("$.data.framePreviews[?(@.srcSn == " + rejectionFrame + ")].frameNo")
                        .value(2))
                .andExpect(jsonPath("$.data.framePreviews[?(@.srcSn == " + rejectionFrame + ")].thumbnailUrl")
                        .value("/v1/frames/" + rejectionFrame + "/image"));
    }

    /**
     * ★영상 내 위치를 모르는 레거시 프레임은 시각을 <b>비운다</b> — {@code 0}(영상 맨 앞)으로
     * 채우면 사실과 구분되지 않는다.
     */
    @Test
    @DisplayName("★영상_내_위치가_없는_레거시_프레임은_시각이_비어_있다")
    void legacyFrameWithoutVideoPositionHasNullTimestamp() throws Exception {
        Long legacy = saveFrame(4, null);

        // 미리보기는 추출 순번 오름차순이라 이 프레임이 마지막(색인 4)이다. 필터 표현식 대신 색인을
        // 쓰는 이유는 「값이 null」과 「행이 없음」을 필터 표현식이 구분하지 못하기 때문이다.
        detail()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.framePreviews[4].srcSn").value(legacy))
                .andExpect(jsonPath("$.data.framePreviews[4].timestampMs", nullValue()))
                // 0(영상 맨 앞)으로 채우지 않는다 — 그러면 사실과 구분되지 않는다.
                .andExpect(jsonPath("$.data.framePreviews[0].timestampMs").value(0));
    }
}
