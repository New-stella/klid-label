package kr.co.cudo.authoring.video;

import jakarta.servlet.http.Cookie;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.security.StreamNonceCookie;
import kr.co.cudo.authoring.common.util.SeedImageGenerator;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.StreamUrlSigner;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-ISSUE-63 — 영상 자산 엔드포인트의 <b>영상 단위</b> 인가 (CWE-639 IDOR).
 *
 * <p>구 동작: 역할(REVIEWER/WORKER)만 검사해 배정 이력이 없는 WORKER 도 타인 배정 영상을 206 으로
 * 재생할 수 있었다. 이제 라벨/트랙 경로와 동일한 {@code LabelAccessGuard.verifyRawAccess} 기준
 * (REVIEWER 전체 / WORKER 본인 LABELER 배정)이 적용된다.
 *
 * <p><b>DEV_FIX H-1</b> — {@code /stream} 만 잠그면 같은 영상 자산을 반환하는 형제 경로
 * ({@code GET /{rawSn}}, {@code GET /{rawSn}/labels/auto}, {@code GET /{rawSn}/frames/{n}/image})로
 * 그대로 우회됐다. 본 테스트는 형제 경로마다 <b>차단(미배정 WORKER 403)과 허용(배정 WORKER·REVIEWER 200)</b>을
 * 쌍으로 검증한다 — 허용 케이스가 없으면 인가 추가가 정상 화면을 깨뜨려도 잡지 못한다.
 *
 * <p><b>DEV_FIX H-2</b> — {@code <video>} 가 실제로 타는 <b>무헤더 쿠키 경로</b>(ADR-071 — 발급자 봉인 쿠키 단독 판정)도 같은 기준으로 검증한다.
 * 기존 서명 테스트는 전부 REVIEWER(sub=1)라 인가 게이트가 얼리리턴으로 no-op 이었다.
 *
 * <p>시드 역할(V9001): 1=REVIEWER, 100=WORKER(본 테스트에서 배정), 101=WORKER(미배정).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoStreamAssignmentAuthorizationTest {

    private static final long ASSIGNED_WORKER = 100L;
    private static final long UNASSIGNED_WORKER = 101L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private StreamUrlSigner signer;
    @Autowired private StreamNonceCookie streamNonceCookie;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidPath;

    private Long rawSn;
    /** 공유 저장소에 쓴 비식별 영상 — {@link #cleanupSharedStorage()} 가 지운다. */
    private Path deidVideoPath;
    private static final int FILE_SIZE = 10_000;

    /** 서명 입력 nonce — 실제 발급 형식(32 hex)과 동일. */
    private static final String NONCE = "9876543210fedcba9876543210fedcba";

    private String tokenFor(long userNo, String role) {
        return JwtTestSupport.token(secret, String.valueOf(userNo), role, "INTERNAL", issuer, 60);
    }

    /** 서버가 내려보내는 것과 동일한 봉인 쿠키 (DEV_FIX M-1). */
    private Cookie sealedNonceCookie(String subject) {
        return new Cookie(StreamNonceCookie.COOKIE_NAME, streamNonceCookie.seal(NONCE, subject));
    }

    @BeforeEach
    void setup() throws IOException {
        String uniq = "STREAM-AUTHZ-" + System.nanoTime();
        String relPath = "stream-test/" + uniq + ".mp4";
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + uniq, "CCTV-STREAM", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, relPath,
                LocalDateTime.now(), 30);
        raw.markDeidentified("Y");
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        // 비식별 <영상> 규약 위치({deid}/videos/{rawSn}/) — 읽기 허용 base 서브트리(B-ISSUE-41).
        Path deidBase = Paths.get(storageDeidPath).toAbsolutePath().normalize();
        Path videoPath = deidBase.resolve("videos").resolve(String.valueOf(rawSn))
                .resolve(uniq + ".mp4").normalize();
        Files.createDirectories(videoPath.getParent());
        Files.write(videoPath, new byte[FILE_SIZE]);
        deidVideoPath = videoPath;

        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null,
                Paths.get(storageRawPath).resolve(relPath).toString(), "test");
        procLog.succeed(videoPath.toString());
        procLogRepository.save(procLog);

        // 형제 경로(프레임 이미지) 검증용 프레임 1건 + 실제 이미지 파일.
        String frameRel = "stream-test/" + uniq + "/frame_0.jpg";
        LsDataSrc src = LsDataSrc.create(rawSn, 0, frameRel, LocalDateTime.now());
        srcRepository.save(src);
        Path framePath = Paths.get(storageRawPath).toAbsolutePath().normalize().resolve(frameRel).normalize();
        Files.createDirectories(framePath.getParent());
        SeedImageGenerator.generate(framePath, "EVT_FALL", "CCTV-STREAM", 0, LocalDateTime.now());

        // WORKER 100 만 본 영상에 LABELER 로 배정한다.
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, ASSIGNED_WORKER, 1L));
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
        if (deidVideoPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(deidVideoPath);
        } catch (IOException ignored) {
            // 정리 실패는 무시한다(제품 결함이 아니다).
        }
    }

    @Test
    @DisplayName("배정되지_않은_WORKER_가_임의_rawSn_스트리밍시_403")
    void unassignedWorkerForbidden() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header("Authorization", "Bearer " + tokenFor(UNASSIGNED_WORKER, "WORKER"))
                        .header(HttpHeaders.RANGE, "bytes=0-100"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("배정되지_않은_WORKER_는_서명URL_발급도_403")
    void unassignedWorkerForbiddenOnStreamUrl() throws Exception {
        // 서명 URL 은 무헤더 재생권이므로 발급 단계에서 동일 기준으로 막아야 우회가 없다.
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + tokenFor(UNASSIGNED_WORKER, "WORKER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("배정된_WORKER_의_스트리밍은_정상_206")
    void assignedWorkerCanStream() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header("Authorization", "Bearer " + tokenFor(ASSIGNED_WORKER, "WORKER"))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent());
    }

    @Test
    @DisplayName("배정된_WORKER_는_서명URL_발급_200")
    void assignedWorkerCanIssueStreamUrl() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream-url")
                        .header("Authorization", "Bearer " + tokenFor(ASSIGNED_WORKER, "WORKER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REVIEWER_는_배정_무관_스트리밍_통과")
    void reviewerCanStreamWithoutAssignment() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .header("Authorization", "Bearer " + tokenFor(1L, "REVIEWER"))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent());
    }

    // ───────────── DEV_FIX H-1 — 형제 경로 전수 적용 (차단/허용 쌍) ─────────────

    @Test
    @DisplayName("배정되지_않은_WORKER_는_영상상세_403")
    void unassignedWorker_videoDetail_forbidden() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + tokenFor(UNASSIGNED_WORKER, "WORKER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("배정된_WORKER_의_영상상세_조회는_정상_200")
    void assignedWorker_videoDetail_ok() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + tokenFor(ASSIGNED_WORKER, "WORKER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REVIEWER_의_영상상세_조회는_배정_무관_200")
    void reviewer_videoDetail_ok() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + tokenFor(1L, "REVIEWER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("배정되지_않은_WORKER_는_영상별_라벨목록_403")
    void unassignedWorker_autoLabels_forbidden() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/labels/auto")
                        .header("Authorization", "Bearer " + tokenFor(UNASSIGNED_WORKER, "WORKER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("배정된_WORKER_의_영상별_라벨목록_조회는_정상_200")
    void assignedWorker_autoLabels_ok() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/labels/auto")
                        .header("Authorization", "Bearer " + tokenFor(ASSIGNED_WORKER, "WORKER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REVIEWER_의_영상별_라벨목록_조회는_배정_무관_200")
    void reviewer_autoLabels_ok() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/labels/auto")
                        .header("Authorization", "Bearer " + tokenFor(1L, "REVIEWER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("배정되지_않은_WORKER_는_프레임이미지_403")
    void unassignedWorker_frameImage_forbidden() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/frames/0/image")
                        .header("Authorization", "Bearer " + tokenFor(UNASSIGNED_WORKER, "WORKER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("배정된_WORKER_의_프레임이미지_조회는_정상_200")
    void assignedWorker_frameImage_ok() throws Exception {
        // 회귀 방어: 라벨링 화면이 매 프레임 호출하는 경로다. 인가 추가로 정상 작업자가 깨지면 안 된다.
        mockMvc.perform(get("/v1/videos/" + rawSn + "/frames/0/image")
                        .header("Authorization", "Bearer " + tokenFor(ASSIGNED_WORKER, "WORKER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REVIEWER_의_프레임이미지_조회는_배정_무관_200")
    void reviewer_frameImage_ok() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/frames/0/image")
                        .header("Authorization", "Bearer " + tokenFor(1L, "REVIEWER")))
                .andExpect(status().isOk());
    }

    // ───────────── DEV_FIX H-2 — 무헤더 쿠키 경로에도 배정 인가가 실제로 걸리는가 ─────────────
    //
    // 기존 서명 테스트는 전부 REVIEWER(sub=1)였다. REVIEWER 는 verifyRawAccess 를 얼리리턴으로 통과하므로
    // "서명 경로에도 인가가 걸린다"는 이번 수정의 핵심 주장이 하나도 검증되지 않았다.
    // ADR-071 이후 무헤더 재생의 인증은 발급자 봉인 쿠키 하나로 판정한다(주소의 exp·sig 는 판정에 쓰지 않음).
    // ★ 쿠키가 유효해도 권한 밖 영상은 거부되어야 한다 — 아래 403 단언을 지우지 말 것.

    @Test
    @DisplayName("★쿠키경로_유효쿠키라도_배정되지_않은_WORKER_는_403 — exp·sig 없이 u+쿠키만(ADR-071)")
    void cookieOnlyStream_unassignedWorker_forbidden() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("u", String.valueOf(UNASSIGNED_WORKER))
                        .cookie(sealedNonceCookie(String.valueOf(UNASSIGNED_WORKER)))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("쿠키경로_배정된_WORKER_는_exp_sig_없이도_206(ADR-071)")
    void cookieOnlyStream_assignedWorker_partialContent() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("u", String.valueOf(ASSIGNED_WORKER))
                        .cookie(sealedNonceCookie(String.valueOf(ASSIGNED_WORKER)))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent());
    }

    @Test
    @DisplayName("서명경로_배정되지_않은_WORKER_는_Authorization_없이도_403")
    void signedStream_unassignedWorker_forbidden() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, String.valueOf(UNASSIGNED_WORKER), NONCE);

        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", String.valueOf(UNASSIGNED_WORKER))
                        .param("sig", p.sig())
                        .cookie(sealedNonceCookie(String.valueOf(UNASSIGNED_WORKER)))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("서명경로_배정된_WORKER_는_Authorization_없이_206")
    void signedStream_assignedWorker_partialContent() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, String.valueOf(ASSIGNED_WORKER), NONCE);

        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", String.valueOf(ASSIGNED_WORKER))
                        .param("sig", p.sig())
                        .cookie(sealedNonceCookie(String.valueOf(ASSIGNED_WORKER)))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent());
    }

    @Test
    @DisplayName("서명경로_REVIEWER_는_배정_무관_206")
    void signedStream_reviewer_partialContent() throws Exception {
        StreamUrlSigner.SignedParams p = signer.sign(rawSn, "1", NONCE);

        mockMvc.perform(get("/v1/videos/" + rawSn + "/stream")
                        .param("exp", String.valueOf(p.exp()))
                        .param("u", "1")
                        .param("sig", p.sig())
                        .cookie(sealedNonceCookie("1"))
                        .header(HttpHeaders.RANGE, "bytes=0-1023"))
                .andExpect(status().isPartialContent());
    }
}
