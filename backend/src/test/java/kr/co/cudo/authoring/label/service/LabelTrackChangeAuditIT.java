package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelHistoryResponse;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-01 — <b>트랙 재지정도 변경이다</b>: 이력·판번호·통지 축에 올라오는지 고정한다.
 *
 * <h3>막는 결함</h3>
 * 관리 엔티티의 {@code reassignTrack()} 은 dirty checking 으로 <b>그냥 커밋된다</b>. 변경 감지 축
 * ({@code LabelSnapshot})에 {@code trackId} 가 없으면 좌표·라벨명이 그대로일 때 "변경 없음"으로
 * 판정되어 한 번에 셋이 빠진다:
 * <ol>
 *   <li>{@code LS_DATA_LBL_HSTRY} 미기록 — 누가 트랙을 옮겼는지 기록이 없다(CWE-778)</li>
 *   <li>{@code LBL_VER} 미증가 — 다른 세션의 낡은 판번호가 무효화되지 않아 그 세션의 전량 교체
 *       저장이 409 없이 통과한다(CWE-362)</li>
 *   <li>승인 영상에서 산출물 재생성·관제 통지 생략</li>
 * </ol>
 *
 * <p>같은 행위를 하는 {@code TrackMergeService} 는 영상 배타 락·겹침 검사·보간 정리·통지를 모두
 * 갖추고 있다 — <b>가드 있는 문 옆에 가드 없는 문</b>을 내지 않기 위한 회귀 가드다.
 *
 * <p>이 클래스가 {@code label.service} 패키지에 있는 이유: 확정 저장 경로의 옵션
 * ({@code LabelService.FrameSaveOptions})이 package-private 이라, 그 경로를 <b>실제로</b> 태우려면
 * 같은 패키지여야 한다(리플렉션으로 우회하지 않는다).
 *
 * @req R6
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class LabelTrackChangeAuditIT {

    @Autowired private LabelService labelService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;

    private Long rawSn;
    private Long srcSn;

    @BeforeEach
    void setUp() {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-TRACK-" + System.nanoTime(), "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        srcSn = srcRepository.save(
                LsDataSrc.create(rawSn, 0, "/var/raw/f0.jpg", LocalDateTime.now())).getSrcSn();
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private TokenClaims worker() {
        return new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    @Test
    @DisplayName("트랙만_바꿔도_저장_이력과_판번호가_남는다")
    void 트랙만_바꿔도_이력과_판번호가_남는다() {
        // given — 트랙에 묶인 라벨 1건. 좌표·라벨명은 그대로 두고 <b>트랙만</b> 옮긴다.
        LsDataLbl a = labelRepository.save(LsDataLbl.createRestored(srcSn, "BBOX", null, "person",
                "[[1.0,1.0],[2.0,2.0]]", "N", null, "T-1", null));
        labelRepository.flush();
        long baseVersion = srcRepository.findById(srcSn).orElseThrow().getLabelVersion();

        // when — 확정 저장 경로의 옵션(trackId 수용)으로 저장 코어를 태운다.
        LabelItemDto moved = new LabelItemDto(a.getLblSn(), "BBOX", null, "person",
                List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null, null, null, null, "T-2");
        LabelService.FrameSaveOutcome outcome = labelService.applyFrameSave(
                srcSn, srcRepository.findById(srcSn).orElseThrow(),
                new LabelBulkUpsertRequest(List.of(moved)), 100L,
                LabelService.FrameSaveOptions.of(null, Map.of(), true));
        labelRepository.flush();
        srcRepository.flush();

        // then ① 트랙이 실제로 바뀐다
        assertThat(labelRepository.findBySrcSn(srcSn).get(0).getTrackId()).isEqualTo("T-2");

        // then ② 누가 옮겼는지 이력이 남는다(CWE-778) — 좌표가 그대로라 "변경 없음"으로 새면 안 된다
        Page<LabelHistoryResponse> history =
                labelService.getHistory(srcSn, worker(), PageRequest.of(0, 10));
        assertThat(history.getContent())
                .as("트랙 재지정이 이력에 남지 않으면 누가 옮겼는지 기록이 사라진다")
                .isNotEmpty();

        // then ③ 판번호가 오른다 — 오르지 않으면 다른 세션의 낡은 판번호가 무효화되지 않아
        //   그 세션의 전량 교체 저장이 409 없이 통과한다(CWE-362)
        //   ⚠ 엔티티로 읽으면 안 된다: LBL_VER 는 insertable/updatable=false 라 원자 UPDATE 이후
        //     영속 컨텍스트 값이 stale 이다(항상 옛 값이 보여 이 단언이 공허해진다). 스칼라 네이티브
        //     조회가 1차 캐시를 우회해 DB 현재 값을 준다.
        assertThat(outcome.labelVersion())
                .as("응답 판번호가 오르지 않으면 화면이 낡은 토큰을 되돌려 보낸다")
                .isGreaterThan(baseVersion);
        assertThat(srcRepository.lockAndReadLabelVersion(srcSn).orElseThrow())
                .as("트랙 재지정으로 LBL_VER 가 오르지 않으면 동시 저장 보호가 뚫린다")
                .isGreaterThan(baseVersion);
    }
}
