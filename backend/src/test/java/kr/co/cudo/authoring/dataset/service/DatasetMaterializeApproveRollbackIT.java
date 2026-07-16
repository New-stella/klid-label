package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRepository;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.review.service.ReviewService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Phase 2 — 승인 트랜잭션 원자성 <b>실 DB(PostgreSQL Testcontainer) 롤백 통합 테스트</b>.
 *
 * <p>기존 롤백 검증은 mock 단위테스트({@code ReviewServiceEventPublishTest})뿐이라, 누군가 실수로
 * {@code materialize} 에 {@code Propagation.REQUIRES_NEW} 를 넣어도 회귀를 잡지 못한다. 본 테스트는
 * 실 DB 에서 materialize 를 강제 실패(소스 조회 단계 예외)시키고 {@link ReviewService#approve} 를 호출해
 * 다음이 <b>모두 롤백</b>됨을 검증한다(요구사항-verifier 갭 #4 폐쇄):
 * <ul>
 *   <li>{@code LS_RAW_DATA_STATUS} — APPROVED 미전이(IN_REVIEW 원복)</li>
 *   <li>{@code LS_LABEL_VERSION} — 승인 스냅샷 미생성</li>
 *   <li>{@code LS_DATASET_VIDEO_META} — 통합 메타 미적재</li>
 *   <li>{@code LS_META_REPL_OUTBOX} — 복제 이벤트 미생성</li>
 * </ul>
 *
 * <p>동결 소스 리포지토리만 {@link MockBean} 으로 예외를 던지게 하고, 나머지(상태 전이·버전 스냅샷·
 * 트랜잭션 경계)는 전부 실제 경로다. 공유 컨테이너 격리를 위해 시드는 {@code RBTX-} 고유 clipId 로 좁힌다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetMaterializeApproveRollbackIT {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private LsDataSrcRepository srcRepository;

    @Autowired
    private LsDataLblRepository labelRepository;

    @Autowired
    private LsDatasetVideoMetaRepository metaRepository;

    /** materialize 소스 조회를 강제 실패시켜 승인 트랜잭션을 롤백 유발한다. */
    @MockBean
    private DatasetMetaSourceRepository sourceRepository;

    private final JdbcTemplate jdbc;
    private Long rawSn;
    private Long srcSn;

    DatasetMaterializeApproveRollbackIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        if (rawSn == null) {
            return;
        }
        jdbc.update("DELETE FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ?", rawSn);
        jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
        jdbc.update("DELETE FROM LS_LABEL_VERSION WHERE DATA_RAW_SN = ?", rawSn);
        if (srcSn != null) {
            jdbc.update("DELETE FROM LS_DATA_LBL WHERE SRC_SN = ?", srcSn);
        }
        jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
        jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
        jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
    }

    private void seedInReviewVideoWithLabel() {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "RBTX-" + UUID.randomUUID(), "CCTV-RBTX", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/rbtx.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();

        // 검수 진행 상태(IN_REVIEW) — approve 의 상태 전이 전제.
        reviewRepository.save(LsRawDataStatus.builder()
                .rawDataId(rawSn)
                .dataSttsCd(LsRawDataStatus.STTS_IN_REVIEW)
                .stpCycl(0)
                .igiCycl(0)
                .updDt(LocalDateTime.now())
                .build());

        // 프레임 1 + 라벨 1 — commitApproved 가 LS_LABEL_VERSION 스냅샷을 실제 생성하도록.
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(rawSn, 1L, "/var/frames/rbtx/1.jpg", LocalDateTime.now()));
        srcSn = frame.getSrcSn();
        labelRepository.save(LsDataLbl.createAutoBbox(
                srcSn, null, "person", "[1,2,3,4]", new BigDecimal("0.9"), null));
    }

    @Test
    @DisplayName("materialize_실패시_approve_실DB_전체롤백_상태_버전_통합메타_outbox_미반영")
    void materializeFailure_rollsBackApproveAcrossAllTables() {
        // given — IN_REVIEW 영상 + 프레임/라벨 시드, materialize 소스 조회는 예외를 던진다.
        seedInReviewVideoWithLabel();
        when(sourceRepository.findSnapshotSource(anyLong()))
                .thenThrow(new RuntimeException("forced materialize failure"));
        TokenClaims reviewer = new TokenClaims(
                "1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));

        // when — 승인 시 materialize 가 실패해 예외가 전파된다.
        assertThatThrownBy(() -> reviewService.approve(rawSn, reviewer))
                .isInstanceOf(RuntimeException.class);

        // then — 같은 트랜잭션의 모든 변경이 롤백된다.
        LsRawDataStatus reloaded = reviewRepository.findByRawDataId(rawSn).orElseThrow();
        assertThat(reloaded.getDataSttsCd())
                .as("APPROVED 로 전이되지 않고 IN_REVIEW 로 원복되어야 한다")
                .isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);

        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_LABEL_VERSION WHERE DATA_RAW_SN = ?", Integer.class, rawSn);
        assertThat(versionCount).as("승인 스냅샷(LS_LABEL_VERSION) 미생성").isZero();

        assertThat(metaRepository.findByRawSn(rawSn))
                .as("통합 메타(LS_DATASET_VIDEO_META) 미적재").isEmpty();

        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ?", Integer.class, rawSn);
        assertThat(outboxCount).as("복제 outbox(LS_META_REPL_OUTBOX) 미생성").isZero();
    }
}
