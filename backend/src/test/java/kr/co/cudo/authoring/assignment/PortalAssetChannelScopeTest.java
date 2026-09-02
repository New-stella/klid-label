package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.dto.AssignmentResponse;
import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.dto.TaskBoardItemResponse;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.AssignmentService;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.service.TaskBoardService;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>채널 축</b> — 포털 사용자 본인 업로드 자산은 관제 작업목록·배정목록에 나타나지 않는다(ADR-058).
 *
 * <h3>흡수가 만든 <b>반대 방향</b>의 문제</h3>
 * <p>막히는 축(포털이 관제 게이트에 걸린다)과 달리 이쪽은 <b>섞여 드는</b> 축이라 오류로 드러나지
 * 않고 조용히 결과가 늘어난다. 관제 배포본에는 포털 행이 없으므로 <b>포털 배포본에서만</b> 드러나
 * 발견이 늦다 — 그래서 여기서 세운다.
 *
 * <h3>★ 파생 등재 게이트가 이것을 대신하지 못한다</h3>
 * <p>그 술어의 배제 대상은 <b>파생</b>뿐이라, 포털 업로드 <b>원본</b>(부모 참조가 비어 있다)은 조건을
 * 무조건 통과한다. 두 게이트는 묻는 것이 다르므로 합치지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PortalAssetChannelScopeTest {

    @Autowired private TaskBoardService taskBoardService;
    @Autowired private AssignmentService assignmentService;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private PortalUploadAssetRepository assetRepository;

    /** test-data.sql 시드 작업자. */
    private static final long WORKER_NO = 100L;

    private static final AtomicLong CLIP_SEQ = new AtomicLong(System.nanoTime());

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("★포털_업로드_자산은_관제_작업목록에_나오지_않는다")
    void portalAssetIsHiddenFromTaskBoard() {
        LsDataRaw control = seedControlVideo();
        Long portalUldSn = assetRepository.insertUploaded(
                "portal-user-" + CLIP_SEQ.incrementAndGet(), "/p/v.mp4", "v.mp4", "video/mp4", 1L);

        List<Long> visible = boardRawSns();

        assertThat(visible)
                .as("관제 영상은 그대로 보여야 한다 — 채널 술어 오타로 전멸하는 사고 방지")
                .contains(control.getRawSn());
        assertThat(visible)
                .as("★포털 자산이 섞이면 관제 작업목록이 남의 채널 데이터를 보여 준다")
                .doesNotContain(portalUldSn);
    }

    @Test
    @DisplayName("★배정_행이_생겨도_포털_자산은_관제_배정목록에_나오지_않는다")
    void portalAssetIsHiddenFromAssignmentList() {
        LsDataRaw control = seedControlVideo();
        Long portalUldSn = assetRepository.insertUploaded(
                "portal-user-" + CLIP_SEQ.incrementAndGet(), "/p/v.mp4", "v.mp4", "video/mp4", 1L);
        // 배정 목록은 배정 행을 기준으로 도는 <다른 경로>다 — 술어를 한 곳에만 붙이면 여기로 샌다.
        assignmentRepository.saveAndFlush(
                LsTaskAssignment.createLabeler(control.getRawSn(), WORKER_NO, 1L));
        assignmentRepository.saveAndFlush(
                LsTaskAssignment.createLabeler(portalUldSn, WORKER_NO, 1L));

        Page<AssignmentResponse.Item> page = assignmentService.listAssignments(
                new AssignmentSearchCondition(null, WORKER_NO, null, null, null),
                reviewer(), PageRequest.of(0, 500));

        assertThat(page.getContent())
                .as("관제 영상 배정은 그대로 보여야 한다 — 술어 오타로 전멸하는 사고 방지")
                .anyMatch(item -> control.getRawSn().equals(item.videoId()));
        assertThat(page.getContent())
                .as("★배정 목록에도 채널 축이 걸려야 한다(파생 게이트만으로는 막히지 않는다)")
                .noneMatch(item -> portalUldSn.equals(item.videoId()));
    }

    // ---------------------------------------------------------------- 픽스처

    /** 관제 인입 원본 1건(배치 완료 — 작업목록 기본 필터 대상). */
    private LsDataRaw seedControlVideo() {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "CHAN-" + CLIP_SEQ.incrementAndGet(), "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/nas-storage/raw/chan.mp4",
                LocalDateTime.of(2026, 5, 1, 9, 0), 30));
        raw.changeStatus(LsDataRaw.DATA_STTS_COMPLETED);
        return videoRepository.saveAndFlush(raw);
    }

    private List<Long> boardRawSns() {
        Page<TaskBoardItemResponse> page = taskBoardService.listBoard(
                TaskBoardSearchCondition.defaults(), reviewer(), PageRequest.of(0, 500));
        return page.getContent().stream().map(TaskBoardItemResponse::videoId).toList();
    }

}
