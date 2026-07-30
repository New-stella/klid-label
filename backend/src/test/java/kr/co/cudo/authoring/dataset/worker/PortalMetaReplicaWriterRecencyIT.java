package kr.co.cudo.authoring.dataset.worker;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.PortalDatasetVideoMetaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 DEV_FIX 이슈 2(clock-독립화) — 포털 복제 writer 의 <b>last-writer-wins</b> 계약 실 DB 통합 테스트.
 *
 * <p>과거 이 writer 는 wall-clock({@code RVW_CMPL_DT}) 기반 단조성 가드로 "옛 스냅샷"을 skip 했으나,
 * NTP 되감김 등 <b>시계 역행</b> 시 논리적으로 최신인 스냅샷을 stale 로 오판·skip 하여 포털이 옛 해시에
 * 영구 고정되는 false-skip 회귀(MEDIUM)를 유발했다. 이 가드를 제거하고 순서 역전 방어는 주 방어
 * (coalescing: {@code pg_advisory_xact_lock(rawSn)} + {@code supersedePending} + 워커 PENDING-only
 * regDt-ASC 폴링)에 단독으로 위임했다.
 *
 * <p>따라서 옛 스냅샷 outbox 가 최신 뒤에 이 writer 로 재전달되는 일 자체가 없으며(=순서 역전은 outbox
 * 계층에서 원천 차단, {@code DatasetVideoMetaSnapshotServiceIT.materialize_supersedesPriorPendingOutbox}
 * 로 검증), writer 는 넘겨받은 스냅샷을 항상 멱등 적용하는 last-writer-wins 로 단순화된다. 본 IT 는
 * (a) 후속 스냅샷의 정상 교체, (b) 시계 역행 상황에서도 최신 스냅샷 정상 반영(false-skip 소멸),
 * (c) 동일 신선도 경계에서의 정상 적용을 실 DB 로 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalMetaReplicaWriterRecencyIT {
    // ── DB-ISSUE-01 / V146: 자식 행이 참조할 부모 영상(LS_DATA_RAW) 시드 ──
    //   FK 신설 전에는 임의 정수를 rawSn 으로 써도 통과했지만 그렇게 만든 데이터는 실제로는 고아였다.
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate parentVideoJdbc;

    private final java.util.List<Long> seededParentRawSns = new java.util.ArrayList<>();

    /** 실재하는 부모 영상 1건을 만들고 rawSn 을 돌려준다(V146 FK). */
    private long newVideo() {
        long rawSn = kr.co.cudo.authoring.support.RawVideoFixture.newRaw(parentVideoJdbc);
        seededParentRawSns.add(rawSn);
        return rawSn;
    }

    @org.junit.jupiter.api.AfterEach
    void cleanSeededParentVideos() {
        // 부모 삭제 = 자식(동결 메타·아웃박스·export 등) CASCADE 삭제.
        seededParentRawSns.forEach(
                sn -> kr.co.cudo.authoring.support.RawVideoFixture.deleteRaws(parentVideoJdbc, sn));
        seededParentRawSns.clear();
    }


    @Autowired
    private PortalMetaReplicaWriter writer;

    @Autowired
    private PortalDatasetVideoMetaRepository portalRepository;

    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    PortalMetaReplicaWriterRecencyIT(@Qualifier("controlDataSource") DataSource controlDataSource) {
        this.jdbc = new JdbcTemplate(controlDataSource);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
        }
    }

    private LsDatasetVideoMeta snapshot(long rawSn, String hash, LocalDateTime rvwCmplDt) {
        return LsDatasetVideoMeta.builder()
                .rawSn(rawSn)
                .snpshtHash(hash)
                .activeYn(LsDatasetVideoMeta.ACTIVE_YES)
                .rvwCmplDt(rvwCmplDt)
                .regDt(LocalDateTime.now())
                .build();
    }

    private String activeHash(long rawSn) {
        List<LsDatasetVideoMeta> active = portalRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        assertThat(active).hasSize(1);
        return active.get(0).getSnpshtHash();
    }

    @Test
    @DisplayName("후속_스냅샷은_옛_활성을_정상_교체")
    void newerSnapshot_replacesActive() {
        // given — 옛 스냅샷 H1(T1)이 먼저 active
        long rawSn = newVideo();
        seededRawSns.add(rawSn);
        LocalDateTime t1 = LocalDateTime.of(2026, 7, 13, 12, 0, 0);
        LocalDateTime t2 = t1.plusHours(1);

        writer.replicate(snapshot(rawSn, "H1", t1));
        assertThat(activeHash(rawSn)).isEqualTo("H1");

        // when — 후속 스냅샷 H2 복제 → 정상 교체(활성 1건 불변식 유지)
        writer.replicate(snapshot(rawSn, "H2", t2));

        // then — active = H2
        assertThat(activeHash(rawSn)).isEqualTo("H2");
    }

    @Test
    @DisplayName("시계_역행시_논리적_최신_스냅샷_정상_반영")
    void clockRollback_appliesLogicallyLatestSnapshot() {
        // given — v1 승인이 wall-clock T=12:00 에 복제돼 포털 active = H1
        long rawSn = newVideo();
        seededRawSns.add(rawSn);
        LocalDateTime laterWall = LocalDateTime.of(2026, 7, 13, 12, 0, 0);
        LocalDateTime rolledBackWall = laterWall.minusHours(1); // NTP 되감김 후의 더 이른 wall-clock

        writer.replicate(snapshot(rawSn, "H1", laterWall));
        assertThat(activeHash(rawSn)).isEqualTo("H1");

        // when — NTP 되감김 후 승인된 논리적 최신 스냅샷 H2 의 rvwCmplDt 가 활성보다 이르다(더 오래된 것처럼 보임)
        writer.replicate(snapshot(rawSn, "H2", rolledBackWall));

        // then — wall-clock 이 이르더라도 최신 스냅샷이 정상 반영된다(옛 단조성 가드의 false-skip 소멸)
        //        → 포털이 옛 해시(H1)에 영구 고정되지 않음
        assertThat(activeHash(rawSn)).isEqualTo("H2");
    }

    @Test
    @DisplayName("동일_기준값_경계_정상_적용")
    void equalRvwCmplDt_stillApplies() {
        // given — 동일 신선도(T) 로 H1 이 먼저 active
        long rawSn = newVideo();
        seededRawSns.add(rawSn);
        LocalDateTime t = LocalDateTime.of(2026, 7, 13, 12, 0, 0);

        writer.replicate(snapshot(rawSn, "H1", t));
        assertThat(activeHash(rawSn)).isEqualTo("H1");

        // when — 동일 rvwCmplDt(경계값) 를 가진 후속 스냅샷 H2 복제
        writer.replicate(snapshot(rawSn, "H2", t));

        // then — 경계값(equal)에서도 skip 없이 정상 적용 → active = H2
        assertThat(activeHash(rawSn)).isEqualTo("H2");
    }
}
