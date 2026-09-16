package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.assignment.dto.AssignmentSearchCondition;
import kr.co.cudo.authoring.assignment.dto.TaskBoardSearchCondition;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.AssignmentQueryRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.TaskBoardQueryRepository;
import kr.co.cudo.authoring.stats.repository.StatsQueryRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>내부 채널 작업 범위의 포털 채널 출처 배제</b> — 실 DB 회귀 가드 (ADR-068 · AC-1119).
 *
 * <h3>무엇을 고정하나</h3>
 * <p>포털 데이터셋 영상({@code PORTAL_DATASET})이 작업보드·배정·통계·고착 회수 조회에 <b>나타나지 않고</b>,
 * 포털 업로드({@code PORTAL_ULD}) 배제도 그대로라는 것. 술어 세 형태를 모두 실제 쿼리로 태운다 —
 * QueryDSL {@code internal}(작업보드) · 상관 {@code EXISTS}(배정) · JPQL 조각(통계·고착 회수).
 *
 * <h3>공허한 통과를 막는 대조군</h3>
 * <p>배제만 단언하면 조건이 틀려 <b>아무것도</b> 안 나와도 통과한다. 그래서 출처가 빈 과거 영상과
 * 관제 원본({@code ORIGINAL}) 영상을 함께 심고 <b>그 둘은 반드시 나온다</b>를 같이 단언한다 —
 * 「포털이 아니다」를 「관제다」로 잘못 적으면 출처가 빈 영상이 사라져 여기서 걸린다.
 *
 * <h3>격리</h3>
 * <p>이 클래스 전용 이벤트 유형 코드·작업자 번호로 결과를 좁혀 공유 DB 의 다른 행에 기대지 않는다.
 *
 * @design ADR-068
 * @design ADR-058
 */
@SpringBootTest
@ActiveProfiles("local")
class InternalWorkScopePortalChannelIT {

    @Autowired private VideoRepository videoRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private TaskBoardQueryRepository taskBoardQueryRepository;
    @Autowired private AssignmentQueryRepository assignmentQueryRepository;
    @Autowired private StatsQueryRepository statsQueryRepository;

    private final JdbcTemplate jdbc;

    private String eventTypeCd;
    private long workerNo;

    private Long legacyNullRawSn;
    private Long controlOriginalRawSn;
    private Long portalUploadRawSn;
    private Long portalDatasetRawSn;
    private final List<Long> seededRawSns = new ArrayList<>();

    InternalWorkScopePortalChannelIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        int salt = ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        eventTypeCd = "EVIWS" + salt;
        workerNo = 9_300_000_000L + salt;

        // 대조군 둘 — 반드시 나와야 한다.
        legacyNullRawSn = save(LsDataRaw.createFromIngest(
                "IWS-LEGACY-" + salt, "CCTV-IWS", eventTypeCd, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/iws-legacy-" + salt + ".mp4", null, 30));
        controlOriginalRawSn = save(LsDataRaw.createFromIngest(
                "IWS-ORIGINAL-" + salt, "CCTV-IWS", eventTypeCd, "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/iws-original-" + salt + ".mp4", null, 30, "ORIGINAL"));

        // 포털 채널 둘 — 나오면 안 된다.
        LsDataRaw upload = LsDataRaw.createPortalUpload("portal-iws-" + salt, "/portal/uploads/iws-" + salt + ".mp4");
        portalUploadRawSn = save(upload);
        portalDatasetRawSn = save(LsDataRaw.createPortalDataset(
                LsDataRaw.portalDatasetClipId(salt, "iws-video"), "/portal/materials/" + salt + "/iws-video"));

        // 포털 두 팩토리는 이벤트 유형을 받지 않는다 — 필터로 같은 결과 집합에 들게 직접 채운다.
        //   (값이 채워지는 순간 섞인다는 것이 이 가드가 막는 시나리오다.)
        jdbc.update("UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = ? WHERE RAW_SN IN (?, ?)",
                eventTypeCd, portalUploadRawSn, portalDatasetRawSn);

        for (Long rawSn : seededRawSns) {
            assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, workerNo, 1L));
        }
    }

    @AfterEach
    void tearDown() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_TASK_ALTMNT WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
        seededRawSns.clear();
    }

    @Test
    @DisplayName("작업보드_목록에_포털_데이터셋_영상과_포털_업로드는_나오지_않고_관제_영상은_나온다")
    void 작업보드_목록에_포털_데이터셋_영상과_포털_업로드는_나오지_않고_관제_영상은_나온다() {
        // given — 적재 기본 배치 단계가 PENDING 이라 그 값으로 좁힌다.
        TaskBoardSearchCondition condition =
                new TaskBoardSearchCondition(LsDataRaw.STATUS_PENDING, null, null, eventTypeCd, null);

        // when
        List<Long> rawSns = taskBoardQueryRepository.search(condition, PageRequest.of(0, 20))
                .getContent().stream().map(LsDataRaw::getRawSn).toList();

        // then
        assertThat(rawSns).containsExactlyInAnyOrder(legacyNullRawSn, controlOriginalRawSn);
    }

    @Test
    @DisplayName("작업보드_이벤트유형_옵션에도_포털_채널_영상만_가진_코드는_나오지_않는다")
    void 작업보드_이벤트유형_옵션에도_포털_채널_영상만_가진_코드는_나오지_않는다() {
        // given — 대조군을 지워 그 코드를 포털 채널 영상만 갖게 한다.
        jdbc.update("DELETE FROM LS_TASK_ALTMNT WHERE RAW_DATA_ID IN (?, ?)", legacyNullRawSn, controlOriginalRawSn);
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN IN (?, ?)", legacyNullRawSn, controlOriginalRawSn);
        TaskBoardSearchCondition condition =
                new TaskBoardSearchCondition(LsDataRaw.STATUS_PENDING, null, null, null, null);

        // when
        List<String> options = taskBoardQueryRepository.findDistinctEventTypes(condition, 10_000);

        // then
        assertThat(options).doesNotContain(eventTypeCd);
    }

    @Test
    @DisplayName("배정_목록에_포털_데이터셋_영상과_포털_업로드의_배정은_나오지_않는다")
    void 배정_목록에_포털_데이터셋_영상과_포털_업로드의_배정은_나오지_않는다() {
        // given
        AssignmentSearchCondition condition = AssignmentSearchCondition.ofRequest(workerNo, null, null, null);

        // when
        List<Long> rawSns = assignmentQueryRepository.search(condition, PageRequest.of(0, 20))
                .getContent().stream().map(row -> row.assignment().getRawDataId()).toList();

        // then
        assertThat(rawSns).containsExactlyInAnyOrder(legacyNullRawSn, controlOriginalRawSn);
    }

    @Test
    @DisplayName("통계_이벤트유형별_영상_건수에_포털_채널_영상은_세지_않는다")
    void 통계_이벤트유형별_영상_건수에_포털_채널_영상은_세지_않는다() {
        // when
        Map<String, Long> counts = new java.util.HashMap<>();
        for (StatsQueryRepository.CountRow row : statsQueryRepository.countVideoByEventType()) {
            counts.put(row.getCode(), row.getCnt());
        }

        // then — 대조군 2건만. 포털 채널이 섞이면 4다.
        assertThat(counts).containsEntry(eventTypeCd, 2L);
    }

    @Test
    @DisplayName("고착_처리중_회수_후보에_포털_채널_영상은_들지_않는다")
    void 고착_처리중_회수_후보에_포털_채널_영상은_들지_않는다() {
        // given — 네 영상 모두 처리 중 + 갱신 시각 없음(회수 후보 조건)으로 맞춘다.
        for (Long rawSn : seededRawSns) {
            jdbc.update("UPDATE LS_DATA_RAW SET DATA_STTS_CD = ?, MDFCN_DT = NULL WHERE RAW_SN = ?",
                    LsDataRaw.DATA_STTS_PROCESSING, rawSn);
        }
        long afterRawSn = seededRawSns.stream().mapToLong(Long::longValue).min().orElseThrow() - 1;

        // when
        List<Long> candidates = videoRepository.findStaleProcessingRawSns(
                        LsDataRaw.DATA_STTS_PROCESSING, LocalDateTime.now(), afterRawSn,
                        PageRequest.of(0, 10_000))
                .stream().filter(seededRawSns::contains).toList();

        // then
        assertThat(candidates).containsExactlyInAnyOrder(legacyNullRawSn, controlOriginalRawSn);
    }

    private Long save(LsDataRaw raw) {
        Long rawSn = videoRepository.save(raw).getRawSn();
        seededRawSns.add(rawSn);
        return rawSn;
    }
}
