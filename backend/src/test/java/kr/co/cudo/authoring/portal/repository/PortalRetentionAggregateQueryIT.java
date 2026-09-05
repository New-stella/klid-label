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
 * 집계 투영이 무슨 타입으로 오는지도 확인하지 못한다. 서비스가 결과를
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
    @DisplayName("데이터마트_영상별_본인_라벨_최초저장일_집계가_실DB에서_LocalDateTime으로_돌아온다")
    void datamartMinRegDtAggregate() {
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

        List<Object[]> rows = userLabelRepository.findMinRegDtGroupedBySrcRawSn(user, List.of(rawSn));

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(rawSn);
        // 캐스팅 가정 고정 — 서비스가 (LocalDateTime) 으로 받는다.
        assertThat(rows.get(0)[1]).isInstanceOf(LocalDateTime.class);
        // ★ 기산점은 최초 저장이다(DFEAT-055) — 뒤에 다시 저장(late)해도 기준점이 밀리지 않는다.
        assertThat((LocalDateTime) rows.get(0)[1])
                .as("마지막 저장(MAX)으로 되돌아가면 저장할 때마다 만료가 밀려 만료가 영영 오지 않는다")
                .isEqualTo(early);
    }

    @Test
    @DisplayName("데이터마트_본인_라벨이_없는_영상은_집계_행_자체가_없다")
    void datamartAggregateOmitsVideosWithoutLabels() {
        String user = "user-" + System.nanoTime();
        long rawSnWithLabel = txTemplate.execute(s -> newVideoRawSn());
        long rawSnWithout = txTemplate.execute(s -> newVideoRawSn());

        txTemplate.executeWithoutResult(s ->
                save(user, rawSnWithLabel, LocalDateTime.of(2026, 8, 1, 9, 0)));

        List<Object[]> rows = userLabelRepository.findMinRegDtGroupedBySrcRawSn(
                user, List.of(rawSnWithLabel, rawSnWithout));

        // 라벨 없는 영상은 행이 없어 서비스에서 null 로 읽히고 만료 예정도 null 이 된다.
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(rawSnWithLabel);
    }

    /**
     * ★ 업로드 축은 <b>여전히 마지막 저장</b>이다 — 데이터마트 축의 {@code MIN} 확정이 여기로
     * 옮겨오지 않았음을 고정한다(DFEAT-055 — 두 채널의 기산점이 같은지는 확정 회신에 언급이 없다).
     */
    @Test
    @DisplayName("★업로드_자산별_라벨_집계는_마지막저장일이다_데이터마트_MIN_확정이_옮겨오지_않았다")
    void uploadMaxRegDtAggregate() {
        String user = "user-" + System.nanoTime();

        LocalDateTime early = LocalDateTime.of(2026, 8, 1, 9, 0);
        LocalDateTime late = LocalDateTime.of(2026, 8, 3, 18, 30);

        Long uldSn = txTemplate.execute(s -> assetRepository.insertUploaded(
                user, "/portal/a.mp4", "a.mp4", "video/mp4", 100L));
        List<Long> lblSns = txTemplate.execute(s -> {
            Long srcSn = frmeRepository.save(
                    LsDataSrc.create(uldSn, 0L, "/portal/frames/0.jpg", null)).getSrcSn();
            Long a = lblRepository.save(LsDataLbl.createManual(
                    srcSn, LsDataLbl.TYPE_BBOX, null, "car", "[[1,1],[2,2]]", user)).getLblSn();
            Long b = lblRepository.save(LsDataLbl.createManual(
                    srcSn, LsDataLbl.TYPE_BBOX, null, "person", "[[3,3],[4,4]]", user)).getLblSn();
            return List.of(a, b);
        });
        // 팩토리가 REG_DT 를 now 로 박으므로 집계 검증용 시각으로 고정한다.
        txTemplate.executeWithoutResult(s -> {
            setLabelRegDt(lblSns.get(0), early);
            setLabelRegDt(lblSns.get(1), late);
        });

        // 흡수 뒤에는 자산 조립 통로가 이 집계를 함께 소유한다(프레임을 거쳐 라벨에 닿는다).
        var map = assetRepository.findLastLabelSavedAt(user, List.of(uldSn));

        assertThat(map).containsKey(uldSn);
        assertThat(map.get(uldSn)).isInstanceOf(LocalDateTime.class);
        assertThat(map.get(uldSn))
                .as("★업로드 축의 기준점 한쪽은 마지막 저장이다 — 데이터마트 축을 따라 MIN 으로 바꾸면 안 된다")
                .isEqualTo(late);
    }

    // ============ ★집계 3축 동치성 — 코드로 합치지 않으므로 시험이 유일한 접착제다 ============

    /**
     * 기준점 집계가 <b>세 자리</b>에 서로 다른 형태로 있고, 그것을 코드로 합치지 않기로 확정했다.
     * <ul>
     *   <li>조회      — {@code select min(l.regDt) ... group by l.srcRawSn}</li>
     *   <li>후보      — {@code ... group by ... having min(l.regDt) < :cutoff}</li>
     *   <li>삭제 실행 — {@code ... and exists (... where k.regDt < :cutoff)}</li>
     * </ul>
     * 셋째가 다른 것은 실수가 아니다 — JPQL {@code DELETE} 의 where 절에는 {@code group by}/
     * {@code having} 을 쓸 수 없어 {@code MIN(REG_DT) < cutoff} 를 <b>동치 재작성</b>한 것이다
     * (커트라인 이전에 저장된 라벨이 하나라도 있다 ⟺ 그 그룹의 최초 저장이 커트라인보다 이르다).
     * 셋 다 JPQL 문자열이라 Java 로 끌어올릴 수 없고 상수 문자열 공유로는 셋째가 덮이지 않는다.
     *
     * <p>★ 그래서 <b>이 시험이 세 축을 잇는 유일한 접착제</b>다 — 한 축만 바꾸면 여기서 죽어야 한다.
     */
    @Test
    @DisplayName("★기준점_집계_3축이_같은_그룹과_커트라인에_같은_판정을_낸다_조회_후보_삭제실행")
    void threeAggregationAxesAgreeOnTheSameGroup() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 20, 10, 0);

        // 경계 — 최초 저장이 커트라인과 정확히 같은 시각이면 아직 만료가 아니다(조건이 strict <).
        assertAxesAgree("경계 — 최초 저장 == 커트라인", cutoff, false, cutoff);
        assertAxesAgree("경계 — 최초 저장이 커트라인 1초 전", cutoff, true, cutoff.minusSeconds(1));

        // 그룹에 행이 하나뿐 — 집계와 exists 가 같은 판정을 내는지.
        assertAxesAgree("행 1건 — 커트라인 이후", cutoff, false, cutoff.plusDays(1));
        assertAxesAgree("행 1건 — 커트라인 이전", cutoff, true, cutoff.minusDays(1));

        // ★ 재저장으로 MAX 만 뒤로 밀린 그룹 — MIN 은 그대로라 여전히 만료다.
        assertAxesAgree("재저장으로 MAX 만 밀린 그룹", cutoff, true,
                cutoff.minusDays(3), cutoff.plusDays(2));
        assertAxesAgree("전 행이 커트라인 이후", cutoff, false,
                cutoff.plusDays(1), cutoff.plusDays(2));
    }

    /**
     * 한 그룹을 세 축으로 각각 판정해 결과가 모두 같은지 본다. 삭제 축은 비가역이라 <b>맨 뒤</b>에 둔다.
     *
     * @param label    실패 메시지에 실을 픽스처 설명
     * @param cutoff   세 축이 공유하는 커트라인
     * @param expired  기대 판정(만료면 {@code true})
     * @param savedAt  그 그룹의 저장 시각들(첫 인자가 최초 저장일 필요는 없다 — 집계가 고른다)
     */
    private void assertAxesAgree(String label, LocalDateTime cutoff, boolean expired,
                                 LocalDateTime... savedAt) {
        String user = "user-" + System.nanoTime();
        long rawSn = txTemplate.execute(s -> newVideoRawSn());
        txTemplate.executeWithoutResult(s -> {
            for (LocalDateTime at : savedAt) {
                save(user, rawSn, at);
            }
        });

        // 축 1 — 조회(화면이 고지하는 만료 예정 시각의 기준점)
        List<Object[]> rows = userLabelRepository.findMinRegDtGroupedBySrcRawSn(user, List.of(rawSn));
        boolean byRead = ((LocalDateTime) rows.get(0)[1]).isBefore(cutoff);

        // 축 2 — 삭제 배치 후보 스캔
        boolean byCandidate = txTemplate.execute(s -> userLabelRepository.findExpiredLabelGroups(cutoff))
                .stream()
                .anyMatch(r -> user.equals(r[0]) && rawSn == ((Number) r[1]).longValue());

        // 축 3 — 삭제 실행문(동치 재작성). 비가역이라 마지막에 실행한다.
        boolean byDelete = txTemplate.execute(s ->
                userLabelRepository.deleteExpiredLabelGroup(user, rawSn, cutoff)) > 0;

        assertThat(byRead).as("[%s] 조회 축", label).isEqualTo(expired);
        assertThat(byCandidate)
                .as("[%s] 후보 축이 조회 축과 갈렸다 — 화면이 고지한 만료일과 삭제일이 어긋난다", label)
                .isEqualTo(expired);
        assertThat(byDelete)
                .as("[%s] 삭제 실행 축이 조회 축과 갈렸다 — 후보로만 잡히고 영영 지워지지 않는다", label)
                .isEqualTo(expired);
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .as("[%s] 삭제는 그룹 전체를 지우거나 한 행도 지우지 않는다", label)
                .hasSize(expired ? 0 : savedAt.length);
    }

    /** 업로드 라벨의 REG_DT 를 검증용 시각으로 고정(팩토리가 now 로 박는다). */
    private void setLabelRegDt(Long lblSn, LocalDateTime regDt) {
        lblRepository.findById(lblSn).ifPresent(l -> {
            setField(l, "regDt", regDt);
            lblRepository.save(l);
        });
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
