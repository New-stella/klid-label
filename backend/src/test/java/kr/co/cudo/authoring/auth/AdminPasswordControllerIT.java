package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.auth.entity.LsMngrPswd;
import kr.co.cudo.authoring.auth.repository.LsMngrPswdRepository;
import kr.co.cudo.authoring.auth.service.AdminPasswordVerifier;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 패스워드 교체 창구. [@design API-223] [@design AC-121] [@design AC-123]
 *
 * <p>★ {@code @SpringBootTest + @AutoConfigureMockMvc} 여야 한다 — standalone MockMvc 는 유효창
 * 인터셉터가 배선되지 않아 <b>게이트가 아예 돌지 않고</b> 전건 통과한다(무방비인 채 초록).
 *
 * <h3>자격을 배포 설정에서 가져오지 않는다</h3>
 * <p>배포 설정 자격의 <b>평문은 저장소에 없다</b>(해시만 있고 평문은 별도 채널로만 공유한다). 그래서
 * 시험이 아는 자격을 <b>저장소 행으로 직접 심는다</b> — 저장소가 배포 설정을 이기므로 그 값이 곧
 * 현재 자격이 되고, 덤으로 「저장소 우선」 갈래까지 이 경로에서 함께 확인된다.
 *
 * <p>{@code @Transactional} 로 각 시험이 되돌려진다. 자격은 전역 값이라 되돌리지 않으면 뒤에 도는
 * 시험들이 바뀐 자격 위에서 돈다.
 *
 * <h3>★자격 주체(sub)는 시험마다 다르다 — 속도 제한의 계정 축을 분리한다</h3>
 * <p>이 창구는 유효창 발급과 <b>같은 속도 제한 축</b>을 쓰고(확정된 설계 의도다), 그 계정 축 한도는
 * 분당 5회다. 그런데 제한기의 로컬 카운터는 싱글턴 빈이라 {@code @Transactional} 롤백에 <b>되돌려지지
 * 않는다</b> — 컨텍스트 캐시로 같은 빈이 계속 살아 있다. 전 시험이 같은 sub 를 쓰면 소비가 한 계정에
 * 쌓이고, 소비를 유발하는 시험이 하나만 더 늘어도 <b>뒤 시험이 429 로 깨진다</b>(이 저장소에 분 버킷
 * 플레이크 전례가 있다).
 *
 * <p>그래서 시험마다 새 sub 를 뽑는다. <b>프로덕션 설정·판정 순서·버킷 구조는 손대지 않는다</b> —
 * 축 공유는 되돌리면 안 되는 설계 결정이고, 여기서 고치는 것은 시험이 그 축을 좁게 쓰는 방식뿐이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AdminPasswordControllerIT {

    private static final String PATH = "/v1/manage/admin-password";
    private static final String CURRENT = "seeded-admin-secret";
    private static final String NEXT = "rotated-admin-secret";

    /**
     * 시험마다 새 자격 주체를 뽑는 발권기 (위 javadoc 의 계정 축 분리). 값은 {@code MDFR_ID}
     * (varchar 30) 에 그대로 적재되므로 짧게 유지한다 — 메서드 이름을 쓰면 폭을 넘긴다.
     *
     * <p>시드 사용자(1·100 등)와 겹치지 않는 대역에서 뽑는다. 겹치면 역할 해석 캐시가 다른 시험의
     * 사용자와 같은 키를 쓰게 된다.
     */
    private static final AtomicInteger SUBJECT_SEQ = new AtomicInteger(9_100_000);

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminSessionTokenService adminSessionTokenService;
    @Autowired private LsMngrPswdRepository mngrPswdRepository;
    @Autowired private LsUserRoleRepository lsUserRoleRepository;
    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    /** 이 시험의 검수자 주체. 속도 제한 계정 축이 시험마다 갈리도록 매번 새로 뽑는다. */
    private String reviewerSub;
    /** 이 시험의 작업자 주체. 검수자와도, 다른 시험과도 겹치지 않는다. */
    private String workerSub;
    /**
     * 이 시험의 <b>관리자</b> 주체 — 이 창구의 정상 호출자다(ADR-055 · AC-072 · ROLE-004).
     * ★{@code @Transactional} 이 이 역할 행을 되돌린다. 관리자 행이 공유 DB 에 <b>남으면</b>
     * 부트스트랩 창구를 검증하는 시험들이 409 로 뒤집히므로 절대 커밋되게 두지 말 것.
     */
    private String adminSub;

    @BeforeEach
    void seedCredential() {
        reviewerSub = String.valueOf(SUBJECT_SEQ.incrementAndGet());
        workerSub = String.valueOf(SUBJECT_SEQ.incrementAndGet());
        adminSub = String.valueOf(SUBJECT_SEQ.incrementAndGet());
        // 인가 역할은 JWT 의 role 클레임이 아니라 LS_USER_ROLE 조회로 해석된다(Phase 3). 새 주체를
        // 뽑았으면 그 역할도 함께 심어야 한다 — 안 심으면 role=null 로 fail-closed 되어 이 시험이
        // <검증하려던 것과 다른 이유로> 403 을 받는다. @Transactional 이 함께 되돌린다.
        lsUserRoleRepository.save(LsUserRole.of(Long.valueOf(reviewerSub), "REVIEWER"));
        lsUserRoleRepository.save(LsUserRole.of(Long.valueOf(workerSub), "WORKER"));
        lsUserRoleRepository.save(LsUserRole.of(Long.valueOf(adminSub), "ADMIN"));

        String hash = new BCryptPasswordEncoder(AdminPasswordVerifier.BCRYPT_COST).encode(CURRENT);
        LsMngrPswd row = mngrPswdRepository.findById(LsMngrPswd.SINGLE_ROW_SN).orElse(null);
        if (row == null) {
            mngrPswdRepository.save(LsMngrPswd.of(hash, "1", LocalDateTime.now()));
        } else {
            row.changeHash(hash, "1", LocalDateTime.now());
            mngrPswdRepository.save(row);
        }
    }

    private String reviewerToken() {
        return JwtTestSupport.token(secret, reviewerSub, "REVIEWER", "INTERNAL", issuer, 60);
    }

    private String workerToken() {
        return JwtTestSupport.token(secret, workerSub, "WORKER", "INTERNAL", issuer, 60);
    }

    /** 이 창구의 정상 호출자 — 관리자. */
    private String adminToken() {
        return JwtTestSupport.token(secret, adminSub, "ADMIN", "INTERNAL", issuer, 60);
    }

    private String adminSession() {
        return adminSession(adminSub);
    }

    /**
     * 유효창은 <b>발급받은 주체에게만</b> 유효하므로 주체를 명시해 발급한다. 작업자 시험이 자기
     * 주체의 유효창을 들고 가야 「유효창은 있는데 역할이 없어서 막힌다」가 실제로 검증된다 —
     * 다른 주체의 유효창을 들려 보내면 게이트의 주체 결박에서 먼저 걸려, 역할 판정에 닿지 못한 채
     * 같은 403 이 나와 <b>시험이 이름과 다른 것을 본다</b>.
     */
    private String adminSession(String subject) {
        return adminSessionTokenService.issue(subject, Instant.now()).token();
    }

    private static String body(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    @Test
    @DisplayName("★관리자_유효창_없이는_403 — 관리자_권한만으로는_열리지_않는다")
    void withoutAdminSessionForbidden() throws Exception {
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, NEXT)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★REVIEWER는_유효창을_들고_와도_403 — 관리자_패스워드_교체는_관리자_전용이다")
    void reviewerWithSessionForbidden() throws Exception {
        // ADR-055 · ROLE-004(SCREEN-041) — 검수자에게 열어 두면 검수자가 관리자 진입 자격 자체를
        //   갈아치울 수 있어 권한 분리가 성립하지 않는다. 유효창은 역할을 올리지 않으므로
        //   <자기 주체의 유효창>을 들고 와도 막힌다.
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + reviewerToken())
                        .header(AdminSessionGate.HEADER, adminSession(reviewerSub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, NEXT)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("WORKER는_유효창을_들고_와도_403 — 유효창은_역할을_승격시키지_않는다")
    void workerForbidden() throws Exception {
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + workerToken())
                        .header(AdminSessionGate.HEADER, adminSession(workerSub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, NEXT)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★유효창이_있어도_현재_패스워드가_틀리면_401 — 세션_탈취가_자격_완전_탈취가_되지_않는다")
    void wrongCurrentPasswordUnauthorized() throws Exception {
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("wrong-current-secret", NEXT)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("새_패스워드가_현재_값과_같으면_400")
    void unchangedPasswordBadRequest() throws Exception {
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, CURRENT)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("새_패스워드가_8자_미만이면_400")
    void tooShortNewPasswordBadRequest() throws Exception {
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("★교체_성공시_응답에_값도_해시도_없고_그_전_유효창이_전부_무효가_된다 (AC-121 · AC-123)")
    void changeSucceedsAndInvalidatesPreviousSessions() throws Exception {
        String sessionUsedForChange = adminSession();

        String responseBody = mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, sessionUsedForChange)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CURRENT, NEXT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(NEXT);
        assertThat(responseBody).doesNotContain(CURRENT);
        assertThat(responseBody).doesNotContain("$2");

        // 방금 교체에 쓴 그 유효창도 함께 끊긴다 — 예외 갈래가 없다.
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, sessionUsedForChange)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(NEXT, "yet-another-secret")))
                .andExpect(status().isForbidden());

        // 새 자격으로 다시 열면 정상 동작한다 — 무효화가 기능을 죽이는 것이 아니다.
        mockMvc.perform(put(PATH)
                        .header("Authorization", "Bearer " + adminToken())
                        .header(AdminSessionGate.HEADER, adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(NEXT, "yet-another-secret")))
                .andExpect(status().isOk());
    }
}
