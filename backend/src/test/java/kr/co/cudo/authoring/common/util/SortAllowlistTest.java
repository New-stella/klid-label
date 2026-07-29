package kr.co.cudo.authoring.common.util;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 (HIGH-5) — 정렬 키 allowlist 검증.
 *
 * <p>allowlist 밖 정렬 키가 Spring Data 로 직행하면 {@code PropertyReferenceException}(500 + 내부
 * 필드명 노출)이 되거나, 느슨한 매핑이면 FE 미노출 내부 컬럼(원본 경로 등)으로 정렬이 가능해진다
 * (CWE-20 / CWE-209). 본 유틸은 <b>미등록 키를 400 으로 명시 거부</b>한다.
 */
class SortAllowlistTest {

    private static final Sort FALLBACK = Sort.by(Sort.Direction.DESC, "regDt");

    @Test
    @DisplayName("allowlist_등록키는_엔티티_필드로_매핑되고_방향이_보존된다")
    void mapsAllowedKeys() {
        Sort resolved = SortAllowlist.resolve(
                Sort.by(Sort.Order.asc("capturedAt")), SortAllowlist.TASK_BOARD, FALLBACK);

        assertThat(resolved).containsExactly(Sort.Order.asc("shtDt"));
    }

    @Test
    @DisplayName("allowlist_밖_정렬키는_INVALID_INPUT_예외로_거부된다")
    void rejectsUnknownKey() {
        assertThatThrownBy(() -> SortAllowlist.resolve(
                Sort.by(Sort.Order.asc("rawFilePathNm")), SortAllowlist.TASK_BOARD, FALLBACK))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("SQL_인젝션_시도_정렬키도_예외로_거부된다")
    void rejectsInjectionAttempt() {
        for (String malicious : new String[]{"'; DROP TABLE LS_DATA_RAW; --", "password", "1=1", "raw.rawFilePathNm"}) {
            assertThatThrownBy(() -> SortAllowlist.resolve(
                    Sort.by(Sort.Order.asc(malicious)), SortAllowlist.TASK_BOARD, FALLBACK))
                    .isInstanceOf(CustomException.class);
        }
    }

    @Test
    @DisplayName("거부_메시지에_입력값이나_내부_필드명이_노출되지_않는다")
    void errorMessageDoesNotLeakInput() {
        CustomException thrown = null;
        try {
            SortAllowlist.resolve(Sort.by(Sort.Order.asc("rawFilePathNm")), SortAllowlist.TASK_BOARD, FALLBACK);
        } catch (CustomException e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).doesNotContain("rawFilePathNm");
    }

    @Test
    @DisplayName("정렬_미지정이면_폴백_정렬을_반환한다")
    void fallbackWhenUnsorted() {
        assertThat(SortAllowlist.resolve(Sort.unsorted(), SortAllowlist.TASK_BOARD, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(SortAllowlist.resolve(null, SortAllowlist.TASK_BOARD, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("정렬_기준_개수가_상한을_넘으면_400")
    void rejectsTooManyOrders() {
        // 상한 = allowlist 고유 엔티티 필드 수(regDt/shtDt/rawSn = 3). 4건째부터 거부된다.
        Sort fourOrders = Sort.by(
                Sort.Order.desc("regDt"), Sort.Order.asc("capturedAt"),
                Sort.Order.desc("rawSn"), Sort.Order.asc("videoId"));

        assertThatThrownBy(() -> SortAllowlist.resolve(fourOrders, SortAllowlist.TASK_BOARD, FALLBACK))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("정렬_기준을_대량으로_보내도_쿼리플랜_오염전에_거부된다")
    void rejectsMassiveOrderList() {
        List<Sort.Order> massive = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            massive.add(i % 2 == 0 ? Sort.Order.asc("regDt") : Sort.Order.desc("shtDt"));
        }

        CustomException thrown = catchCustomException(
                () -> SortAllowlist.resolve(Sort.by(massive), SortAllowlist.TASK_BOARD, FALLBACK));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        // CWE-209 — 입력 개수/상한 같은 내부 정책을 메시지로 흘리지 않는다.
        assertThat(thrown.getMessage()).isEqualTo("정렬 기준이 너무 많습니다.")
                .doesNotContain("500", "3");
    }

    @Test
    @DisplayName("동일_엔티티필드_중복_정렬키는_한_번만_적용")
    void duplicateEntityFieldAppliedOnce() {
        // capturedAt/shtDt 는 같은 엔티티 필드(shtDt), videoId/rawSn 은 같은 엔티티 필드(rawSn).
        Sort resolved = SortAllowlist.resolve(
                Sort.by(Sort.Order.asc("capturedAt"), Sort.Order.desc("shtDt"), Sort.Order.desc("rawSn")),
                SortAllowlist.TASK_BOARD, FALLBACK);

        // 첫 지정(shtDt ASC)만 살아남고 뒤 중복은 drop — ORDER BY 순열이 무한히 늘지 않는다.
        assertThat(resolved).containsExactly(Sort.Order.asc("shtDt"), Sort.Order.desc("rawSn"));
    }

    private static CustomException catchCustomException(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (CustomException e) {
            return e;
        }
    }

    @Test
    @DisplayName("작업목록_allowlist는_FE_노출_컬럼만_보유한다")
    void taskBoardAllowlistExposesOnlyPublicColumns() {
        assertThat(SortAllowlist.TASK_BOARD).containsOnlyKeys("regDt", "capturedAt", "shtDt", "rawSn", "videoId");
        assertThat(SortAllowlist.TASK_BOARD.values()).containsOnly("regDt", "shtDt", "rawSn");
    }

    @Test
    @DisplayName("검수목록_allowlist는_FE_노출_컬럼만_보유한다")
    void reviewAllowlistExposesOnlyPublicColumns() {
        // 작업목록과 대칭 가드 — 내부 컬럼(원본 경로·PII 축 등)이 추가되면 여기서 깨진다.
        assertThat(SortAllowlist.REVIEW).containsOnlyKeys("submittedAt", "updDt", "videoId", "status");
        assertThat(SortAllowlist.REVIEW.values()).containsOnly("updDt", "rawDataId", "dataSttsCd");
    }

    @Test
    @DisplayName("정렬_항목_상한은_allowlist_고유_엔티티필드_수에서_파생된다")
    void maxOrdersDerivedFromAllowlist() {
        // 리포지토리가 상한을 하드코딩하면 allowlist 확장 시 조용히 어긋난다 — 파생 계약을 고정한다.
        assertThat(SortAllowlist.maxOrders(SortAllowlist.REVIEW)).isEqualTo(3);
        assertThat(SortAllowlist.maxOrders(SortAllowlist.TASK_BOARD)).isEqualTo(3);
    }

    // ------------------------------------------------------------- lenient 모드

    private static final Sort REVIEW_FALLBACK = Sort.by(Sort.Direction.DESC, "updDt");

    @Test
    @DisplayName("관용모드는_미등록_정렬키를_거부하지_않고_기본정렬로_폴백한다")
    void lenientFallsBackOnUnknownKey() {
        // 검수목록은 변경 전 sort 를 받지도 않아 어떤 값이든 200 이었다 — 400 은 하위호환 파손이다.
        for (String unknown : new String[]{"regDt", "cctvName", "password", "'; DROP TABLE LS_DATA_RAW; --"}) {
            assertThat(SortAllowlist.resolveLenient(
                    Sort.by(Sort.Order.asc(unknown)), SortAllowlist.REVIEW, REVIEW_FALLBACK))
                    .as("미등록 키 [%s] 는 기본 정렬로 폴백되어야 한다", unknown)
                    .isEqualTo(REVIEW_FALLBACK);
        }
    }

    @Test
    @DisplayName("관용모드도_등록키는_그대로_매핑하고_미등록키만_무시한다")
    void lenientKeepsAllowedKeys() {
        Sort resolved = SortAllowlist.resolveLenient(
                Sort.by(Sort.Order.asc("submittedAt"), Sort.Order.desc("regDt")),
                SortAllowlist.REVIEW, REVIEW_FALLBACK);

        // 미등록 키(regDt)는 drop, 허용 키(submittedAt→updDt)는 유지 — 미등록 키가 쿼리에 닿는 경로는 없다.
        assertThat(resolved).containsExactly(Sort.Order.asc("updDt"));
    }

    @Test
    @DisplayName("관용모드는_정렬항목_개수_초과도_400이_아니라_기본정렬로_폴백한다")
    void lenientFallsBackWhenTooManyOrders() {
        List<Sort.Order> massive = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            massive.add(i % 2 == 0 ? Sort.Order.asc("submittedAt") : Sort.Order.desc("videoId"));
        }

        assertThat(SortAllowlist.resolveLenient(Sort.by(massive), SortAllowlist.REVIEW, REVIEW_FALLBACK))
                .isEqualTo(REVIEW_FALLBACK);
        assertThat(SortAllowlist.resolveLenient(
                Sort.by(Sort.Order.asc("submittedAt"), Sort.Order.asc("videoId"),
                        Sort.Order.asc("status"), Sort.Order.desc("updDt")),
                SortAllowlist.REVIEW, REVIEW_FALLBACK))
                .isEqualTo(REVIEW_FALLBACK);
    }

    @Test
    @DisplayName("엄격모드와_관용모드의_정책_차이가_고정된다")
    void strictAndLenientPoliciesDiffer() {
        Sort unknown = Sort.by(Sort.Order.asc("rawFilePathNm"));

        // 작업목록(strict) — 변경 전에도 200 이 아니었으므로(500) 400 거부를 유지한다.
        assertThatThrownBy(() -> SortAllowlist.resolve(unknown, SortAllowlist.TASK_BOARD, FALLBACK))
                .isInstanceOf(CustomException.class);
        // 검수목록(lenient) — 변경 전 항상 200 이었으므로 폴백한다.
        assertThat(SortAllowlist.resolveLenient(unknown, SortAllowlist.REVIEW, REVIEW_FALLBACK))
                .isEqualTo(REVIEW_FALLBACK);
    }

    @Test
    @DisplayName("관용모드도_정렬_미지정이면_폴백_정렬을_반환한다")
    void lenientFallbackWhenUnsorted() {
        assertThat(SortAllowlist.resolveLenient(Sort.unsorted(), SortAllowlist.REVIEW, REVIEW_FALLBACK))
                .isEqualTo(REVIEW_FALLBACK);
        assertThat(SortAllowlist.resolveLenient(null, SortAllowlist.REVIEW, REVIEW_FALLBACK))
                .isEqualTo(REVIEW_FALLBACK);
    }
}
