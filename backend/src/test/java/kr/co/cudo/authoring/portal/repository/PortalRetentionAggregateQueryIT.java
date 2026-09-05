package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보존기간 만료 예정 시각의 기준점 집계 쿼리를 <b>실 DB 에서 실행</b>해 검증한다.
 * @design AC-033, DFEAT-055
 *
 * <p>서비스 단위 테스트는 리포지토리를 mock 하므로 이 쿼리가 <b>실제로 도는지</b>도,
 * {@code max(regDt)} 투영이 무슨 타입으로 오는지도 확인하지 못한다. 서비스가 결과를
 * {@code (LocalDateTime) row[1]} 로 캐스팅하므로 드라이버가 {@code Timestamp} 를 돌려주면
 * 런타임 {@code ClassCastException}(500)이 된다 — 그 캐스팅 가정을 여기서 고정한다.
 *
 * <p>데이터마트 축은 기존 통합 테스트에 APPROVED 영상이 없어 목록 조회가 빈 페이지로 조기
 * 반환되므로, 그 경로만으로는 이 쿼리가 <b>한 번도 실행되지 않는다</b>.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalRetentionAggregateQueryIT {

    @Autowired
    private LsPortalUserLabelRepository userLabelRepository;

    @Autowired
    private PortalUploadAssetRepository assetRepository;

    @Autowired
    private PortalUploadFrameRepository frmeRepository;

    @Autowired
    private PortalUploadLabelRepository lblRepository;

    @Autowired
    private VideoRepository videoRepository;

    private final TransactionTemplate txTemplate;

    PortalRetentionAggregateQueryIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @Test
    @DisplayName("데이터마트_영상별_본인_라벨_마지막저장일_집계가_실DB에서_LocalDateTime으로_돌아온다")
    void datamartMaxRegDtAggregate() {
        String user = "user-" + System.nanoTime();
        String other = user + "-other";

        LocalDateTime early = LocalDateTime.of(2026, 8, 1, 9, 0);
        LocalDateTime late = LocalDateTime.of(2026, 8, 3, 18, 30);

        // SRC_RAW_SN 은 LS_DATA_RAW 로 FK 가 걸려 있어 실 영상 행이 선행돼야 한다.
        long rawSn = txTemplate.execute(s -> newVideoRawSn());
        txTemplate.executeWithoutResult(s -> {
            save(user, rawSn, early);
            save(user, rawSn, late);
            // 타 사용자 라벨은 더 늦지만 소유자 스코프라 섞이면 안 된다(IDOR).
            save(other, rawSn, late.plusDays(5));
        });

        List<Object[]> rows = userLabelRepository.findMaxRegDtGroupedBySrcRawSn(user, List.of(rawSn));

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(rawSn);
        // 캐스팅 가정 고정 — 서비스가 (LocalDateTime) 으로 받는다.
        assertThat(rows.get(0)[1]).isInstanceOf(LocalDateTime.class);
        assertThat((LocalDateTime) rows.get(0)[1]).isEqualTo(late);
    }

    @Test
    @DisplayName("데이터마트_본인_라벨이_없는_영상은_집계_행_자체가_없다")
    void datamartAggregateOmitsVideosWithoutLabels() {
        String user = "user-" + System.nanoTime();
        long rawSnWithLabel = txTemplate.execute(s -> newVideoRawSn());
        long rawSnWithout = txTemplate.execute(s -> newVideoRawSn());

        txTemplate.executeWithoutResult(s ->
                save(user, rawSnWithLabel, LocalDateTime.of(2026, 8, 1, 9, 0)));

        List<Object[]> rows = userLabelRepository.findMaxRegDtGroupedBySrcRawSn(
                user, List.of(rawSnWithLabel, rawSnWithout));

        // 라벨 없는 영상은 행이 없어 서비스에서 null 로 읽히고 만료 예정도 null 이 된다.
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(rawSnWithLabel);
    }

    @Test
    @DisplayName("업로드_자산별_라벨_마지막저장일_집계가_실DB에서_LocalDateTime으로_돌아온다")
    void uploadMaxRegDtAggregate() {
        String user = "user-" + System.nanoTime();

        Long uldSn = txTemplate.execute(s -> assetRepository.insertUploaded(
                user, "/portal/a.jpg", "a.jpg", "image/jpeg", 100L));
        txTemplate.executeWithoutResult(s -> {
            Long srcSn = frmeRepository.save(
                    LsDataSrc.create(uldSn, 0L, "/portal/frames/0.jpg", null)).getSrcSn();
            lblRepository.save(LsDataLbl.createManual(
                    srcSn, LsDataLbl.TYPE_BBOX, null, "car", "[[1,1],[2,2]]", user));
            lblRepository.save(LsDataLbl.createManual(
                    srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[[3,3],[4,4]]", user));
        });

        // 흡수 뒤에는 자산 조립 통로가 이 집계를 함께 소유한다(프레임을 거쳐 라벨에 닿는다).
        var map = assetRepository.findLastLabelSavedAt(user, List.of(uldSn));

        assertThat(map).containsKey(uldSn);
        assertThat(map.get(uldSn)).isInstanceOf(LocalDateTime.class);
    }

    /** 라벨이 참조할 실 영상 1건 적재 후 그 PK 반환(FK 충족용 최소 픽스처). */
    private long newVideoRawSn() {
        return videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + System.nanoTime(), "cctv-1", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30)).getRawSn();
    }

    private void save(String portalUserNo, long rawSn, LocalDateTime regDt) {
        LsPortalUserLabel label = LsPortalUserLabel.create(
                portalUserNo, rawSn, rawSn, "BBOX", "car", "[[1,1],[2,2]]");
        // 팩토리가 REG_DT 를 now 로 박으므로 집계 검증용 시각으로 고정한다.
        setField(label, "regDt", regDt);
        userLabelRepository.save(label);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
