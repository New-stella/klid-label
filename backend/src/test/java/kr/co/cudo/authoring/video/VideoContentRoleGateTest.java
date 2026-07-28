package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 역할 분리 Phase 3 보안(관찰-1) — 비식별 영상 콘텐츠 읽기 엔드포인트가
 * 저작도구 역할 미배정(role=null) INTERNAL 사용자에게 노출되지 않음을 검증한다.
 *
 * <p>배경: Phase 3 에서 인가 역할을 LS_USER_ROLE 조회로 해석하면서, JWT 검증은 통과했으나
 * LS 에 역할이 없는 'role=null INTERNAL' 상태가 생겼다. 이 사용자는 인증은 되었지만
 * 저작도구 작업 콘텐츠(영상/프레임)에 접근할 수 없어야 한다.
 *
 * <p>이전에는 {@code @PreAuthorize("isAuthenticated()")} 였으므로 role=null 사용자도
 * 영상 목록/스트림/프레임 이미지를 GET 조회할 수 있었다(노출). 본 테스트는 이를
 * {@code hasAnyRole('REVIEWER','WORKER')} 로 게이트해 403 이 되는지 확인한다.
 *
 * <p>role=null 토큰: INTERNAL 채널 + LS_USER_ROLE 미시드 sub(770001).
 * REVIEWER=sub 1, WORKER=sub 100 (V9001 시드, SecurityRoleResolutionPhase3Test 패턴).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoContentRoleGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Value("${authoring.jwt.secret}")
    private String secret;

    @Value("${authoring.jwt.issuer}")
    private String issuer;

    /** LS 미배정 sub — 인증은 되지만 저작도구 역할 없음(role=null). */
    private String unassignedToken() {
        return JwtTestSupport.token(secret, "770001", "REVIEWER", "INTERNAL", issuer, 60);
    }

    private String workerToken() {
        return JwtTestSupport.token(secret, "100", "WORKER", "INTERNAL", issuer, 60);
    }

    private String reviewerToken() {
        return JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    /**
     * sub-스푸핑 토큰 — subject 를 합성 식별자("stream-signed")로 위장한 정상 서명 JWT.
     * <p>INTERNAL 채널이라 sub 가 비숫자면 LS 역할 미해석(role=null)이 되어 STREAM_SIGNED 권한은
     * 부여되지 않는다. 인가가 principal.sub() 값에 의존하면 이 토큰이 /stream 게이트를 뚫는다(취약).
     */
    private String spoofedStreamSubToken() {
        return JwtTestSupport.token(secret, "stream-signed", "REVIEWER", "INTERNAL", issuer, 60);
    }

    @Test
    @DisplayName("미배정_role_null_사용자는_영상목록_403")
    void unassignedForbiddenOnVideoList() throws Exception {
        // given: LS 미배정(role=null) INTERNAL 토큰
        // when/then: 영상 목록 GET → 403 (인증은 됐으나 역할 없음)
        mockMvc.perform(get("/v1/videos").header("Authorization", "Bearer " + unassignedToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미배정_role_null_사용자는_영상_stream_403")
    void unassignedForbiddenOnStream() throws Exception {
        // given: LS 미배정(role=null) INTERNAL 토큰
        // when/then: 영상 스트림 GET → 403 (역할 게이트가 데이터 조회 이전에 차단)
        mockMvc.perform(get("/v1/videos/1/stream").header("Authorization", "Bearer " + unassignedToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미배정_role_null_사용자는_프레임이미지_403")
    void unassignedForbiddenOnFrameImage() throws Exception {
        // given: LS 미배정(role=null) INTERNAL 토큰
        // when/then: 프레임 이미지 GET → 403
        mockMvc.perform(get("/v1/videos/1/frames/0/image").header("Authorization", "Bearer " + unassignedToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("WORKER는_영상목록_200")
    void workerOkOnVideoList() throws Exception {
        // given: LS 시드 sub=100 → WORKER
        // when/then: 영상 목록 GET → 200 (빈 페이지여도 정상)
        mockMvc.perform(get("/v1/videos").header("Authorization", "Bearer " + workerToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("배정되지_않은_WORKER_가_임의_rawSn_스트리밍시_403")
    void unassignedWorkerForbiddenOnArbitraryStream() throws Exception {
        // B-ISSUE-63 (CWE-639 IDOR) — 구 동작은 역할만 검사해서 배정 이력이 전혀 없는 WORKER 도
        // 임의 영상을 재생할 수 있었다(존재하는 rawSn 은 206, 없는 rawSn 은 404).
        // 이제 영상 단위 인가가 먼저 걸리므로 배정되지 않은 WORKER 는 rawSn 존재 여부와 무관하게 403 이다
        // (존재 여부를 알려주는 oracle 도 함께 제거된다).
        mockMvc.perform(get("/v1/videos/999999/stream").header("Authorization", "Bearer " + workerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("STREAM_SIGNED_권한없는_sub_stream_가짜접근_차단")
    void spoofedStreamSubForbiddenOnStream() throws Exception {
        // given: subject 를 합성 식별자("stream-signed")로 위장한 정상 서명 JWT (서명 쿼리는 없음)
        // when/then: /stream 인가가 sub 값이 아닌 STREAM_SIGNED 권한 기반이므로 위장은 무력 → 403
        //   (JWT 발급 경로는 ROLE_*/CHANNEL_* 만 부여, STREAM_SIGNED 권한은 절대 합성 불가)
        mockMvc.perform(get("/v1/videos/1/stream").header("Authorization", "Bearer " + spoofedStreamSubToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REVIEWER는_영상목록_200")
    void reviewerOkOnVideoList() throws Exception {
        // given: LS 시드 sub=1 → REVIEWER
        // when/then: 영상 목록 GET → 200
        mockMvc.perform(get("/v1/videos").header("Authorization", "Bearer " + reviewerToken()))
                .andExpect(status().isOk());
    }
}
