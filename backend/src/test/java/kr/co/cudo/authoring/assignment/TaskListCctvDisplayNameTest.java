package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.dto.TaskBoardItemResponse;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
import kr.co.cudo.authoring.assignment.service.TaskBoardService;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.support.IngestFlatValueSeeder;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작업목록(REVIEWER·WORKER 양쪽)의 <b>영상 표시명</b> — 빈칸을 그리지 않는다 (V185).
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * <p>관제 회신(2026-08-12)으로 {@code VMS_CCTV_ID} 가 NULL 인 영상이 실재하게 됐고, CCTV명마저 없으면
 * 두 작업목록은 <b>행 전체가 빈칸</b>이었다(FE 는 {@code cctvName} 을 그대로 그린다). 영상 상세·영상
 * 목록만 3단 폴백을 적용해 두면 <b>같은 영상이 화면마다 다른 이름</b>으로 보인다.
 *
 * <p>판정은 {@code CctvDisplayNamePolicy} 한 곳이 소유한다 — 두 서비스는 호출만 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TaskListCctvDisplayNameTest {

    @Autowired private TaskBoardService taskBoardService;
    @Autowired private AssignmentService assignmentService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** test-data.sql 시드 작업자. */
    private static final long WORKER_NO = 100L;

    private static final AtomicLong CLIP_SEQ = new AtomicLong(System.nanoTime());

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private TokenClaims worker() {
        return new TokenClaims(String.valueOf(WORKER_NO), Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
    }

    /**
     * 영상 1건 시드 + WORKER 배정.
     *
     * @param vmsCctvId {@code null} 이면 CCTV 식별자가 없는 영상(관제 수동 업로드분 등)
     * @param cctvNm    {@code null} 이면 관제 인입 행 자체를 만들지 않는다(= 이름 조달 불가)
     */
    private LsDataRaw seedAssignedVideo(String vmsCctvId, String cctvNm) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "NAME-" + CLIP_SEQ.incrementAndGet(), vmsCctvId, "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/nas-storage/raw/name.mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        raw = videoRepository.saveAndFlush(raw);
        IngestFlatValueSeeder.seedName(jdbcTemplate, raw.getRawSn(), vmsCctvId, cctvNm);
        assignmentRepository.saveAndFlush(
                LsTaskAssignment.createLabeler(raw.getRawSn(), WORKER_NO, 1L));
        return raw;
    }

    /** REVIEWER 작업목록에서 그 영상 행의 표시명. */
    private String boardNameOf(Long rawSn) {
        Page<TaskBoardItemResponse> page = taskBoardService.listBoard(
                TaskBoardSearchCondition.defaults(), reviewer(), PageRequest.of(0, 500));
        return page.getContent().stream()
                .filter(i -> rawSn.equals(i.videoId()))
                .map(TaskBoardItemResponse::cctvName)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("작업목록에 그 영상이 없다 — 픽스처 오류"));
    }

    /** WORKER 작업목록(배정목록)에서 그 영상 행. */
    private AssignmentResponse.Item assignmentItemOf(Long rawSn) {
        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                AssignmentSearchCondition.none(), worker(), PageRequest.of(0, 500));
        return page.getContent().stream()
                .filter(i -> rawSn.equals(i.rawDataId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("배정목록에 그 영상이 없다 — 픽스처 오류"));
    }

    // ----------------------------------------------------- 둘 다 없는 영상

    @Test
    @DisplayName("REVIEWER_작업목록_CCTV명과_CCTV_ID가_모두_없으면_영상번호로_식별한다")
    void REVIEWER_작업목록_CCTV명과_CCTV_ID가_모두_없으면_영상번호로_식별한다() {
        // given — 구 동작은 null 을 그대로 내려 FE 가 빈칸을 그렸다.
        LsDataRaw raw = seedAssignedVideo(null, null);

        // when / then
        assertThat(boardNameOf(raw.getRawSn())).isEqualTo("영상 #" + raw.getRawSn());
    }

    @Test
    @DisplayName("WORKER_작업목록_CCTV명과_CCTV_ID가_모두_없으면_영상번호로_식별한다")
    void WORKER_작업목록_CCTV명과_CCTV_ID가_모두_없으면_영상번호로_식별한다() {
        // given
        LsDataRaw raw = seedAssignedVideo(null, null);

        // when
        AssignmentResponse.Item item = assignmentItemOf(raw.getRawSn());

        // then — FE 가 읽는 cctvName 과 외부 계약 필드 videoTitle 이 같은 표기를 쓴다.
        assertThat(item.cctvName()).isEqualTo("영상 #" + raw.getRawSn());
        assertThat(item.videoTitle()).isEqualTo("영상 #" + raw.getRawSn());
    }

    // ----------------------------------------------------- 회귀 가드

    @Test
    @DisplayName("두_작업목록_모두_CCTV명이_있으면_그_이름을_그대로_쓴다")
    void 두_작업목록_모두_CCTV명이_있으면_그_이름을_그대로_쓴다() {
        // given — 이름이 있는 영상은 폴백이 개입하면 안 된다(회귀 0).
        LsDataRaw raw = seedAssignedVideo("CCTV-GANGNAM-001", "CCTV-강남구-001");

        // when / then
        assertThat(boardNameOf(raw.getRawSn())).isEqualTo("CCTV-강남구-001");
        AssignmentResponse.Item item = assignmentItemOf(raw.getRawSn());
        assertThat(item.cctvName()).isEqualTo("CCTV-강남구-001");
        assertThat(item.videoTitle()).isEqualTo("CCTV-강남구-001");
    }

    @Test
    @DisplayName("두_작업목록_모두_CCTV명이_없으면_CCTV_ID로_폴백한다")
    void 두_작업목록_모두_CCTV명이_없으면_CCTV_ID로_폴백한다() {
        // given — 인입 행이 없어 이름을 조달할 수 없는 영상. 기존 2단 폴백은 그대로다.
        LsDataRaw raw = seedAssignedVideo("CCTV-ORPHAN-001", null);

        // when / then
        assertThat(boardNameOf(raw.getRawSn())).isEqualTo("CCTV-ORPHAN-001");
        assertThat(assignmentItemOf(raw.getRawSn()).cctvName()).isEqualTo("CCTV-ORPHAN-001");
    }
}
