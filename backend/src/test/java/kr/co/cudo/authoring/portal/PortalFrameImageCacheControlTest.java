package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.util.SeedImageGenerator;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/portal/frames/{srcSn}/image} — 캐시 정책(no-store) 회귀 방어.
 *
 * <p><b>왜 필요한가</b>: 이 엔드포인트는 <b>내부 파이프라인의 비식별 프레임</b>을 외부(포털) 채널로
 * 내보내며 {@code LabelAccessGuard.requireNotUnderDeidentReport} 게이트 대상이다. 그런데 응답 캐시만
 * {@code private, max-age=300} 으로 남아 있어, 조회 성공(200) 후 비식별 누락 신고({@code DE_IDNTF_YN='F'})가
 * 나도 브라우저 HTTP 캐시가 최대 5분간 <b>마스킹 실패 PII 프레임</b>을 재노출했다(서버 412 에 도달조차 못 함).
 * 게이트 뒤 미디어 경로(내부 {@code /image}·{@code /deid-image}·영상 {@code /stream})와 동일하게
 * {@code no-store} 여야 한다(CWE-359/525).
 *
 * <p>구 정책({@code max-age=300}) 이면 {@link #portalFrameImageIsNotCached()} 가 실패한다.
 *
 * <p><b>스코프 주의</b>: 포털 <b>업로드 자산</b> 서빙({@code /v1/portal/uploads/frames/**},
 * {@code PortalUploadService}) 은 본인 업로드분이라 신고 게이트 대상이 아니며 본 테스트 대상이 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PortalFrameImageCacheControlTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsRawDataStatusRepository rawDataStatusRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidPath;

    private String portalToken;
    private Long rawSn;
    private Long srcSn;
    private Path deidFramePath;

    @BeforeEach
    void setup() throws IOException {
        portalToken = JwtTestSupport.token(secret, "alice", "PORTAL_USER", "PORTAL", issuer, 60);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-PORTAL-CACHE-001", "CCTV-PORTAL", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        // 포털은 데이터마트 노출(검수 완료 = APPROVED) 영상만 서빙한다.
        LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        rawDataStatusRepository.save(status);

        // 포털은 비식별 프레임만 서빙 — 원본 폴백 없음.
        Path deidBase = Paths.get(storageDeidPath).toAbsolutePath().normalize();
        String deidRel = StorageSubtreePolicy.deidFramesDir(rawSn) + "/frame_1.jpg";
        deidFramePath = deidBase.resolve(deidRel).normalize();
        Files.createDirectories(deidFramePath.getParent());
        SeedImageGenerator.generate(deidFramePath, "EVT_FALL", "CCTV-PORTAL", 1, LocalDateTime.now());

        srcSn = srcRepository.save(LsDataSrc.create(
                rawSn, 1L, 1L, null, deidRel, LocalDateTime.now())).getSrcSn();
    }

    private String url(Long sn) {
        return "/v1/portal/frames/" + sn + "/image";
    }

    @Test
    @DisplayName("포털_프레임_이미지_응답은_no_store_로_캐시되지_않는다")
    void portalFrameImageIsNotCached() throws Exception {
        // given: 데이터마트 노출(APPROVED) 영상의 비식별 프레임 — 정상 서빙 조건
        // when: PORTAL_USER 가 프레임 이미지를 조회
        MvcResult result = mockMvc.perform(get(url(srcSn))
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                // then: 게이트가 매 요청 평가되도록 클라이언트 캐시 재사용 금지 (CWE-359/525)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();

        // 구 정책(private, max-age=300)이면 여기서 실패한다 — 값 일치만으로는 회귀 의도가 드러나지 않으므로
        // "max-age 가 아예 없음"을 명시 단언한다.
        String cacheControl = result.getResponse().getHeader("Cache-Control");
        assertThat(cacheControl).isEqualTo("no-store");
        assertThat(cacheControl).doesNotContain("max-age");
        assertThat(result.getResponse().getContentAsByteArray())
                .isEqualTo(Files.readAllBytes(deidFramePath));
    }

    @Test
    @DisplayName("포털_프레임_이미지는_비식별_누락_신고_게이트_대상이라_신고중이면_412")
    void portalFrameImageBlockedUnderDeidentReport() throws Exception {
        // given: 조회 성공(캐시 대상) 이후 비식별 누락 신고 발생
        mockMvc.perform(get(url(srcSn)).header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk());

        LsDataRaw raw = rawRepository.findById(rawSn).orElseThrow();
        raw.markDeidentified("F");
        rawRepository.save(raw);

        // when/then: 서버는 즉시 412 — 이 경로가 게이트 대상임을 고정한다.
        // (no-store 가 아니면 브라우저가 이 412 에 도달하지 못하고 캐시본을 재노출한다)
        MvcResult result = mockMvc.perform(get(url(srcSn))
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isPreconditionFailed())
                .andReturn();
        assertThat(result.getResponse().getContentType()).doesNotContain("image/");
    }
}
