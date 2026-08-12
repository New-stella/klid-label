package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LabelSnapshot;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4 — 폐기된 프레임이 <b>밖으로 나가는 8개 지점</b>에서 전부 빠지는지 실동작 검증
 * (Testcontainers PostgreSQL).
 *
 * <h3>왜 실 DB 로 검증하나</h3>
 * 여덟 지점 중 둘은 <b>뷰 SQL</b>(V182)이고 나머지는 JPQL 술어라, 모킹으로는 "필터를 실제로 걸었는가"를
 * 증명할 수 없다. 게다가 목록만 거르고 <b>개수</b>를 두면 둘이 어긋나는데 그 어긋남도 실 쿼리로만 드러난다.
 *
 * <h3>함께 고정하는 반대 방향 (D2)</h3>
 * <b>내부 화면의 프레임 수는 폐기분을 빼지 않는다.</b> 총량이 줄면 작업 진도가 왜 바뀌었는지 알 수 없기
 * 때문이다. 그래서 {@code countByRawSn}·{@code countByRawSnsGrouped}·{@code findByRawSnOrderByFrameNoAsc}
 * 는 폐기분을 <b>그대로 센다</b> — 이 방향이 깨지는 것도 결함이므로 함께 단언한다.
 *
 * @design D1
 * @design D2
 * @req R4
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class FrameDiscardFilterIT {

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private LsDataLblHstryRepository historyRepository;

    private Long rawSn;
    private Long keptSrcSn;
    private Long discardedSrcSn;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(controlDataSource);
    }

    @BeforeEach
    void setUp() {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-DSCDFLT-" + System.nanoTime(), "CCTV-DSCDFLT", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/dscdflt.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();

        LsDataSrc kept = srcRepository.save(newFrame(0));
        LsDataSrc discarded = srcRepository.save(newFrame(1));
        keptSrcSn = kept.getSrcSn();
        discardedSrcSn = discarded.getSrcSn();

        // 검수 완료(APPROVED) — 데이터마트 뷰 4종의 공통 게이트.
        statusRepository.save(LsRawDataStatus.initial(rawSn));
        srcRepository.flush();
        statusRepository.flush();
        jdbc().update("UPDATE ls_raw_data_status SET data_stts_cd = ? WHERE raw_data_id = ?",
                LsRawDataStatus.STTS_APPROVED, rawSn);

        // 폐기 상태를 실제 DB 값으로 만든다(적용 경로와 동일한 원자 UPDATE).
        srcRepository.applyDiscardFlag(discardedSrcSn, LsDataSrc.DSCD_YES);
    }

    private LsDataSrc newFrame(int frameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo,
                "/frames/raw/dscdflt/" + frameNo + ".jpg", LocalDateTime.now());
        src.attachDeidPath("/frames/deid/dscdflt/" + frameNo + ".jpg");
        return src;
    }

    // ------------------------------------------------------------ ①⑥ 산출·관제 목록 (프레임 엔티티)

    @Test
    @DisplayName("산출용_프레임_목록에서_폐기_프레임이_빠진다")
    void 산출용_프레임_목록에서_폐기_프레임이_빠진다() {
        List<LsDataSrc> frames = srcRepository.findNotDiscardedByRawSnOrderByFrameNoAsc(rawSn);

        assertThat(frames).extracting(LsDataSrc::getSrcSn).containsExactly(keptSrcSn);
    }

    @Test
    @DisplayName("관제_라벨목록_페이징에서_폐기_프레임이_빠지고_총건수도_함께_줄어든다")
    void 관제_라벨목록_페이징에서_폐기_프레임이_빠지고_총건수도_함께_줄어든다() {
        var page = srcRepository.findNotDiscardedByRawSnOrderByFrameNoAsc(rawSn, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(LsDataSrc::getSrcSn).containsExactly(keptSrcSn);
        // 목록만 거르고 totalElements 를 두면 페이지 메타와 내용이 모순된다.
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("관제_라벨목록의_프레임_필터로_폐기_프레임을_콕_집어도_나오지_않는다")
    void 관제_라벨목록의_프레임_필터로_폐기_프레임을_콕_집어도_나오지_않는다() {
        var page = srcRepository.findNotDiscardedByRawSnAndSrcSnInOrderByFrameNoAsc(
                rawSn, Set.of(keptSrcSn, discardedSrcSn), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(LsDataSrc::getSrcSn).containsExactly(keptSrcSn);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    // ------------------------------------------------------------ ②⑤ 개수 지점

    @Test
    @DisplayName("완료통지_이미지수와_관제_요약_프레임수에서_폐기_프레임이_빠진다")
    void 완료통지_이미지수와_관제_요약_프레임수에서_폐기_프레임이_빠진다() {
        assertThat(srcRepository.countNotDiscardedByRawSn(rawSn)).isEqualTo(1);
    }

    // ------------------------------------------------------------ ③④ 통지 변경파일 목록

    @Test
    @DisplayName("전량재생성_통지의_변경파일_목록에서_폐기_프레임이_빠진다")
    void 전량재생성_통지의_변경파일_목록에서_폐기_프레임이_빠진다() {
        List<Long> frameNos = srcRepository.findExportableFrameNosByRawSn(rawSn);

        assertThat(frameNos).containsExactly(0L);
    }

    @Test
    @DisplayName("프레임단위_통지의_변경파일_해석에서_폐기_프레임이_빠진다")
    void 프레임단위_통지의_변경파일_해석에서_폐기_프레임이_빠진다() {
        List<Object[]> rows = srcRepository.findBothVelExportableFrameNoByRawSnAndSrcSnIn(
                rawSn, Set.of(keptSrcSn, discardedSrcSn));

        assertThat(rows).extracting(r -> r[0]).containsExactly(keptSrcSn);
    }

    // ------------------------------------------------------------ ⑦⑧ 데이터마트 뷰

    @Test
    @DisplayName("데이터마트_프레임뷰에서_폐기_프레임이_빠진다")
    void 데이터마트_프레임뷰에서_폐기_프레임이_빠진다() {
        List<Long> srcSns = jdbc().queryForList(
                "SELECT src_sn FROM v_completed_frame WHERE raw_sn = ? ORDER BY frame_no",
                Long.class, rawSn);

        assertThat(srcSns).containsExactly(keptSrcSn);
    }

    @Test
    @DisplayName("데이터마트_라벨변경뷰에서_폐기_프레임의_변경점이_빠진다")
    void 데이터마트_라벨변경뷰에서_폐기_프레임의_변경점이_빠진다() {
        historyRepository.saveAndFlush(saveEvent(keptSrcSn));
        historyRepository.saveAndFlush(saveEvent(discardedSrcSn));

        List<Long> srcSns = jdbc().queryForList(
                "SELECT src_sn FROM v_completed_label_change WHERE raw_sn = ?", Long.class, rawSn);

        assertThat(srcSns).containsExactly(keptSrcSn);
    }

    private LsDataLblHstry saveEvent(Long srcSn) {
        LabelSnapshot after = new LabelSnapshot("BBOX", null, "person", "[[1.0,1.0],[2.0,2.0]]");
        return LsDataLblHstry.recordSaveEvent(srcSn, "1",
                List.of(LabelChange.added(9_000_000L + srcSn, "person", after)));
    }

    // ------------------------------------------------------------ D2 — 내부 화면 총량은 그대로

    @Test
    @DisplayName("내부화면_프레임수는_폐기분을_빼지_않는다_작업진도가_흔들리지_않게")
    void 내부화면_프레임수는_폐기분을_빼지_않는다() {
        assertThat(srcRepository.countByRawSn(rawSn)).isEqualTo(2);
        assertThat(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn)).hasSize(2);
        assertThat(srcRepository.countByRawSnsGrouped(List.of(rawSn)))
                .singleElement()
                .satisfies(row -> assertThat(((Number) row[1]).longValue()).isEqualTo(2L));
    }

    // ------------------------------------------------------------ 원자 적용 (CWE-362)

    @Test
    @DisplayName("이미_같은_상태면_폐기_UPDATE가_한_행도_바꾸지_않는다_멱등")
    void 이미_같은_상태면_폐기_UPDATE가_한_행도_바꾸지_않는다() {
        assertThat(srcRepository.applyDiscardFlag(discardedSrcSn, LsDataSrc.DSCD_YES)).isZero();
        assertThat(srcRepository.applyDiscardFlag(keptSrcSn, LsDataSrc.DSCD_NO)).isZero();
    }

    @Test
    @DisplayName("복원하면_산출_대상으로_되돌아온다")
    void 복원하면_산출_대상으로_되돌아온다() {
        assertThat(srcRepository.applyDiscardFlag(discardedSrcSn, LsDataSrc.DSCD_NO)).isEqualTo(1);

        assertThat(srcRepository.countNotDiscardedByRawSn(rawSn)).isEqualTo(2);
        assertThat(srcRepository.readDiscardFlag(discardedSrcSn)).contains(LsDataSrc.DSCD_NO);
    }
}
