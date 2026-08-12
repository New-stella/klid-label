package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VideoStreamController — HTTP Range 스트리밍 동작/보안 검증 (R3-1 회귀).
 *
 * <p>실 HTTP 직렬화 레이어(MockMvc)로 검증한다. 단위 테스트(VideoStreamServiceTest)는
 * ResponseEntity 객체만 단언하므로 ResourceRegion 직렬화 시 발생하는 500 / Accept-Ranges 누락을
 * 잡지 못한다 — 그 갭을 본 통합 테스트가 메운다.
 *
 * <ul>
 *   <li>Range 없음 → 200 + Accept-Ranges: bytes + 전체 바이트</li>
 *   <li>Range bytes=0-1023 → 206 + Content-Range + Accept-Ranges (RFC 7233)</li>
 *   <li>범위 밖 Range → 416 Range Not Satisfiable</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoStreamControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidPath;

    private String token;
    private Long rawSn;
    private Path videoPath;
    private static final int FILE_SIZE = 10_000;

    @BeforeEach
    void setup() throws IOException {
        token = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        String uniq = "STREAM-" + System.nanoTime();
        String relPath = "stream-test/" + uniq + ".mp4";
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + uniq, "CCTV-STREAM", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, relPath,
                LocalDateTime.now(), 30);
        // 비식별 완료 상태('Y') — 마킹 스트림 게이트(DE_IDNTF_YN='Y' 요구) 통과 전제.
        raw.markDeidentified("Y");
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        // Phase 2: 마킹 스트림은 비식별 영상을 서빙 → 비식별 <영상> 규약 위치({deid}/videos/{rawSn}/)에
        //          비식별 파일 + 성공 procLog 준비(B-ISSUE-41 — 읽기 허용 base 는 이 서브트리다).
        Path deidBase = Paths.get(storageDeidPath).toAbsolutePath().normalize();
        videoPath = deidBase.resolve("videos").resolve(String.valueOf(rawSn))
                .resolve(uniq + ".mp4").normalize();
        Files.createDirectories(videoPath.getParent());
        Files.write(videoPath, new byte[FILE_SIZE]);

        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null,
                Paths.get(storageRawPath).resolve(relPath).toString(), "test");
        procLog.succeed(videoPath.toString());
        procLogRepository.save(procLog);
    }

    /**
     * 공유 저장소에 쓴 비식별 영상을 되돌린다 — {@code @TempDir} 이 아니라
     * <b>프로젝트 상대 경로</b>({@code ./storage/deidentified})라 지우지 않으면 실행이 끝나도 남는다.
     *
     * <p>남기면 다음 실행에서 <b>같은 {@code RAW_SN} 을 받은 다른 테스트</b>가 이 파일을 자기 영상의
     * 산출물로 열거한다({@code RAW_SN} 은 테스트 DB 가 실행마다 새로 뜨므로 같은 값이 재발급된다).
     * 실제로 이 잔재가 비식별 신고 후보 목록의 건수 단언을 깨뜨린 사고가 있었다.
     *
     * <p>⚠ <b>자기가 만든 파일만 지우고 부모 디렉터리는 건드리지 않는다.</b> 이 경로는 실행 간 공유라
     * 다른 실행의 잔재가 같은 디렉터리에 함께 있을 수 있는데, {@code Files.deleteIfExists} 는 비어 있지
     * 않은 디렉터리에 {@code DirectoryNotEmptyException} 을 <b>던진다</b>(조용히 건너뛰지 않는다).
     * 그래서 부모까지 지우려던 구현이 이 클래스의 테스트 전량을 실패시킨 적이 있다.
     *
     * <p>또한 <b>정리 실패는 테스트를 실패시키지 않는다</b> — 정리는 위생이지 검증 대상이 아니다.
     */
    @AfterEach
    void cleanupSharedStorage() {
        if (videoPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(videoPath);
        } catch (IOException ignored) {
            // 정리 실패는 무시한다(제품 결함이 아니다).
        }
    }

    @Test
    @DisplayName("스트리밍_Range_없음_200_AcceptRanges_헤더_노출")
    void fullRequest_200_acceptRanges() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }

    @Test
    @DisplayName("스트리밍_Range_요청시_206_PartialContent_ContentRange_AcceptRanges")
    void rangeRequest_206_contentRange() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header("Authorization", "Bearer " + token)
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-1023/" + FILE_SIZE))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 1024L));
    }

    @Test
    @DisplayName("스트리밍_열린_Range_요청시_206_파일끝까지")
    void openEndedRange_206() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header("Authorization", "Bearer " + token)
                        .header(HttpHeaders.RANGE, "bytes=5000-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }

    @Test
    @DisplayName("스트리밍_범위_밖_Range_요청시_416_RangeNotSatisfiable")
    void rangeOutOfBounds_416() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header("Authorization", "Bearer " + token)
                        .header(HttpHeaders.RANGE, "bytes=" + (FILE_SIZE + 1000) + "-" + (FILE_SIZE + 2000)))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    @DisplayName("스트리밍_비인증_요청시_401")
    void unauthorized_401() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isUnauthorized());
    }
}
