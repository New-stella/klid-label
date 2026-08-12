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
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 — 명시 PK 복원의 <b>신뢰경계</b>를 고정한다: 되살릴 수 있는 {@code LBL_SN} 은 <b>서버가 회차
 * 스냅샷에서 만든 복원 힌트에 있는 것뿐</b>이다.
 *
 * <h3>왜 이 가드가 필수인가 (Critical)</h3>
 * 확정 저장이 요청의 {@code id} 를 그대로 PK 로 쓰게 되므로, 게이트가 새면 <b>클라이언트가 라벨 PK 를
 * 지정하는 통로</b>가 열린다(Mass Assignment — CWE-915). 게이트는 두 조건의 교집합이다:
 * <ol>
 *   <li>그 프레임에 실재하지 않는 라벨({@code LabelService.isNewLabel})</li>
 *   <li>그 {@code id} 가 <b>복원 힌트에 있다</b> — 힌트는 서버가 {@code loadedVersion} 스냅샷에서
 *       만들고 <b>그 프레임 것만</b> 담는다</li>
 * </ol>
 * 프레임 축 저장({@code PUT /v1/frames/{srcSn}/labels})은 {@code FrameSaveOptions.NONE} 의 힌트가
 * <b>빈 맵</b>이라 자동으로 제외된다 — 그 사실을 여기서 못 박는다. 깨지면 어느 사용자든 라벨 PK 를
 * 지정할 수 있게 된다.
 *
 * <p>이 클래스가 {@code label.service} 패키지에 있는 이유: 확정 저장 경로의 옵션
 * ({@code LabelService.FrameSaveOptions})이 package-private 이라 그 경로를 <b>실제로</b> 태우려면 같은
 * 패키지여야 한다(리플렉션으로 우회하지 않는다 — {@code LabelTrackChangeAuditIT} 와 같은 규약).
 *
 * @design API-196
 * @req R6
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class LabelSnapshotPkRestoreGuardIT {

    private static final List<List<Double>> POINTS =
            List.of(List.of(10.0, 10.0), List.of(50.0, 50.0));

    @Autowired private LabelService labelService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;

    private Long rawSn;
    private Long srcSn;
    private Long otherSrcSn;

    @BeforeEach
    void setUp() {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-PKGUARD-" + System.nanoTime(), "CCTV-PKGUARD", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/pkguard.mp4", java.time.LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        srcSn = srcRepository.save(LsDataSrc.create(
                rawSn, 0, "/frames/raw/pkguard/0.jpg", java.time.LocalDateTime.now())).getSrcSn();
        otherSrcSn = srcRepository.save(LsDataSrc.create(
                rawSn, 1, "/frames/raw/pkguard/1.jpg", java.time.LocalDateTime.now())).getSrcSn();
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
    }

    private TokenClaims worker() {
        return new TokenClaims("100", Role.WORKER, Channel.INTERNAL,
                java.time.Instant.now().plusSeconds(600));
    }

    /** 한 번 존재했다가 삭제되어 <b>지금은 비어 있는</b> PK — 복원 대상이 될 수 있는 유일한 형태다. */
    private Long freedLblSn(Long frameSn) {
        LsDataLbl ghost = labelRepository.save(LsDataLbl.createManual(
                frameSn, "BBOX", null, "person", "[[10.0,10.0],[50.0,50.0]]", 100L));
        labelRepository.flush();
        Long id = ghost.getLblSn();
        labelRepository.deleteAllByIdInBatch(List.of(id));
        labelRepository.flush();
        return id;
    }

    private LabelItemDto item(Long id) {
        return new LabelItemDto(id, "BBOX", null, "person", POINTS, null, null, null, null, null);
    }

    private LsDataSrc frame(Long frameSn) {
        return srcRepository.findById(frameSn).orElseThrow();
    }

    @Test
    @DisplayName("프레임축_저장은_미존재_id_를_보내도_새_PK_를_발급한다 — 힌트_없는_경로_제외")
    void 프레임축_저장은_새_PK_를_발급한다() {
        // given — 비어 있는 PK 를 요청 id 로 실어 보낸다(클라이언트가 PK 를 지정하려는 시도).
        Long freed = freedLblSn(srcSn);

        // when — 프레임 축 저장(FrameSaveOptions.NONE — restoreHints 가 빈 맵이다)
        LabelResponse saved = labelService.bulkUpsert(
                srcSn, new LabelBulkUpsertRequest(List.of(item(freed))), worker());
        labelRepository.flush();

        // then — 요청이 지정한 PK 로 저장되지 않는다. 여기가 새면 어느 사용자든 라벨 PK 를 지정할 수 있다.
        assertThat(saved.items()).hasSize(1);
        assertThat(saved.items().get(0).id())
                .as("복원 힌트에 없는 id 는 명시 PK 경로에 들어가지 못한다(CWE-915)")
                .isNotEqualTo(freed);
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(1);
    }

    @Test
    @DisplayName("확정저장_경로는_복원_힌트에_있는_id_만_옛_PK_로_되살린다")
    void 확정저장_경로는_힌트에_있는_id_만_되살린다() {
        // given — 힌트에 있는 id 하나 + 힌트에 없는 id 하나를 함께 보낸다.
        Long hinted = freedLblSn(srcSn);
        Long notHinted = freedLblSn(srcSn);
        Map<Long, LabelService.RestoreHint> hints = Map.of(
                hinted, new LabelService.RestoreHint("N", null, null, null));

        // when — 영상 축 확정 저장 경로의 옵션(회차 스냅샷 복원 힌트 보유)
        LabelService.FrameSaveOutcome outcome = labelService.applyFrameSave(
                srcSn, frame(srcSn),
                new LabelBulkUpsertRequest(List.of(item(hinted), item(notHinted))), 100L,
                LabelService.FrameSaveOptions.of(null, hints, true));
        labelRepository.flush();

        // then ① 힌트에 있는 id 는 옛 PK 그대로 되살아난다
        assertThat(outcome.labels().stream().map(LsDataLbl::getLblSn))
                .as("스냅샷 힌트에 있는 id 는 옛 LBL_SN 으로 복원돼야 한다")
                .contains(hinted);
        // then ② 힌트에 없는 id 는 새 PK 가 발급된다(같은 요청 안에서도 게이트가 항목별로 성립한다)
        assertThat(outcome.labels().stream().map(LsDataLbl::getLblSn))
                .as("힌트에 없는 id 는 같은 요청 안에서도 명시 PK 경로에 들어가지 못한다")
                .doesNotContain(notHinted);
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(2);
    }

    /**
     * ★ 삭제·삽입 <b>순서</b>와 flush 를 실제 DB 로 못 박는다.
     *
     * <p>명시 PK 삽입은 full-replace 삭제 델타보다 <b>앞</b>에서 일어난다. 대상이 겹치면 PK 충돌로
     * 저장 전체가 500 이 되지만, 복원 대상은 정의상 <b>그 프레임에 실재하지 않는 PK</b> 이고 삭제 대상은
     * <b>{@code findBySrcSn(srcSn)} 에 실재하는 PK</b> 라 두 집합은 절대 겹치지 않는다.
     *
     * <p>명시 PK 복원 배치({@code restoreSnapshotPks})는 항목별 수정/삭제 루프보다 <b>먼저</b> 실행되므로
     * 그 내부 {@code flush()} 시점에는 아직 {@code kept} 의 수정이 반영되기 전이다 — 잃을 변경이 없다.
     * 대신 이 테스트는 <b>한 저장에 수정·삭제·명시 PK 복원 세 축이 섞여도</b> PK 충돌 없이 세 결과가
     * 모두 최종 반영되는지를 확인한다.
     */
    @Test
    @DisplayName("같은_저장에_기존_라벨_수정과_삭제가_섞여도_명시_PK_복원이_충돌하지_않는다")
    void 삭제_델타와_명시PK_삽입이_충돌하지_않는다() {
        // given — 수정될 라벨 / 요청에서 빠져 삭제될 라벨 / 되살아날 옛 PK
        LsDataLbl kept = labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, "person", "[[1.0,1.0],[2.0,2.0]]", 100L));
        LsDataLbl dropped = labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, "car", "[[3.0,3.0],[4.0,4.0]]", 100L));
        labelRepository.flush();
        Long freed = freedLblSn(srcSn);
        Map<Long, LabelService.RestoreHint> hints = Map.of(
                freed, new LabelService.RestoreHint("N", null, null, null));

        // when — 수정(kept) + 복원(freed). dropped 는 요청에 없어 전체 교체로 삭제된다.
        LabelItemDto edited = new LabelItemDto(kept.getLblSn(), "BBOX", null, "truck",
                POINTS, null, null, null, null, null);
        labelService.applyFrameSave(srcSn, frame(srcSn),
                new LabelBulkUpsertRequest(List.of(edited, item(freed))), 100L,
                LabelService.FrameSaveOptions.of(null, hints, true));
        labelRepository.flush();

        // then ① 세 축이 모두 반영된다 — PK 충돌로 500 이 되지 않는다
        assertThat(labelRepository.findBySrcSn(srcSn)).extracting(LsDataLbl::getLblSn)
                .as("복원 대상과 삭제 대상은 집합이 겹칠 수 없다(순서가 문제되지 않는 근거)")
                .containsExactlyInAnyOrder(kept.getLblSn(), freed);
        // ⚠ 삭제 확인은 <b>쿼리로</b> 한다 — full-replace 삭제는 bulk JPQL 이라 1차 캐시를 evict 하지
        //   않으므로 {@code findById} 는 detach 되지 않은 옛 인스턴스를 돌려준다(이 저장소가
        //   {@code VersionService.detachDeleted} 에 같은 함정을 주석으로 남긴 지점 — M4).
        assertThat(labelRepository.existsById(dropped.getLblSn())).isFalse();
        // then ② 수정 축도 함께 최종 반영된다 — 복원 배치가 먼저 실행돼도 뒤이은 수정 루프의 결과가 살아남는다
        assertThat(labelRepository.findById(kept.getLblSn()).orElseThrow().getLabelNm())
                .isEqualTo("truck");
    }

    @Test
    @DisplayName("옛_PK_가_타_프레임_라벨에_점유돼_있으면_새_PK_로_폴백한다")
    void 점유된_PK_는_새_PK_로_폴백한다() {
        // given — 다른 프레임이 그 PK 를 쓰고 있다(회차 스냅샷 id 가 그사이 재사용된 상황).
        LsDataLbl occupant = labelRepository.save(LsDataLbl.createManual(
                otherSrcSn, "BBOX", null, "car", "[[1.0,1.0],[2.0,2.0]]", 100L));
        labelRepository.flush();
        Long occupied = occupant.getLblSn();
        Map<Long, LabelService.RestoreHint> hints = Map.of(
                occupied, new LabelService.RestoreHint("N", null, null, null));

        // when
        LabelService.FrameSaveOutcome outcome = labelService.applyFrameSave(
                srcSn, frame(srcSn), new LabelBulkUpsertRequest(List.of(item(occupied))), 100L,
                LabelService.FrameSaveOptions.of(null, hints, true));
        labelRepository.flush();

        // then ① 그 1건만 새 PK 로 폴백한다(전체 실패로 만들지 않는다 — 롤백 경로와 같은 규약)
        assertThat(outcome.labels()).hasSize(1);
        assertThat(outcome.labels().get(0).getLblSn()).isNotEqualTo(occupied);
        // then ② 남의 프레임 라벨은 건드리지 않는다
        assertThat(labelRepository.findBySrcSn(otherSrcSn))
                .extracting(LsDataLbl::getLblSn).containsExactly(occupied);
        assertThat(labelRepository.findBySrcSn(srcSn)).hasSize(1);
    }
}
