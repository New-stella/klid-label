package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.auth.entity.LsMngrPswd;
import kr.co.cudo.authoring.auth.repository.LsMngrPswdRepository;
import kr.co.cudo.authoring.auth.service.AdminPasswordVerifier;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
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

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 단기 유효창 <b>발급 창구</b>의 역할 축 (@design AC-072 · ROLE-004 · ADR-055).
 *
 * <h3>왜 이 창구의 역할이 실질 경계인가</h3>
 * <p>유효창을 <b>발급받지 못하면 소비도 못 한다.</b> 그래서 이 창구를 검수자에게 열어 두면,
 * 소비처의 역할 게이트가 무엇이든 <b>관리자 공유 패스워드를 아는 검수자</b>가 관리 성격의 쓰기에
 * 닿는다(관리자 패스워드 교체 자체를 포함해서). 유효창이 역할을 승격시키지 않는다는 성질은 이미
 * 고정돼 있지만, 그것만으로는 이 경로가 닫히지 않는다.
 *
 * <p>★ 관리자 역할 행은 이 시험이 직접 심고 {@code @Transactional} 이 되돌린다. 공유 DB 에 관리자
 * 행이 <b>남으면</b> 부트스트랩 창구를 검증하는 시험들이 409 로 뒤집히므로 커밋되게 두지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AdminSessionRoleGateIT {

    private static final String PATH = "/v1/manage/admin-session";

    /** 시드 사용자와 겹치지 않는 대역 — 겹치면 역할 해석 캐시가 남의 키를 쓴다. */
    private static final AtomicInteger SUBJECT_SEQ = new AtomicInteger(9_600_000);

    @Autowired private MockMvc mockMvc;
    @Autowired private LsUserRoleRepository lsUserRoleRepository;
    @Autowired private LsMngrPswdRepository mngrPswdRepository;
    @Autowired private UserRoleResolver userRoleResolver;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String adminSub;
    private String reviewerSub;
    private String adminPlaintext;

    @BeforeEach
    void seed() {
        adminSub = String.valueOf(SUBJECT_SEQ.incrementAndGet());
        reviewerSub = String.valueOf(SUBJECT_SEQ.incrementAndGet());
        lsUserRoleRepository.save(LsUserRole.of(Long.valueOf(adminSub), "ADMIN"));
        lsUserRoleRepository.save(LsUserRole.of(Long.valueOf(reviewerSub), "REVIEWER"));
        userRoleResolver.evict(Long.valueOf(adminSub));
        userRoleResolver.evict(Long.valueOf(reviewerSub));

        // 평문 상수를 두지 않는다(Fortify Hardcoded Password) — 매번 새로 뽑아 해시만 심는다.
        byte[] pw = new byte[24];
        new SecureRandom().nextBytes(pw);
        adminPlaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(pw);
        String hash = new BCryptPasswordEncoder(AdminPasswordVerifier.BCRYPT_COST).encode(adminPlaintext);
        LsMngrPswd row = mngrPswdRepository.findById(LsMngrPswd.SINGLE_ROW_SN).orElse(null);
        if (row == null) {
            mngrPswdRepository.save(LsMngrPswd.of(hash, "1", LocalDateTime.now()));
        } else {
            row.changeHash(hash, "1", LocalDateTime.now());
            mngrPswdRepository.save(row);
        }
    }

    private String token(String sub, String roleLabel) {
        return JwtTestSupport.token(secret, sub, roleLabel, "INTERNAL", issuer, 60);
    }

    private String body() {
        return "{\"adminPassword\":\"" + adminPlaintext + "\"}";
    }

    @Test
    @DisplayName("★관리자는_유효창을_연다_부트스트랩_직후_최초_관리자도_이_경로로_성립한다")
    void adminOpensSession() throws Exception {
        // 부트스트랩 직후 최초 관리자는 이미 ADMIN 이므로 이 창구가 열려 있어야 한다 —
        //   닫혀 있으면 관리 기능 전체가 영구히 잠긴다(유효창을 얻을 길이 없다).
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + token(adminSub, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★검수자는_패스워드가_맞아도_유효창을_열_수_없다_403")
    void reviewerCannotOpenSession() throws Exception {
        // 이 창구가 검수자에게 열려 있으면, 소비처 게이트가 무엇이든 관리자 패스워드를 아는
        //   검수자가 관리 성격의 쓰기에 닿는다. 발급을 막는 것이 실질 경계다.
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + token(reviewerSub, "REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isForbidden());
    }
}
