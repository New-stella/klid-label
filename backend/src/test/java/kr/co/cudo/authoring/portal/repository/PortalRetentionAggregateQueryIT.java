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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    private PortalUserWorkRepository workRepository;

    @Autowired
    private LsPortalUserMetaRepository userMetaRepository;

    @Autowired
    private LsPortalUserEvntAnnoRepository userAnnoRepository;

    @Autowired
    private PortalUploadAssetRepository assetRepository;

    @Autowired
    private PortalUploadFrameRepository frmeRepository;

    @Autowired
    private PortalUploadLabelRepository lblRepository;

    @Autowired
    private VideoRepository videoRepository;

    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbcTemplate;

    PortalRetentionAggregateQueryIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager,
            @Qualifier("controlDataSource") DataSource controlDataSource) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
        this.jdbcTemplate = new JdbcTemplate(controlDataSource);
    }

    private JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    @Test
    @DisplayName("데이터마트_영상별_본인_저작_최초저장일_집계가_실DB에서_LocalDateTime으로_돌아온다")
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

        Map<Long, LocalDateTime> earliest =
                workRepository.findEarliestAuthoredAtByVideo(user, List.of(rawSn));

        assertThat(earliest).containsOnlyKeys(rawSn);
        // ★ 기산점은 최초 저장이다(DFEAT-055) — 뒤에 다시 저장(late)해도 기준점이 밀리지 않는다.
        assertThat(earliest.get(rawSn))
                .as("마지막 저장(MAX)으로 되돌아가면 저장할 때마다 만료가 밀려 만료가 영영 오지 않는다")
                .isEqualTo(early);
    }

    @Test
    @DisplayName("데이터마트_세_저작물이_하나도_없는_영상은_집계_키_자체가_없다")
    void datamartAggregateOmitsVideosWithoutLabels() {
        String user = "user-" + System.nanoTime();
        long rawSnWithLabel = txTemplate.execute(s -> newVideoRawSn());
        long rawSnWithout = txTemplate.execute(s -> newVideoRawSn());

        txTemplate.executeWithoutResult(s ->
                save(user, rawSnWithLabel, LocalDateTime.of(2026, 8, 1, 9, 0)));

        Map<Long, LocalDateTime> earliest = workRepository.findEarliestAuthoredAtByVideo(
                user, List.of(rawSnWithLabel, rawSnWithout));

        // 세 저작물을 하나도 갖지 않은 영상은 키가 없어 서비스에서 null 로 읽히고 만료 예정도 null 이 된다.
        assertThat(earliest).containsOnlyKeys(rawSnWithLabel);
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

    // ============ ★기산점 3축 동치성 — 코드로 합치지 않으므로 시험이 유일한 접착제다 ============

    /**
     * 기산점 판정이 <b>세 자리</b>에 서로 다른 형태로 있고, 그것을 코드로 합치지 않는다.
     * <ul>
     *   <li>조회      — 표별 {@code min(w.regDt) ... group by w.srcRawSn} 셋의 최솟값</li>
     *   <li>후보      — 표별 {@code select distinct ... where w.regDt < :cutoff} 셋의 합집합</li>
     *   <li>삭제 실행 — 네이티브 CTE 의 {@code EXISTS(... reg_dt < :cutoff)} 세 갈래 OR</li>
     * </ul>
     * 셋이 다른 것은 실수가 아니다. 표를 가로지르는 최솟값은 JPQL 로 표현할 수 없고(UNION 부재),
     * 삭제는 <b>한 문장</b>이어야 세 표가 같은 스냅샷을 본다. 그래서 세 형태가 남고
     * <b>「그 그룹의 최초 저장이 커트라인보다 이르다」 ⟺ 「어느 표엔가 커트라인 이전 행이 있다」</b>
     * 라는 동치를 <b>이 시험이 유일하게 잇는다</b> — 한 축만 바꾸면 여기서 죽어야 한다.
     *
     * <p>★ 픽스처가 <b>세 저작물</b>을 각각 단독으로도 놓는다. 라벨만 놓고 검사하면 메타·어노테이션
     * 축이 조회·후보·삭제 어디에서 빠져도 이 시험이 알아채지 못한다.
     */
    @Test
    @DisplayName("★기산점_3축이_같은_그룹과_커트라인에_같은_판정을_낸다_조회_후보_삭제실행")
    void threeAggregationAxesAgreeOnTheSameGroup() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 20, 10, 0);

        // 경계 — 최초 저장이 커트라인과 정확히 같은 시각이면 아직 만료가 아니다(조건이 strict <).
        assertAxesAgree("경계 — 최초 저장 == 커트라인", cutoff, false, Artifact.LABEL, cutoff);
        assertAxesAgree("경계 — 최초 저장이 커트라인 1초 전", cutoff, true,
                Artifact.LABEL, cutoff.minusSeconds(1));

        // 그룹에 행이 하나뿐 — 집계와 exists 가 같은 판정을 내는지.
        assertAxesAgree("행 1건 — 커트라인 이후", cutoff, false, Artifact.LABEL, cutoff.plusDays(1));
        assertAxesAgree("행 1건 — 커트라인 이전", cutoff, true, Artifact.LABEL, cutoff.minusDays(1));

        // ★ 재저장으로 MAX 만 뒤로 밀린 그룹 — MIN 은 그대로라 여전히 만료다.
        assertAxesAgree("재저장으로 MAX 만 밀린 그룹", cutoff, true, Artifact.LABEL,
                cutoff.minusDays(3), cutoff.plusDays(2));
        assertAxesAgree("전 행이 커트라인 이후", cutoff, false, Artifact.LABEL,
                cutoff.plusDays(1), cutoff.plusDays(2));

        // ★ 라벨이 아닌 저작물만 있는 그룹 — 세 축 어디서든 라벨만 보면 여기서 죽는다.
        assertAxesAgree("메타 오버레이만 — 만료", cutoff, true, Artifact.META, cutoff.minusDays(1));
        assertAxesAgree("메타 오버레이만 — 미만료", cutoff, false, Artifact.META, cutoff.plusDays(1));
        assertAxesAgree("어노테이션 오버레이만 — 만료", cutoff, true, Artifact.ANNO, cutoff.minusDays(1));
        assertAxesAgree("어노테이션 오버레이만 — 미만료", cutoff, false, Artifact.ANNO, cutoff.plusDays(1));
    }

    /**
     * ★ <b>가장 이른 저작물이 라벨이 아닐 때</b>도 기산점이 그것으로 잡히는지 — 세 축 전부에서.
     *
     * <p>라벨만 보면 기산점이 실제보다 늦어져 ①고지가 늦고 ②후보에도 늦게 잡힌다. 픽스처는
     * <b>라벨을 커트라인 이후에</b> 두어, 라벨만 보는 구현이 「미만료」로 판정하게 만든다.
     */
    @Test
    @DisplayName("★가장_이른_저작물이_메타면_기산점도_후보판정도_그것을_따른다_라벨만_보면_죽는다")
    void earliestArtifactMayNotBeTheLabel() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 20, 10, 0);
        String user = "user-" + System.nanoTime();
        long rawSn = txTemplate.execute(s -> newVideoRawSn());
        txTemplate.executeWithoutResult(s -> {
            saveMeta(user, rawSn, cutoff.minusDays(5));   // 가장 이른 저작 — 메타
            save(user, rawSn, cutoff.plusDays(1));        // 라벨은 커트라인 이후
        });

        assertThat(workRepository.findEarliestAuthoredAtByVideo(user, List.of(rawSn)).get(rawSn))
                .as("★라벨만 보면 기산점이 5일 늦어져 고지도 삭제도 밀린다")
                .isEqualTo(cutoff.minusDays(5));
        assertThat(candidateContains(user, rawSn, cutoff))
                .as("★라벨만 보는 후보 스캔은 이 그룹을 놓친다")
                .isTrue();

        // 삭제 축(비가역) — 맨 뒤. 라벨까지 <함께> 지워져야 한 벌 삭제가 성립한다.
        int removedPair = txTemplate.execute(s ->
                workRepository.deleteExpiredWorkGroup(user, rawSn, cutoff));
        assertThat(removedPair).isEqualTo(2);
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .as("★그룹이 만료면 라벨도 함께 지워진다 — 자기 표의 시각만 보면 라벨이 살아남는다")
                .isEmpty();
        assertThat(userMetaRepository
                .findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(user, rawSn)).isEmpty();
    }

    /**
     * ★★ <b>순차 삭제였다면 여기서 깨진다</b> — 표마다 DELETE 를 따로 날리면 앞 문장이 만료 판정의
     * 유일한 근거(오래된 라벨)를 지워, 뒤 문장의 판정이 거짓이 되어 <b>메타·어노테이션이 살아남는다</b>.
     * 데이터 변경 CTE 가 같은 스냅샷을 보는 성질이 이 시험을 통과시키는 유일한 이유다.
     */
    @Test
    @DisplayName("★★만료_근거가_라벨_하나뿐이어도_메타와_어노테이션이_함께_지워진다_스냅샷_삭제")
    void allThreeArtifactsAreDeletedEvenWhenOnlyTheLabelIsOld() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 20, 10, 0);
        String user = "user-" + System.nanoTime();
        long rawSn = txTemplate.execute(s -> newVideoRawSn());
        txTemplate.executeWithoutResult(s -> {
            save(user, rawSn, cutoff.minusDays(30));      // 만료의 유일한 근거
            saveMeta(user, rawSn, cutoff.plusDays(1));    // 최근 — 자기 시각만 보면 만료가 아니다
            saveAnno(user, rawSn, cutoff.plusDays(1));
        });

        int removed = txTemplate.execute(s ->
                workRepository.deleteExpiredWorkGroup(user, rawSn, cutoff));

        assertThat(removed).as("세 저작물 3행이 한 벌로 지워진다").isEqualTo(3);
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .isEmpty();
        assertThat(userMetaRepository
                .findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(user, rawSn))
                .as("★라벨을 먼저 지운 뒤 판정하면 여기가 남는다 — 순차 삭제 금지의 실효 지점")
                .isEmpty();
        assertThat(userAnnoRepository.findByPortalUserNoAndSrcRawSn(user, rawSn)).isEmpty();
    }

    /**
     * ★★ 판별자 <b>하나씩</b> — 나머지 조건이 대신 막아 주지 못하는 픽스처로 짠다.
     * <ul>
     *   <li>소유자를 빼면 → <b>같은 영상에 저작한 남</b>의 행이 지워진다(그 행도 만료다)</li>
     *   <li>영상을 빼면 → <b>같은 사용자의 다른 영상</b> 저작물이 지워진다(그 행도 만료다)</li>
     *   <li>커트라인을 빼면 → <b>같은 사용자·같은 영상의 최근</b> 저작물까지 지워진다</li>
     * </ul>
     * 셋을 함께 빼야 죽는 시험은 가드가 아니다 — 쓸모 있는 것은 「하나만 빼도 죽는다」다.
     */
    @Test
    @DisplayName("★★남의_것도_다른_영상도_기간_안의_것도_지우지_않는다_판별자_하나씩")
    void deleteTouchesNeitherOtherOwnersNorOtherVideosNorFreshGroups() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 20, 10, 0);
        String mine = "user-" + System.nanoTime();
        String other = mine + "-other";
        long target = txTemplate.execute(s -> newVideoRawSn());
        long otherVideo = txTemplate.execute(s -> newVideoRawSn());

        txTemplate.executeWithoutResult(s -> {
            // 지워져야 하는 것 — 내 것 · 그 영상 · 만료
            save(mine, target, cutoff.minusDays(30));
            saveMeta(mine, target, cutoff.minusDays(30));
            saveAnno(mine, target, cutoff.minusDays(30));
            // ① 소유자만 다르다(같은 영상 · 같은 만료) — 소유자 조건만이 이것을 막는다
            save(other, target, cutoff.minusDays(30));
            saveMeta(other, target, cutoff.minusDays(30));
            saveAnno(other, target, cutoff.minusDays(30));
            // ② 영상만 다르다(같은 사용자 · 같은 만료) — 영상 조건만이 이것을 막는다
            save(mine, otherVideo, cutoff.minusDays(30));
            saveMeta(mine, otherVideo, cutoff.minusDays(30));
        });
        // ③ 같은 사용자·같은 영상인데 커트라인 이후 저장 — 커트라인 조건만이 이것을 막는다.
        //    ⚠ 그룹이 만료라 <함께 지워지는 것이 정상>이므로 별도 그룹으로 둔다.
        long freshVideo = txTemplate.execute(s -> newVideoRawSn());
        txTemplate.executeWithoutResult(s -> {
            save(mine, freshVideo, cutoff.plusDays(1));
            saveMeta(mine, freshVideo, cutoff.plusDays(1));
        });

        int removed = txTemplate.execute(s ->
                workRepository.deleteExpiredWorkGroup(mine, target, cutoff));

        assertThat(removed).as("대상 그룹의 세 저작물만 지워진다").isEqualTo(3);
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(other, target))
                .as("★소유자 조건을 빼면 여기가 지워진다").hasSize(1);
        assertThat(userMetaRepository
                .findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(other, target))
                .as("★소유자 조건을 빼면 여기가 지워진다").hasSize(1);
        assertThat(userAnnoRepository.findByPortalUserNoAndSrcRawSn(other, target))
                .as("★소유자 조건을 빼면 여기가 지워진다").isPresent();
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(mine, otherVideo))
                .as("★영상 조건을 빼면 여기가 지워진다").hasSize(1);
        assertThat(userMetaRepository
                .findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(mine, otherVideo))
                .as("★영상 조건을 빼면 여기가 지워진다").hasSize(1);

        // ③ 커트라인 — 만료 전 그룹에 같은 문장을 직접 불러도 0행이어야 한다.
        int removedFresh = txTemplate.execute(s ->
                workRepository.deleteExpiredWorkGroup(mine, freshVideo, cutoff));
        assertThat(removedFresh)
                .as("★커트라인 조건을 빼면 기간 안의 저작물까지 지워진다").isZero();
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(mine, freshVideo))
                .hasSize(1);
    }

    /** 픽스처가 놓을 저작물 종류 — 세 축 어디서 빠져도 드러나게 <b>단독으로도</b> 놓는다. */
    private enum Artifact { LABEL, META, ANNO }

    /**
     * 한 그룹을 세 축으로 각각 판정해 결과가 모두 같은지 본다. 삭제 축은 비가역이라 <b>맨 뒤</b>에 둔다.
     *
     * @param label    실패 메시지에 실을 픽스처 설명
     * @param cutoff   세 축이 공유하는 커트라인
     * @param expired  기대 판정(만료면 {@code true})
     * @param kind     저작물 종류 — 라벨이 아닌 축도 단독으로 검사한다
     * @param savedAt  그 그룹의 저장 시각들(첫 인자가 최초 저장일 필요는 없다 — 집계가 고른다)
     */
    private void assertAxesAgree(String label, LocalDateTime cutoff, boolean expired,
                                 Artifact kind, LocalDateTime... savedAt) {
        String user = "user-" + System.nanoTime();
        long rawSn = txTemplate.execute(s -> newVideoRawSn());
        txTemplate.executeWithoutResult(s -> {
            for (LocalDateTime at : savedAt) {
                switch (kind) {
                    case LABEL -> save(user, rawSn, at);
                    // 메타는 적재 키에 메타키가 있어 여러 건을 놓을 수 있다(시각별로 키를 가른다).
                    case META -> saveMeta(user, rawSn, at, "k" + at.toLocalTime());
                    // 어노테이션은 (사용자, 영상)당 한 벌이라 마지막 시각만 남는다.
                    case ANNO -> saveAnno(user, rawSn, at);
                }
            }
        });

        // 축 1 — 조회(화면이 고지하는 만료 예정 시각의 기준점)
        boolean byRead = workRepository.findEarliestAuthoredAtByVideo(user, List.of(rawSn))
                .get(rawSn).isBefore(cutoff);

        // 축 2 — 삭제 배치 후보 스캔
        boolean byCandidate = candidateContains(user, rawSn, cutoff);

        // 축 3 — 삭제 실행문(동치 재작성). 비가역이라 마지막에 실행한다.
        boolean byDelete = txTemplate.execute(s ->
                workRepository.deleteExpiredWorkGroup(user, rawSn, cutoff)) > 0;

        assertThat(byRead).as("[%s] 조회 축", label).isEqualTo(expired);
        assertThat(byCandidate)
                .as("[%s] 후보 축이 조회 축과 갈렸다 — 화면이 고지한 만료일과 삭제일이 어긋난다", label)
                .isEqualTo(expired);
        assertThat(byDelete)
                .as("[%s] 삭제 실행 축이 조회 축과 갈렸다 — 후보로만 잡히고 영영 지워지지 않는다", label)
                .isEqualTo(expired);
    }

    private boolean candidateContains(String user, long rawSn, LocalDateTime cutoff) {
        return txTemplate.execute(s -> workRepository.findExpiredWorkGroups(cutoff))
                .stream()
                .anyMatch(g -> user.equals(g.portalUserNo()) && rawSn == g.srcRawSn());
    }

    private void saveMeta(String portalUserNo, long rawSn, LocalDateTime regDt) {
        saveMeta(portalUserNo, rawSn, regDt, "weather");
    }

    /** 메타 오버레이 1칸 — 영상 축(프레임 참조 없음)으로 놓고 REG_DT 를 검증용 시각으로 고정한다. */
    private void saveMeta(String portalUserNo, long rawSn, LocalDateTime regDt, String metaKey) {
        userMetaRepository.upsertVideoScoped(portalUserNo, rawSn, metaKey, "v");
        jdbc().update("UPDATE ls_portal_user_meta SET reg_dt = ?"
                        + " WHERE portal_user_no = ? AND src_raw_sn = ? AND meta_key = ?",
                java.sql.Timestamp.valueOf(regDt), portalUserNo, rawSn, metaKey);
    }

    /** 이벤트 어노테이션 오버레이 한 벌 — (사용자, 영상)당 1건이라 다시 놓으면 덮어쓴다. */
    private void saveAnno(String portalUserNo, long rawSn, LocalDateTime regDt) {
        userAnnoRepository.upsertAnnotation(portalUserNo, rawSn, "{\"a\":1}");
        jdbc().update("UPDATE ls_portal_user_evnt_anno SET reg_dt = ?"
                        + " WHERE portal_user_no = ? AND src_raw_sn = ?",
                java.sql.Timestamp.valueOf(regDt), portalUserNo, rawSn);
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
