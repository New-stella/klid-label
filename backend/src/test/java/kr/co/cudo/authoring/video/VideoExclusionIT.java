package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>영상 제외·복원</b> 회귀 가드 — 정상 경로 · 멱등 · 배정 충돌 · 인가 · 사유.
 * [@design ADR-069] [@design API-260] [@design API-261] [@design API-042]
 * [@design AC-1124] [@design AC-1125] [@design AC-1126] [@design AC-1127]
 *
 * <h2>경계(배치·관제·산출물 무변경)는 여기가 아니다</h2>
 * 그 축은 {@code VideoExclusionBoundaryIT} 와 {@code VideoExclusionBoundarySourceGuardTest} 가 담는다 —
 * 「되는 것」과 「안 되는 것」은 서로를 대신하지 못해 같은 클래스에 섞으면 한쪽이 묻힌다.
 *
 * <h2>시험을 짤 때 빠지기 쉬운 자리 — 이 클래스가 의식적으로 덮는 것</h2>
 * <ul>
 *   <li>되풀이 호출이 <b>성공으로 끝나는 것만</b> 확인하고 원장을 보지 않으면, 이력을 매번 남기는
 *       구현도 통과한다 → 성공 여부와 <b>이력 행 수</b>를 한 시험 안에서 함께 본다.</li>
 *   <li>「제외됨 N건」을 필터 없이만 보면 <b>필터 범위를 무시하고 전체를 세는</b> 구현이 통과한다
 *       → 필터를 건 채로 확인한다.</li>
 *   <li>0건 상태를 별도 사례로 두지 않으면 <b>0 일 때 키가 빠지는</b> 구현이 통과한다.</li>
 *   <li>멱등 성공과 배정 충돌 거부는 <b>같은 지점</b>(갱신 0행)에서 갈린다 → 한쪽만 시험하면 반대쪽이
 *       조용히 뒤집혀도 통과하므로 두 사례를 같은 묶음에 둔다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoExclusionIT {

    /**
     * 이 시험 전용 사용자번호 — 공용 시드·다른 시험과 겹치지 않는 대역.
     *
     * <p>★<b>인가의 진실원은 JWT 의 역할 클레임이 아니라 {@code LS_USER_ROLE} 이다.</b> 토큰의 역할
     * 문자열은 표기일 뿐이라, 심지 않으면 관리자 토큰도 검수자 전용 창구에서 막힌다(실측). 공용 시드에
     * 관리자를 넣지 않는 이유는 관리자 부트스트랩 창구가 영구히 닫히기 때문이므로 <b>여기서 심고 반드시
     * 지운다</b>.
     */
    private static final long REVIEWER_NO = 969_300_021L;
    private static final long WORKER_NO = 969_300_022L;
    private static final long ADMIN_NO = 969_300_023L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private LsTaskEventLogRepository taskEventLogRepository;
    @Autowired private kr.co.cudo.authoring.common.security.UserRoleResolver userRoleResolver;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("controlDataSource")
    private javax.sql.DataSource controlDataSource;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String reviewerToken;
    private String adminToken;
    private String workerToken;

    /** 이 클래스가 심은 영상만 고르는 검색어 — 다른 시드에 기대 건수가 종속되지 않게 한다. */
    private String tag;

    private Long alpha;
    private Long beta;
    private Long assigned;

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, String.valueOf(REVIEWER_NO), "REVIEWER", "INTERNAL", issuer, 60);
        adminToken = JwtTestSupport.token(secret, String.valueOf(ADMIN_NO), "ADMIN", "INTERNAL", issuer, 60);
        workerToken = JwtTestSupport.token(secret, String.valueOf(WORKER_NO), "WORKER", "INTERNAL", issuer, 60);

        jdbc = new org.springframework.jdbc.core.JdbcTemplate(controlDataSource);
        clearRoles();
        // ★관리자에게 <b>검수자 역할을 따로 부여하지 않는다</b> — 미리 부여해 두면 역할 계층이 끊겨도
        //   관리자 시험이 통과한다. 작업자도 실제로 작업자 역할을 갖게 심는다 — 역할을 아예 안 주면
        //   403 이 「작업자라서」가 아니라 「무권한이라서」 나므로 인가 시험이 의미를 잃는다.
        grantRole(REVIEWER_NO, "REVIEWER");
        grantRole(WORKER_NO, "WORKER");
        grantRole(ADMIN_NO, "ADMIN");

        tag = "exclit" + System.nanoTime();

        alpha = saveVideo();
        beta = saveVideo();
        assigned = saveVideo();
        assignmentRepository.save(LsTaskAssignment.createLabeler(assigned, WORKER_NO, REVIEWER_NO));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        clearRoles();
    }

    private void grantRole(long userNo, String roleCd) {
        jdbc.update("INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, ?, CURRENT_TIMESTAMP)",
                userNo, roleCd);
        userRoleResolver.evict(userNo);
    }

    private void clearRoles() {
        for (long userNo : new long[]{REVIEWER_NO, WORKER_NO, ADMIN_NO}) {
            jdbc.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", userNo);
            jdbc.update("DELETE FROM LS_ACNT_USER WHERE USER_NO = ?", userNo);
            userRoleResolver.evict(userNo);
        }
    }

    private Long saveVideo() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + tag + "-" + System.nanoTime(), tag, "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
        return videoRepository.save(raw).getRawSn();
    }

    private ResultActions list(String extraQuery) throws Exception {
        return mockMvc.perform(get("/v1/videos?size=50&cctvNameKeyword=" + tag + extraQuery)
                .header("Authorization", "Bearer " + reviewerToken));
    }

    private ResultActions exclude(Long rawSn, String token, String reason) throws Exception {
        return mockMvc.perform(post("/v1/videos/" + rawSn + "/exclusion")
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content("{\"reason\":" + quote(reason) + "}"));
    }

    private ResultActions restore(Long rawSn, String token) throws Exception {
        return mockMvc.perform(delete("/v1/videos/" + rawSn + "/exclusion")
                .header("Authorization", "Bearer " + token));
    }

    /** JSON 문자열 리터럴 — 제어문자를 <b>그대로 실어 보내야</b> 하므로 직접 이스케이프한다. */
    private static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private List<LsTaskEventLog> events(Long rawSn) {
        return taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(rawSn);
    }

    private String exclFlag(Long rawSn) {
        return videoRepository.findExclYnByRawSn(rawSn).orElse(null);
    }

    // ---------------------------------------------------------------- 정상 경로

    @Test
    @DisplayName("★제외하면_목록에서_사라지고_전체건수도_함께_줄며_복원하면_되돌아온다")
    void excludeRemovesFromListAndRestoreBringsItBack() throws Exception {
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.excludedCount").value(0));

        exclude(alpha, reviewerToken, "관제 인입 시험 데이터라 작업 대상이 아님")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawSn").value(alpha))
                .andExpect(jsonPath("$.data.excluded").value(true))
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.reason").value("관제 인입 시험 데이터라 작업 대상이 아님"))
                .andExpect(jsonPath("$.data.excludedAt").isNotEmpty());

        // 목록 내용만이 아니라 <b>전체 건수</b>도 함께 줄어야 한다 — 한쪽에만 조건이 붙으면 화면의
        //   카드 숫자와 그 카드를 눌러 얻는 목록이 어긋난다(이 수용기준의 본체).
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.excludedCount").value(1))
                .andExpect(jsonPath("$.data.content[?(@.rawSn == " + alpha + ")]").isEmpty());

        restore(alpha, reviewerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawSn").value(alpha))
                .andExpect(jsonPath("$.data.excluded").value(false))
                .andExpect(jsonPath("$.data.restored").value(true));

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.excludedCount").value(0))
                .andExpect(jsonPath("$.data.content[?(@.rawSn == " + alpha + ")]").isNotEmpty());
    }

    @Test
    @DisplayName("★제외분만_보기는_제외된_영상만_남기고_그_건수가_제외됨N건과_정확히_같다")
    void excludedOnlyMatchesExcludedCount() throws Exception {
        exclude(alpha, reviewerToken, "시험 데이터").andExpect(status().isOk());
        exclude(beta, reviewerToken, "잘못 들어온 영상").andExpect(status().isOk());

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.excludedCount").value(2));

        // 「제외됨 N건」을 눌러 전환한 목록의 전체 건수와 <b>정확히 같아야</b> 한다.
        list("&excludedOnly=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.excludedCount").value(2))
                .andExpect(jsonPath("$.data.content[?(@.rawSn == " + assigned + ")]").isEmpty());
    }

    /**
     * ★0건이어도 키가 실린다 — 빠지면 화면이 「제외된 것이 없다」와 「제외 기능이 없다」를 구분해 보여
     * 주지 못해 되돌아갈 길을 안내할 수 없다. <b>값이 아니라 키 존재</b>를 먼저 본다.
     */
    @Test
    @DisplayName("★행이_0건인_페이지에서도_제외됨건수_키가_실린다")
    void excludedCountIsPresentOnEmptyPage() throws Exception {
        // 아무것도 제외하지 않은 상태 — 값이 0 이라고 키가 빠지면 안 된다.
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excludedCount").exists())
                .andExpect(jsonPath("$.data.excludedCount").value(0));

        // 제외분만 보기인데 제외분이 0건 — 행이 하나도 없는 페이지다.
        list("&excludedOnly=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.excludedCount").exists())
                .andExpect(jsonPath("$.data.excludedCount").value(0));

        // 전건을 제외해 <b>기본 목록이 0행</b>이 된 상태에서도 건수를 알 수 있어야 한다.
        exclude(alpha, reviewerToken, "시험").andExpect(status().isOk());
        exclude(beta, reviewerToken, "시험").andExpect(status().isOk());
        list("&dataSttsCd=PENDING&excludedOnly=false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.rawSn == " + alpha + ")]").isEmpty())
                .andExpect(jsonPath("$.data.excludedCount").value(2));
    }

    /**
     * ★필터를 무시하고 전체를 세는 구현을 잡는다 — 필터 없이만 확인하면 그 구현도 통과한다.
     */
    @Test
    @DisplayName("★제외됨건수는_지금_걸린_필터_범위_안에서_세어진다_전체를_세지_않는다")
    void excludedCountRespectsFilterScope() throws Exception {
        // 이 클래스 밖의 영상 하나를 제외해 둔다 — 검색어 필터 범위 밖이므로 세어지면 안 된다.
        Long outsideTag = videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-outside-" + System.nanoTime(), "outside" + System.nanoTime(), "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn();
        exclude(outsideTag, reviewerToken, "범위 밖").andExpect(status().isOk());
        exclude(alpha, reviewerToken, "범위 안").andExpect(status().isOk());

        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excludedCount").value(1));
        list("&excludedOnly=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("★★하위호환_제외분만보기를_보내지_않던_호출의_결과가_달라지지_않는다")
    void backwardCompatibleWhenParamAbsent() throws Exception {
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));
        // 빈 문자열도 「미지정」이다(FE 가 필터를 비운 상태로 보낼 수 있다).
        list("&excludedOnly=").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    // ---------------------------------------------------------------- 멱등 (AC-1125)

    /**
     * ★결박의 본체는 「값이 실제로 바뀐 경우에만 이력을 남긴다」이다. 되풀이 호출이 성공으로 끝나는
     * 것만 확인하고 원장을 보지 않으면 이 수용기준은 검증되지 않는다.
     */
    @Test
    @DisplayName("★★멱등_같은_값을_다시_보내면_아무것도_바뀌지_않고_이력도_늘지_않는다")
    void idempotentCallsLeaveNoTrace() throws Exception {
        exclude(alpha, reviewerToken, "최초 사유").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));
        List<LsTaskEventLog> afterFirst = events(alpha);
        assertThat(afterFirst).hasSize(1);
        LsTaskEventLog first = afterFirst.get(0);

        // 되풀이 횟수를 2회로 고정하지 않는다 — 세 번째 호출까지 보낸다.
        for (int i = 0; i < 2; i++) {
            exclude(alpha, reviewerToken, "다른 사유 " + i).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.excluded").value(true))
                    .andExpect(jsonPath("$.data.changed").value(false))
                    // 무변경이면 사유·시각을 비운다 — 남지 않은 이력의 시각을 지어내지 않는다.
                    .andExpect(jsonPath("$.data.reason").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.excludedAt").value(org.hamcrest.Matchers.nullValue()));
        }

        List<LsTaskEventLog> afterRepeat = events(alpha);
        assertThat(afterRepeat).as("되풀이 호출로 이력이 한 건도 늘면 안 된다").hasSize(1);
        // ★행 수만 세면 같은 행을 <b>덮어쓰는</b> 구현이 통과한다 — 칸 값도 함께 본다.
        assertThat(afterRepeat.get(0).getRsn()).isEqualTo(first.getRsn()).isEqualTo("최초 사유");
        assertThat(afterRepeat.get(0).getActorUserNo()).isEqualTo(first.getActorUserNo());
        assertThat(afterRepeat.get(0).getOcrnDt()).isEqualTo(first.getOcrnDt());
        assertThat(afterRepeat.get(0).getActorRoleCd()).isEqualTo("REVIEWER");

        // 이미 보이는 영상을 복원하는 것도 무변경 성공이며 이력이 남지 않는다.
        restore(beta, reviewerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excluded").value(false))
                .andExpect(jsonPath("$.data.restored").value(false));
        assertThat(events(beta)).isEmpty();
    }

    @Test
    @DisplayName("복원_이력에는_사유가_남지_않는다_감추는_쪽만_사유를_남긴다")
    void restoreLeavesNoReason() throws Exception {
        exclude(alpha, reviewerToken, "시험 데이터").andExpect(status().isOk());
        restore(alpha, reviewerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restored").value(true));

        List<LsTaskEventLog> logs = events(alpha);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_VIDEO_EXCLUDE);
        assertThat(logs.get(0).getRsn()).isEqualTo("시험 데이터");
        assertThat(logs.get(1).getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_VIDEO_RESTORE);
        assertThat(logs.get(1).getRsn()).as("복원은 사유를 받지도 남기지도 않는다").isNull();
        assertThat(logs.get(1).getActorRoleCd()).isEqualTo("REVIEWER");
        assertThat(logs.get(1).getOcrnDt()).isNotNull();
    }

    // ---------------------------------------------------------------- 배정 충돌 (AC-1127)

    /**
     * ★멱등 성공과 배정 충돌 거부는 <b>같은 지점</b>(갱신 0행)에서 갈린다 — 두 사례를 같은 묶음에 둔다.
     */
    @Test
    @DisplayName("★★배정이_있으면_409로_거부되고_표시도_이력도_바뀌지_않는다")
    void assignedVideoIsRejected() throws Exception {
        exclude(assigned, reviewerToken, "시험 데이터")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                // 안내가 막다른 길이 아니어야 한다 — 먼저 배정을 해제하라고 알린다.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("배정을 해제")));

        assertThat(exclFlag(assigned)).isEqualTo(LsDataRaw.EXCL_NO);
        assertThat(events(assigned)).as("거부된 요청은 이력을 남기지 않는다").isEmpty();
        list("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.rawSn == " + assigned + ")]").isNotEmpty());
    }

    /**
     * 거부 안내가 <b>실제로 따라갈 수 있는 길</b>인지 — 배정이 사라지면 같은 요청이 수락된다.
     *
     * <p>배정 해제 <b>창구</b>는 작업 배정 도메인 소관이라 여기서 호출하지 않는다. 이 시험이 고정하는
     * 것은 「배정이 없어지면 제외가 성립한다」는 <b>제외 쪽 계약</b>이다.
     */
    @Test
    @DisplayName("★배정이_사라지면_같은_제외_요청이_수락된다_일시조건이지_영구거부가_아니다")
    void rejectionIsTemporary() throws Exception {
        exclude(assigned, reviewerToken, "시험 데이터").andExpect(status().isConflict());

        assignmentRepository.deleteAll(
                assignmentRepository.findAll().stream()
                        .filter(a -> assigned.equals(a.getRawDataId())).toList());

        exclude(assigned, reviewerToken, "시험 데이터").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));
        assertThat(exclFlag(assigned)).isEqualTo(LsDataRaw.EXCL_YES);
    }

    // ---------------------------------------------------------------- 인가 (AC-1126)

    /**
     * ★화면에서 감추는 것과 서버가 막는 것은 서로를 대신하지 못한다 — 창구를 직접 불러도 거부돼야 한다.
     * 제외만 시험하고 복원을 빠뜨리면 되돌리는 쪽 인가가 열려 있어도 통과한다.
     */
    @Test
    @DisplayName("★★인가_작업자는_제외도_복원도_할_수_없고_거부_뒤_상태가_그대로다")
    void workerCannotExcludeOrRestore() throws Exception {
        exclude(alpha, workerToken, "시험 데이터").andExpect(status().isForbidden());
        restore(alpha, workerToken).andExpect(status().isForbidden());

        assertThat(exclFlag(alpha)).isEqualTo(LsDataRaw.EXCL_NO);
        assertThat(events(alpha)).as("거부는 부분 반영으로 끝나지 않는다").isEmpty();
    }

    @Test
    @DisplayName("인가_토큰이_없으면_권한없음이_아니라_인증필요로_거부된다")
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/v1/videos/" + alpha + "/exclusion")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"시험\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/v1/videos/" + alpha + "/exclusion"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * ★관리자에게 검수자 역할을 <b>따로 부여하지 않은</b> 상태에서 성공해야 한다 — 미리 부여해 두고
     * 시험하면 역할 계층이 끊겨도 통과한다.
     */
    @Test
    @DisplayName("★인가_관리자는_역할계층으로_그대로_수행한다_관리자전용으로_좁히지_않는다")
    void adminInheritsReviewer() throws Exception {
        exclude(alpha, adminToken, "관리자가 정리").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));
        assertThat(events(alpha).get(0).getActorRoleCd())
                .as("계층으로 승격된 값이 아니라 행위자의 실제 역할이 남아야 한다")
                .isEqualTo("ADMIN");

        restore(alpha, adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restored").value(true));
    }

    // ---------------------------------------------------------------- 사유

    @Test
    @DisplayName("★사유_제외는_사유가_없으면_거부되고_복원은_사유_없이_성공한다")
    void reasonRequiredOnlyWhenHiding() throws Exception {
        mockMvc.perform(post("/v1/videos/" + alpha + "/exclusion")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        exclude(alpha, reviewerToken, "   ").andExpect(status().isBadRequest());
        // 제어문자만 보낸 입력은 빈 입력과 같다.
        exclude(alpha, reviewerToken, "\n\t\r").andExpect(status().isBadRequest());
        assertThat(events(alpha)).isEmpty();

        // 복원은 요청 본문 자체가 없다 — 사유 없이 성공한다.
        exclude(alpha, reviewerToken, "시험 데이터").andExpect(status().isOk());
        restore(alpha, reviewerToken).andExpect(status().isOk());
    }

    @Test
    @DisplayName("★사유_개행과_제어문자가_제거되고_칸폭_안으로_길이가_제한돼_저장된다")
    void reasonIsSanitizedAndTruncated() throws Exception {
        exclude(alpha, reviewerToken, "  앞\n뒤\t줄 바꿈  ").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("앞뒤줄바꿈"));
        assertThat(events(alpha).get(0).getRsn()).isEqualTo("앞뒤줄바꿈");

        String tooLong = "가".repeat(700);
        exclude(beta, reviewerToken, tooLong).andExpect(status().isOk());
        String stored = events(beta).get(0).getRsn();
        assertThat(stored).hasSize(500).isEqualTo("가".repeat(500));
    }

    // ---------------------------------------------------------------- 없는 영상

    @Test
    @DisplayName("없는_영상은_제외도_복원도_404다")
    void unknownVideoIsNotFound() throws Exception {
        long missing = 9_000_000_000L;
        exclude(missing, reviewerToken, "시험").andExpect(status().isNotFound());
        restore(missing, reviewerToken).andExpect(status().isNotFound());
    }
}
