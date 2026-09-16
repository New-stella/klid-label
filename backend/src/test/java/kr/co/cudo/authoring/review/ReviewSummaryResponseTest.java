package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.review.dto.ReviewSummaryResponse;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검수목록 KPI 응답 DTO ↔ 상태 화이트리스트 <b>커버리지 드리프트</b> 가드.
 *
 * <p>리포지토리({@code ReviewQueryRepository.countByStatus})는
 * {@link ReviewRepository#REVIEW_STATUS_WHITELIST} 를 순회해 버킷을 만드는데, 응답 DTO 는
 * {@code pending/inReview/approved/rejected} 4필드를 고정으로 갖는다. 화이트리스트에 5번째 상태가
 * 추가되면 리포지토리의 {@code bucketSum == total} 대조는 <b>통과</b>하고(예외·로그 없음) 응답
 * {@code total} 만 목록 {@code totalElements} 보다 작아진다 — 조용한 과소집계다.
 */
class ReviewSummaryResponseTest {

    @Test
    @DisplayName("응답_DTO_매핑이_상태_화이트리스트_전체를_커버한다")
    void dtoCoversWholeWhitelist() {
        // 두 정의가 어긋나는 순간 여기서 깨진다(어느 한쪽만 확장하는 것을 막는다).
        assertThat(ReviewSummaryResponse.MAPPED_STATUSES)
                .containsExactlyInAnyOrderElementsOf(ReviewRepository.REVIEW_STATUS_WHITELIST);
        assertThat(ReviewRepository.REVIEW_STATUS_WHITELIST).hasSize(4);
    }

    @Test
    @DisplayName("화이트리스트_전_상태에_건수가_있으면_total_이_전부_합산된다")
    void totalSumsEveryWhitelistedStatus() {
        // given — 화이트리스트를 순회해 각 상태 1건씩 (리포지토리 집계 결과와 동일한 형태)
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String code : ReviewRepository.REVIEW_STATUS_WHITELIST) {
            counts.put(code, 1L);
        }

        ReviewSummaryResponse response = ReviewSummaryResponse.of(counts, 0L);

        // then — 5번째 상태가 추가되면 total(4) != 화이트리스트 크기(5) 로 즉시 드러난다.
        assertThat(response.total())
                .as("total 은 화이트리스트 전 상태의 합이어야 한다 — 미매핑 상태가 있으면 과소집계다")
                .isEqualTo(ReviewRepository.REVIEW_STATUS_WHITELIST.size());
    }

    @Test
    @DisplayName("매핑되지_않은_상태에_건수가_있으면_조용히_누락하지_않고_실패한다")
    void unmappedStatusWithCountFailsFast() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(LsRawDataStatus.STTS_PENDING, 2L);
        counts.put("FUTURE_REVIEW_STATUS", 3L);

        assertThatThrownBy(() -> ReviewSummaryResponse.of(counts, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FUTURE_REVIEW_STATUS");
    }

    @Test
    @DisplayName("매핑되지_않은_상태라도_건수가_0이면_기존_동작을_유지한다")
    void unmappedStatusWithZeroCountIsHarmless() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(LsRawDataStatus.STTS_PENDING, 2L);
        counts.put("FUTURE_REVIEW_STATUS", 0L);

        ReviewSummaryResponse response = ReviewSummaryResponse.of(counts, 0L);

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.pending()).isEqualTo(2);
    }

    @Test
    @DisplayName("빈_맵이나_null_이어도_전_필드가_0이다")
    void emptyOrNullCountsYieldZeroes() {
        for (Map<String, Long> counts : java.util.Arrays.asList(null, new LinkedHashMap<String, Long>())) {
            ReviewSummaryResponse response = ReviewSummaryResponse.of(counts, 0L);
            assertThat(response.total()).isZero();
            assertThat(response.pending()).isZero();
            assertThat(response.inReview()).isZero();
            assertThat(response.approved()).isZero();
            assertThat(response.rejected()).isZero();
        }
    }
}
