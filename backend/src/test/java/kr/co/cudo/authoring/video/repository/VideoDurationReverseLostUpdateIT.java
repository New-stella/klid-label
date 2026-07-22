package kr.co.cudo.authoring.video.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LsDataRaw} 의 {@code @DynamicUpdate} 로 <b>역방향 lost-update</b>가 차단되는지 실 PostgreSQL 로 검증
 * — VDO_LEN_SEC back-fill 정합성 DEV_FIX (CWE-362).
 *
 * <p><b>재현 시나리오</b>: 다른 full-entity writer 가 {@code durationSec} 를 모르던 시점(로드 시 null)에서
 * 다른 필드만 변경해 flush 할 때, 로드 스냅샷의 stale {@code durationSec} 를 SET 절에 포함해 back-fill 한
 * 값을 되돌리는 회귀. {@code @Version} 이 없어 Hibernate 기본 정적 UPDATE 는 전체 컬럼을 SET 하므로 이 회귀가
 * 성립한다. {@code @DynamicUpdate} 는 실제 dirty 필드만 SET 하므로 {@code durationSec} 를 건드리지 않는다.
 *
 * <p><b>결정론 확보</b>: 실제 동시 스레드 대신, 벌크 UPDATE(영속성 컨텍스트 우회)로 "다른 writer 가 30 을
 * 커밋한" 상태를 만든 뒤, stale 스냅샷을 그대로 든 관리 엔티티의 다른 필드만 변경·flush 하는 순서를 단일
 * 스레드로 재현한다. {@code @DynamicUpdate} 유무로 SET 절 범위가 갈린다.
 * ({@code backfillDurationSecIfBlank} 는 {@code clearAutomatically=true} 로 관리 엔티티를 detach 시키므로,
 * stale 스냅샷 유지를 위해 여기서는 컨텍스트를 비우지 않는 EntityManager 벌크 UPDATE 를 사용한다.)
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class VideoDurationReverseLostUpdateIT {

    @Autowired
    private VideoRepository videoRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("다른_writer가_다른필드만_flush해도_backfill된_VDO_LEN_SEC는_보존된다")
    void reverseLostUpdateBlockedByDynamicUpdate() {
        // given — 적재 시 VDO_LEN_SEC 가 비어있는(null) 영상을 로드한 writer (stale 스냅샷 durationSec=null)
        LsDataRaw writerView = videoRepository.saveAndFlush(LsDataRaw.createFromIngest(
                "CLIP-REV-LU-" + System.nanoTime(), "CCTV-RLU", "FIRE", "11110",
                LsDataRaw.PRVC_TYPE_ANONY, "/nas/rlu.mp4", null, null));
        Long rawSn = writerView.getRawSn();
        assertThat(writerView.getDurationSec()).isNull();

        // and — 그 사이 다른 writer(back-fill)가 VDO_LEN_SEC=30 을 커밋 (영속성 컨텍스트 우회 벌크 UPDATE)
        em.createQuery("UPDATE LsDataRaw r SET r.durationSec = 30 WHERE r.rawSn = :id")
                .setParameter("id", rawSn)
                .executeUpdate();

        // when — writer 가 durationSec 이 아닌 다른 필드(DATA_STTS_CD)만 변경해 flush
        writerView.markMarkingReady();
        em.flush();

        // then — @DynamicUpdate 로 durationSec 은 SET 절에서 제외 → DB 의 30 이 보존된다.
        //        (@DynamicUpdate 가 없으면 stale null 로 되돌아가 RED)
        em.clear();
        LsDataRaw reloaded = videoRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.getDurationSec()).isEqualTo(30);
        assertThat(reloaded.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }
}
