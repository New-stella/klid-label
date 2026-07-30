package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewRepository#searchByStatus} 의 <b>검수 워크플로우 화이트리스트 상시 적용</b> 검증
 * — 배치가 처리중(PROCESSING)인 영상이 REVIEWER 검수 목록에 누출되던 결함(사용자 보고)의 회귀 가드.
 *
 * <p>검수 목록에 노출 가능한 상태 집합 = {@code PENDING/IN_REVIEW/APPROVED/REJECTED}. 배치/작업 상태
 * ({@code ASSIGNED/BATCH_QUEUED/PROCESSING/COMPLETED/FAILED})는 상시 제외되어야 한다.
 * 상태 row 를 직접 시드해 검증한다(V146 이후 부모 영상 {@code LS_DATA_RAW} 를 함께 시드한다).
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 * {@link ReviewRepository} 는 {@code @ControlRepo} 이므로 {@code controlTransactionManager} 안에서 동작한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class ReviewListWhitelistFilterIT {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 다른 시드와 충돌하지 않도록 높은 rawDataId 대역을 사용한다. */
    private static final long BASE_ID = 990_000_000L;

    private long seed(long offset, String status, LocalDateTime updDt) {
        long rawDataId = BASE_ID + offset;
        // 부모 영상 선시드 — V146 FK(LS_RAW_DATA_STATUS → LS_DATA_RAW).
        RawVideoFixture.seedRaw(jdbcTemplate, rawDataId);
        LsRawDataStatus s = LsRawDataStatus.builder()
                .rawDataId(rawDataId)
                .dataSttsCd(status)
                .stpCycl(0)
                .igiCycl(0)
                .updDt(updDt)
                .build();
        reviewRepository.saveAndFlush(s);
        return rawDataId;
    }

    @Test
    @DisplayName("status_미지정시_검수워크플로우_상태만_반환하고_배치작업_상태는_제외된다")
    void nullStatusReturnsOnlyWhitelistStatuses() {
        // given — 9개 상태를 각각 1건씩 시드
        LocalDateTime now = LocalDateTime.now();
        long pending = seed(1, LsRawDataStatus.STTS_PENDING, now);
        long inReview = seed(2, LsRawDataStatus.STTS_IN_REVIEW, now);
        long approved = seed(3, LsRawDataStatus.STTS_APPROVED, now);
        long rejected = seed(4, LsRawDataStatus.STTS_REJECTED, now);
        long assigned = seed(5, LsRawDataStatus.STTS_ASSIGNED, now);
        long batchQueued = seed(6, LsRawDataStatus.STTS_BATCH_QUEUED, now);
        long processing = seed(7, LsRawDataStatus.STTS_PROCESSING, now);
        long completed = seed(8, LsRawDataStatus.STTS_COMPLETED, now);
        long failed = seed(9, LsRawDataStatus.STTS_FAILED, now);

        // when — status 미지정(null) 전체 조회
        Page<LsRawDataStatus> page = reviewRepository.searchByStatus(null, PageRequest.of(0, 500));
        Set<Long> ids = page.getContent().stream()
                .map(LsRawDataStatus::getRawDataId).collect(java.util.stream.Collectors.toSet());

        // then — 화이트리스트 4개만 노출, 배치/작업 상태 5개는 누출 차단
        assertThat(ids).contains(pending, inReview, approved, rejected);
        assertThat(ids).doesNotContain(assigned, batchQueued, processing, completed, failed);
        // 반환 row 의 상태는 전부 화이트리스트 내
        assertThat(page.getContent())
                .extracting(LsRawDataStatus::getDataSttsCd)
                .allMatch(ReviewRepository.REVIEW_STATUS_WHITELIST::contains);
    }

    @Test
    @DisplayName("status_IN_REVIEW_지정시_IN_REVIEW_상태만_반환한다")
    void statusInReviewReturnsOnlyInReview() {
        // given
        LocalDateTime now = LocalDateTime.now();
        long inReview = seed(11, LsRawDataStatus.STTS_IN_REVIEW, now);
        long pending = seed(12, LsRawDataStatus.STTS_PENDING, now);
        long approved = seed(13, LsRawDataStatus.STTS_APPROVED, now);

        // when
        Page<LsRawDataStatus> page = reviewRepository.searchByStatus("IN_REVIEW", PageRequest.of(0, 500));
        Set<Long> ids = page.getContent().stream()
                .map(LsRawDataStatus::getRawDataId).collect(java.util.stream.Collectors.toSet());

        // then — 지정 상태만, 다른 화이트리스트 상태도 제외
        assertThat(ids).contains(inReview);
        assertThat(ids).doesNotContain(pending, approved);
        assertThat(page.getContent())
                .extracting(LsRawDataStatus::getDataSttsCd)
                .allMatch(v -> v.equals(LsRawDataStatus.STTS_IN_REVIEW));
    }

    @Test
    @DisplayName("화이트리스트_밖_상태_PROCESSING_지정시_빈결과로_누출을_차단한다")
    void statusOutsideWhitelistYieldsEmpty() {
        // given — PROCESSING row 존재
        seed(21, LsRawDataStatus.STTS_PROCESSING, LocalDateTime.now());

        // when — 화이트리스트 밖 값(PROCESSING) 지정 → 교집합 공집합
        Page<LsRawDataStatus> page = reviewRepository.searchByStatus("PROCESSING", PageRequest.of(0, 500));

        // then — 전체 빈 결과 (누구의 PROCESSING row 도 노출되지 않음)
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("결과_정렬은_updDt_DESC_를_유지한다")
    void resultsOrderedByUpdDtDesc() {
        // given — 화이트리스트 상태 3건을 서로 다른 updDt 로 시드 (t1 < t2 < t3)
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(30);
        LocalDateTime t2 = LocalDateTime.now().minusMinutes(20);
        LocalDateTime t3 = LocalDateTime.now().minusMinutes(10);
        long oldest = seed(31, LsRawDataStatus.STTS_PENDING, t1);
        long newest = seed(32, LsRawDataStatus.STTS_IN_REVIEW, t3);
        long middle = seed(33, LsRawDataStatus.STTS_APPROVED, t2);

        // when
        Page<LsRawDataStatus> page = reviewRepository.searchByStatus(null, PageRequest.of(0, 500));

        // then — 시드한 3건의 상대 순서가 updDt DESC (newest, middle, oldest)
        List<Long> orderedMine = page.getContent().stream()
                .map(LsRawDataStatus::getRawDataId)
                .filter(id -> id == oldest || id == middle || id == newest)
                .toList();
        assertThat(orderedMine).containsExactly(newest, middle, oldest);
    }
}
