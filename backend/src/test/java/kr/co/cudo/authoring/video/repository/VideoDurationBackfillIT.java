package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VideoRepository#backfillDurationSecIfBlank} 의 <b>실 PostgreSQL(Testcontainer) 조건부 UPDATE 검증</b>
 * — VDO_LEN_SEC 적재 정합성 DEV_FIX.
 *
 * <p>단위 테스트(Mockito)는 "비어 있을 때만 채운다"·"다른 컬럼은 건드리지 않는다"는 <b>DB 원자 WHERE 가드</b>를
 * 재현하지 못한다. 본 IT 는 실 DB 에서 다음을 런타임 고정한다.
 *
 * <ol>
 *   <li>VDO_LEN_SEC 가 NULL 이면 초값으로 채워진다(영향 행수 1).</li>
 *   <li>VDO_LEN_SEC 가 0(≤0)이면 채워진다(영향 행수 1).</li>
 *   <li>VDO_LEN_SEC 가 유효값(≥1)이면 <b>보존</b>된다(영향 행수 0, override 금지).</li>
 *   <li>back-fill 은 VDO_LEN_SEC·MDFCN_DT 만 SET 하고 {@code DE_IDENT_YN}/{@code DATA_STTS_CD} 등
 *       다른 컬럼은 건드리지 않는다(동시 비식별 write 와의 lost-update 회피 근거).</li>
 * </ol>
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 * {@link VideoRepository} 는 {@code @ControlRepo} 이므로 {@code controlTransactionManager} 안에서 동작한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class VideoDurationBackfillIT {

    @Autowired
    private VideoRepository videoRepository;

    private LsDataRaw persistRawWithDuration(Integer durationSec) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-BF-" + System.nanoTime(), "CCTV-BF", "FIRE", "11110",
                LsDataRaw.PRVC_TYPE_ANONY, "/nas/bf.mp4", null, durationSec);
        return videoRepository.saveAndFlush(raw);
    }

    @Test
    @DisplayName("VDO_LEN_SEC가_null이면_초값으로_backfill되고_영향행수1")
    void backfillsWhenNull() {
        // given — 적재 시 VDO_LEN_SEC 가 비어있는(null) 영상
        LsDataRaw raw = persistRawWithDuration(null);

        // when
        int affected = videoRepository.backfillDurationSecIfBlank(raw.getRawSn(), 42);

        // then — 42초로 채워지고 영향 행수 1(clearAutomatically 로 재조회 시 반영)
        assertThat(affected).isEqualTo(1);
        LsDataRaw reloaded = videoRepository.findById(raw.getRawSn()).orElseThrow();
        assertThat(reloaded.getDurationSec()).isEqualTo(42);
    }

    @Test
    @DisplayName("VDO_LEN_SEC가_0이면_초값으로_backfill되고_영향행수1")
    void backfillsWhenZero() {
        // given — 적재 시 0(≤0)으로 남은 영상
        LsDataRaw raw = persistRawWithDuration(0);

        // when
        int affected = videoRepository.backfillDurationSecIfBlank(raw.getRawSn(), 7);

        // then
        assertThat(affected).isEqualTo(1);
        LsDataRaw reloaded = videoRepository.findById(raw.getRawSn()).orElseThrow();
        assertThat(reloaded.getDurationSec()).isEqualTo(7);
    }

    @Test
    @DisplayName("VDO_LEN_SEC가_유효값이면_보존되고_영향행수0")
    void preservesValidDuration() {
        // given — 관제가 준 유효한 초값(120)
        LsDataRaw raw = persistRawWithDuration(120);

        // when — back-fill 시도(다른 값)
        int affected = videoRepository.backfillDurationSecIfBlank(raw.getRawSn(), 999);

        // then — 유효값 보존, override 안 됨(영향 행수 0)
        assertThat(affected).isZero();
        LsDataRaw reloaded = videoRepository.findById(raw.getRawSn()).orElseThrow();
        assertThat(reloaded.getDurationSec()).isEqualTo(120);
    }

    @Test
    @DisplayName("backfill은_DE_IDENT_YN과_DATA_STTS_CD를_건드리지_않는다")
    void doesNotTouchOtherColumns() {
        // given — 적재 직후 상태(DE_IDENT_YN='N', DATA_STTS_CD=PENDING), VDO_LEN_SEC null
        LsDataRaw raw = persistRawWithDuration(null);
        String deidBefore = raw.getDeIdntfYn();
        String sttsBefore = raw.getDataSttsCd();

        // when — VDO_LEN_SEC 만 back-fill
        int affected = videoRepository.backfillDurationSecIfBlank(raw.getRawSn(), 30);

        // then — 다른 컬럼은 그대로(동시 비식별 write 를 되돌리지 않는다는 근거)
        assertThat(affected).isEqualTo(1);
        LsDataRaw reloaded = videoRepository.findById(raw.getRawSn()).orElseThrow();
        assertThat(reloaded.getDurationSec()).isEqualTo(30);
        assertThat(reloaded.getDeIdntfYn()).isEqualTo(deidBefore);
        assertThat(reloaded.getDataSttsCd()).isEqualTo(sttsBefore);
    }
}
