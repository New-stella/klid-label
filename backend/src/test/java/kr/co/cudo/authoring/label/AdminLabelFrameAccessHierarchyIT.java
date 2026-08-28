package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>종단(HTTP) 회귀 가드</b> — 관리자가 라벨링 프레임 경로에 실제로 도달하고, 그 자리에서
 * 원본이 아니라 비식별 프레임을 받는다.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125] [design: API-021]
 *
 * <h3>왜 종단인가 (단위 시험이 이미 있는데도)</h3>
 * <p>이 축은 <b>두 판정이 직렬로 걸려 있다</b> — ①{@code LabelAccessGuard} 의 역할 인가(계층을
 * 적용해야 관리자가 통과한다) ②{@code FrameImageService.serveFrame} 의 원본 서빙 판정(계층을
 * <b>적용하면 안 된다</b>). 방향이 정반대라 어느 한쪽만 단위로 보면 반대쪽 실수를 못 잡는다.
 * 실제로 영상 도메인은 ②를 먼저 고정했지만 ①이 막고 있어 관리자는 <b>바이트를 한 번도 받지
 * 못했고</b>, 그래서 그쪽 시험은 종단으로 세울 수 없다는 사실을 주석으로 남겨 두었다. 이 시험이
 * 그 숙제를 닫는다.
 *
 * <h3>상태코드만 단언하지 않는 이유</h3>
 * <p>원본도 비식별도 <b>둘 다 200</b> 이라 {@code isOk()} 만으로는 유출을 구분하지 못한다. 두 벌에
 * <b>길이가 다른 바이트</b>를 심고 양방향으로 단언한다 — 「비식별과 같다」 + 「원본과 다르다」.
 *
 * <h3>대조군</h3>
 * <p>같은 픽스처로 검수자의 {@code raw=true} 가 원본을 받는 것을 함께 고정한다. 원본 분기가 애초에
 * 도달 불가면 관리자 단언은 항상 참이 되어 아무것도 지키지 않는다.
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <ul>
 *   <li>{@code LabelAccessGuard} 를 동등 비교로 되돌리면 → 관리자 요청이 <b>403</b> 이 되어 FAILED.</li>
 *   <li>{@code FrameImageService.serveFrame} 을 {@code hasRole} 로 바꾸면 → 관리자에게 원본 바이트가
 *       나가 FAILED.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AdminLabelFrameAccessHierarchyIT {

    /** 비식별 프레임 픽셀 대역. */
    private static final byte[] DEID_BYTES =
            ("ÿØÿ" + "DEID").getBytes(StandardCharsets.ISO_8859_1);
    /** 원본 프레임 픽셀 대역 — 길이가 비식별본과 다르도록 일부러 어긋나게 둔다. */
    private static final byte[] RAW_BYTES =
            ("ÿØÿ" + "RAW-ORIGINAL-PIXELS-NOT-FOR-ADMIN").getBytes(StandardCharsets.ISO_8859_1);

    /**
     * 이 시험 전용 관리자 사용자번호 — 공용 시드·다른 시험과 겹치지 않는 대역.
     *
     * <p>★ 공용 시드에 관리자를 넣지 않는 이유: 시스템에 관리자가 <b>항상</b> 있는 셈이 되어 관리자
     * 부트스트랩 창구(관리자 0명일 때만 열린다)가 영구히 닫히고, 그 창구를 검증하는 시험들이 통째로
     * 깨진다. 그래서 이 클래스가 직접 심고 <b>반드시 지운다</b>.
     */
    private static final long ADMIN_NO = 969_300_031L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private UserRoleResolver userRoleResolver;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidentifiedPath;

    private JdbcTemplate jdbc;
    private String adminToken;
    private String reviewerToken;
    private Path rawBase;
    private Path deidBase;
    private String unique;

    @BeforeEach
    void setUp() {
        // JWT 의 role 클레임은 인가에 쓰이지 않는다(LS_USER_ROLE 이 진실원) — 값은 표기일 뿐이다.
        adminToken = JwtTestSupport.token(secret, String.valueOf(ADMIN_NO), "ADMIN", "INTERNAL", issuer, 60);
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        rawBase = Paths.get(storageRawPath).toAbsolutePath().normalize();
        deidBase = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        unique = "alfah-" + System.nanoTime();

        // @Sql(BEFORE_TEST_METHOD) 가 LS_USER_ROLE 을 통째로 지우고 재삽입한 <이후>에 심어야 살아남는다
        // (Spring 의 스크립트 실행이 @BeforeEach 보다 앞선다).
        jdbc = new JdbcTemplate(controlDataSource);
        clearAdminRole();
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'ADMIN', CURRENT_TIMESTAMP)",
                ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
    }

    @AfterEach
    void tearDown() {
        clearAdminRole();
    }

    private void clearAdminRole() {
        jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", ADMIN_NO);
        jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", ADMIN_NO);
        userRoleResolver.evict(ADMIN_NO);
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * 원본·비식별 두 벌이 모두 준비된 프레임 1건. 관리자에게는 <b>배정을 심지 않는다</b> —
     * 배정이 있으면 작업자 분기로도 통과해 "계층으로 통과했다"가 증명되지 않는다.
     */
    private LsDataSrc seedFrameWithBothCopies() throws IOException {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + unique, "CCTV-ALFAH", "EVT_FALL", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/" + unique + ".mp4", LocalDateTime.now(), 30));

        String rawRel = "frames/raw/" + raw.getRawSn() + "/" + unique + ".jpg";
        Path rawFile = rawBase.resolve(rawRel).normalize();
        Files.createDirectories(rawFile.getParent());
        Files.write(rawFile, RAW_BYTES);

        String deidRel = "frames/deid/" + raw.getRawSn() + "/" + unique + ".jpg";
        Path deidFile = deidBase.resolve(deidRel).normalize();
        Files.createDirectories(deidFile.getParent());
        Files.write(deidFile, DEID_BYTES);

        return srcRepository.save(
                LsDataSrc.create(raw.getRawSn(), 0, null, rawRel, deidRel, LocalDateTime.now()));
    }

    private byte[] fetch(long srcSn, String token, boolean raw) throws Exception {
        return mockMvc.perform(get("/v1/frames/" + srcSn + "/image")
                        .queryParam("raw", String.valueOf(raw))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    // ---------------------------------------------------------------- 본체

    @Test
    @DisplayName("★관리자는_배정이_없어도_프레임_이미지를_받고_raw_true_는_무시되어_비식별_바이트가_나간다")
    void adminReachesFrameAndGetsDeidBytes() throws Exception {
        LsDataSrc src = seedFrameWithBothCopies();

        // ① 인가 축 — 이관 전에는 여기서 403 이었다(LabelAccessGuard 가 관리자를 fall-through 로 떨어뜨렸다).
        // ② 개인정보 축 — 그 뒤 서빙 판정은 계층을 <b>적용하지 않는다</b>. 두 축이 정반대 방향이다.
        byte[] body = fetch(src.getSrcSn(), adminToken, true);

        assertThat(body)
                .as("관리자에게는 비식별 프레임이 나가야 한다")
                .isEqualTo(DEID_BYTES);
        assertThat(body)
                .as("원본 픽셀이 관리자에게 새면 안 된다 (CWE-359) — 상태코드는 둘 다 200 이라 구분되지 않는다")
                .isNotEqualTo(RAW_BYTES);
    }

    @Test
    @DisplayName("관리자가_raw_를_요청하지_않아도_비식별_바이트가_나간다")
    void adminWithoutRawGetsDeidBytes() throws Exception {
        LsDataSrc src = seedFrameWithBothCopies();

        assertThat(fetch(src.getSrcSn(), adminToken, false)).isEqualTo(DEID_BYTES);
    }

    @Test
    @DisplayName("검수자의_raw_true_는_원본_바이트가_나간다_원본_분기가_도달_가능함을_고정")
    void reviewerRawStillServesOriginal() throws Exception {
        // 이 대조군이 없으면 위 관리자 단언은 "원본 분기가 애초에 죽어 있어도" 통과한다(항상 참인 시험).
        LsDataSrc src = seedFrameWithBothCopies();

        assertThat(fetch(src.getSrcSn(), reviewerToken, true)).isEqualTo(RAW_BYTES);
    }
}
