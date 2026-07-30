package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.TaskBoardQueryRepository;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1/R2 — 영상 처리 현황 조회는 파생 RAW(ORGNL_RAW_SN NOT NULL) 를 제외해 원본만 노출하고(R1),
 * 작업 목록 쿼리에는 파생 RAW 가 그대로 유지됨을(R2) 실제 DB 쿼리로 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = "/db/test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VideoRepositoryDerivedFilterTest {

    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository dataSttsRepository;
    @Autowired private TaskBoardQueryRepository taskBoardQueryRepository;

    private LsDataRaw seedOriginal(String clipId, String status) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + clipId + ".mp4",
                LocalDateTime.now(), 30);
        raw = videoRepository.save(raw);
        raw.changeStatus(status);
        return videoRepository.save(raw);
    }

    private LsDataRaw seedDerived(LsDataRaw parent, String goalResCd, String status) {
        LsDataRaw derived = LsDataRaw.createFromResolution(
                parent, "/var/raw/deriv-" + goalResCd + ".mp4", goalResCd);
        derived = videoRepository.save(derived);
        derived.changeStatus(status);
        return videoRepository.save(derived);
    }

    @Test
    @DisplayName("영상현황_조회는_파생RAW_제외_원본만")
    void listExcludesDerivedOriginalsOnly() {
        // given: 원본 2건 + 파생 2건(각각 원본을 ORGNL_RAW_SN 으로 참조)
        LsDataRaw orig1 = seedOriginal("CLIP-ORIG-1", "COMPLETED");
        LsDataRaw orig2 = seedOriginal("CLIP-ORIG-2", "COMPLETED");
        LsDataRaw deriv1 = seedDerived(orig1, "RESL_480P", "COMPLETED");
        LsDataRaw deriv2 = seedDerived(orig2, "720P", "COMPLETED");

        // when: 현황 조회(원본전용) 3종
        Page<LsDataRaw> noFilter = videoRepository.findAllByOrgnlRawSnIsNull(PageRequest.of(0, 100));
        Page<LsDataRaw> byStatus = videoRepository.findAllByDataSttsCdAndOrgnlRawSnIsNull(
                "COMPLETED", PageRequest.of(0, 100));

        // then: 파생 RAW 는 제외되고 원본만 노출된다
        List<Long> noFilterIds = noFilter.getContent().stream().map(LsDataRaw::getRawSn).toList();
        assertThat(noFilterIds).contains(orig1.getRawSn(), orig2.getRawSn());
        assertThat(noFilterIds).doesNotContain(deriv1.getRawSn(), deriv2.getRawSn());
        assertThat(noFilter.getContent()).allMatch(v -> v.getOrgnlRawSn() == null);

        List<Long> byStatusIds = byStatus.getContent().stream().map(LsDataRaw::getRawSn).toList();
        assertThat(byStatusIds).contains(orig1.getRawSn(), orig2.getRawSn());
        assertThat(byStatusIds).doesNotContain(deriv1.getRawSn(), deriv2.getRawSn());
    }

    @Test
    @DisplayName("영상현황_검수상태필터_조회도_파생RAW_제외")
    void listWithReviewStatusExcludesDerived() {
        // given: 원본 APPROVED + 파생 APPROVED (둘 다 검수상태 row 보유)
        LsDataRaw orig = seedOriginal("CLIP-RV-ORIG", "COMPLETED");
        LsDataRaw deriv = seedDerived(orig, "RESL_1080P", "COMPLETED");
        approve(orig.getRawSn());
        approve(deriv.getRawSn());

        // when
        Page<LsDataRaw> page = videoRepository.findOriginalsWithReviewStatus(
                null, LsRawDataStatus.STTS_APPROVED, PageRequest.of(0, 100));

        // then: 파생 제외, 원본만
        List<Long> ids = page.getContent().stream().map(LsDataRaw::getRawSn).toList();
        assertThat(ids).contains(orig.getRawSn());
        assertThat(ids).doesNotContain(deriv.getRawSn());
    }

    @Test
    @DisplayName("작업보드_조회는_파생RAW_포함")
    void boardIncludesDerived() {
        // given: 원본 COMPLETED + 파생 COMPLETED (둘 다 미배정 → 작업 목록 노출 대상)
        LsDataRaw orig = seedOriginal("CLIP-BOARD-ORIG", "COMPLETED");
        LsDataRaw deriv = seedDerived(orig, "RESL_480P", "COMPLETED");

        // when: 작업 목록 쿼리(R1 필터 미적용)
        Page<LsDataRaw> board = taskBoardQueryRepository.search(
                new TaskBoardSearchCondition("COMPLETED", null, null, null, null), PageRequest.of(0, 100));
        Page<LsDataRaw> unassigned = taskBoardQueryRepository.search(
                new TaskBoardSearchCondition(TaskBoardSearchCondition.STATUS_UNASSIGNED, null, null, null, null),
                PageRequest.of(0, 100));

        // then: 파생 RAW 도 작업 목록에는 그대로 유지된다(R2)
        List<Long> boardIds = board.getContent().stream().map(LsDataRaw::getRawSn).toList();
        assertThat(boardIds).contains(orig.getRawSn(), deriv.getRawSn());

        List<Long> unassignedIds = unassigned.getContent().stream().map(LsDataRaw::getRawSn).toList();
        assertThat(unassignedIds).contains(orig.getRawSn(), deriv.getRawSn());
    }

    private void approve(Long rawSn) {
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.transitionTo(LsRawDataStatus.STTS_APPROVED);
        dataSttsRepository.save(stts);
    }
}
