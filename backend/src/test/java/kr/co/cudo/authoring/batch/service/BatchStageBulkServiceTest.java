package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchStageBulkRequest;
import kr.co.cudo.authoring.batch.dto.BatchStageBulkResponse;
import kr.co.cudo.authoring.batch.dto.BatchStageSkipBulkRequest;
import kr.co.cudo.authoring.batch.dto.BatchStageSkipResponse;
import kr.co.cudo.authoring.batch.dto.BatchStageRerunResponse;
import kr.co.cudo.authoring.batch.dto.BulkRawSns;
import kr.co.cudo.authoring.batch.dto.BatchBulkRetryRequest;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 작업 묶음 일괄 스킵·해제·재수행 서비스 단위 테스트. [@design API-212] [@design API-213] [@design API-214]
 *
 * <p>핵심 수용 기준 셋:
 * <ol>
 *   <li><b>부분 성공</b> — 한 건의 거부·예외가 다른 건을 막지 않고, 한 건도 성공 못 해도 예외를 던지지
 *       않으며(200), 실패 사유에 내부 정보가 실리지 않는다.</li>
 *   <li><b>판정 위임</b> — 스킵 가능 여부·되돌린 묶음 여부·승인 이력 등 모든 판정은 단건 서비스가 한다.
 *       여기서 복제하면 두 경로가 갈린다.</li>
 *   <li><b>대상 묶음은 VLM 하나</b> — 오토라벨 일괄은 400.</li>
 * </ol>
 */
class BatchStageBulkServiceTest {

    private static final String REASON = "외부 시계열 분석 벤더 연동 전이라 시계열 없이 진행";
    private static final String VLM = BatchStageBundle.VLM.name();
    private static final String AUTOLABEL = BatchStageBundle.AUTOLABEL.name();

    private BatchStageSkipService skipService;
    private BatchStageRerunService rerunService;
    private BatchStageBulkService service;

    @BeforeEach
    void setUp() {
        skipService = mock(BatchStageSkipService.class);
        rerunService = mock(BatchStageRerunService.class);
        service = new BatchStageBulkService(skipService, rerunService);
        when(skipService.skip(anyLong(), anyString(), anyString()))
                .thenAnswer(inv -> new BatchStageSkipResponse(
                        inv.getArgument(0), inv.getArgument(1), true, "[수동 스킵] " + REASON, null));
        when(rerunService.rerun(anyLong(), anyString()))
                .thenAnswer(inv -> new BatchStageRerunResponse(
                        inv.getArgument(0), inv.getArgument(1), true));
    }

    private static BatchStageSkipBulkRequest skipReq(Long... rawSns) {
        return new BatchStageSkipBulkRequest(List.of(rawSns), REASON);
    }

    private static BatchStageBulkRequest req(Long... rawSns) {
        return new BatchStageBulkRequest(List.of(rawSns));
    }

    // ────────────────────────── 부분 성공 ──────────────────────────

    @Test
    @DisplayName("전건_성공하면_successCount가_대상건수와_같다")
    void allSucceed() {
        BatchStageBulkResponse response = service.skipAll(VLM, skipReq(1L, 2L, 3L));

        assertThat(response.successCount()).isEqualTo(3);
        assertThat(response.failureCount()).isZero();
        assertThat(response.results()).extracting(BatchStageBulkResponse.Item::reason).containsOnlyNulls();
    }

    @Test
    @DisplayName("★한_건이_거부돼도_나머지는_처리된다_부분성공")
    void partialSuccess() {
        // given — 2번만 파생영상이라 단건 서비스가 400 을 던진다.
        when(skipService.skip(eq(2L), anyString(), anyString())).thenThrow(new CustomException(
                ErrorCode.INVALID_INPUT, "이 영상은 다른 영상에서 파생된 영상이라 배치 단계를 조작할 수 없습니다."));

        // when
        BatchStageBulkResponse response = service.skipAll(VLM, skipReq(1L, 2L, 3L));

        // then — 1·3 은 실제로 처리됐고 2 만 사유와 함께 실패로 기록된다.
        verify(skipService).skip(eq(1L), anyString(), anyString());
        verify(skipService).skip(eq(3L), anyString(), anyString());
        assertThat(response.successCount()).isEqualTo(2);
        assertThat(response.failureCount()).isEqualTo(1);
        assertThat(response.results()).anySatisfy(item -> {
            assertThat(item.rawSn()).isEqualTo(2L);
            assertThat(item.success()).isFalse();
            assertThat(item.reason()).contains("파생");
        });
    }

    @Test
    @DisplayName("★한_건도_성공하지_못해도_예외를_던지지_않는다")
    void allFailStillReturns() {
        when(rerunService.rerun(anyLong(), anyString()))
                .thenThrow(new CustomException(ErrorCode.INVALID_INPUT,
                        "되돌린 작업 묶음이 아니거나 지원하지 않는 값입니다."));

        BatchStageBulkResponse response = service.rerunAll(VLM, req(1L, 2L));

        assertThat(response.successCount()).isZero();
        assertThat(response.failureCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("★예상치_못한_예외도_그_건만_실패로_격리되고_내부정보를_노출하지_않는다")
    void unexpectedExceptionIsolated() {
        when(skipService.skip(eq(2L), anyString(), anyString())).thenThrow(new IllegalStateException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint \"uk_ls_batch_proc_log\"]"));

        BatchStageBulkResponse response = service.skipAll(VLM, skipReq(1L, 2L, 3L));

        assertThat(response.successCount()).isEqualTo(2);
        assertThat(response.results()).anySatisfy(item -> {
            assertThat(item.rawSn()).isEqualTo(2L);
            assertThat(item.reason())
                    .isEqualTo(BatchStageBulkService.UNEXPECTED_FAILURE_REASON)
                    .doesNotContain("constraint")
                    .doesNotContain("uk_")
                    .doesNotContain("Exception");
        });
    }

    // ────────────────────────── 목록 규칙 ──────────────────────────

    @Test
    @DisplayName("중복된_rawSn은_1건으로_취급해_한_번만_처리한다")
    void duplicatesCollapse() {
        BatchStageBulkResponse response = service.clearAll(VLM, req(5L, 5L, 5L, 6L));

        verify(skipService, times(1)).clearSkip(eq(5L), anyString());
        assertThat(response.results()).extracting(BatchStageBulkResponse.Item::rawSn)
                .containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("★요청_순서를_보존한다_화면이_행을_짝지을_수_있어야_한다")
    void preservesRequestOrder() {
        BatchStageBulkResponse response = service.skipAll(VLM, skipReq(9L, 3L, 7L, 1L));

        assertThat(response.results()).extracting(BatchStageBulkResponse.Item::rawSn)
                .containsExactly(9L, 3L, 7L, 1L);
    }

    @Test
    @DisplayName("실질_대상이_0건이면_400이다")
    void emptyTargetsRejected() {
        List<Long> nullsOnly = new ArrayList<>();
        nullsOnly.add(null);

        assertThatThrownBy(() -> service.clearAll(VLM, new BatchStageBulkRequest(nullsOnly)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("★일괄_상한은_기존_일괄_재처리와_같은_100이다")
    void bulkLimitMatchesExistingContract() {
        // 설계가 "배치 일괄 재처리와 같은 계약"이라고 규정한다 — 두 값이 갈리면 화면이 엔드포인트마다
        //   다른 상한을 알아야 한다.
        assertThat(BulkRawSns.MAX_SIZE).isEqualTo(BatchBulkRetryRequest.MAX_SIZE).isEqualTo(100);
    }

    // ────────────────────────── 판정 위임 (복제 금지) ──────────────────────────

    @Test
    @DisplayName("★스킵은_단건_서비스에_위임한다_판정을_복제하지_않는다")
    void skipDelegatesToSingleService() {
        service.skipAll(VLM, skipReq(9L));

        // 요청 값 그대로가 아니라 <해석된 묶음 이름>을 넘긴다(요청 문자열이 하류로 흐르지 않게).
        verify(skipService).skip(9L, VLM, REASON);
        verifyNoInteractions(rerunService);
    }

    @Test
    @DisplayName("★해제는_단건_서비스에_위임한다_판정을_복제하지_않는다")
    void clearDelegatesToSingleService() {
        service.clearAll(VLM, req(9L));

        verify(skipService).clearSkip(9L, VLM);
        verifyNoInteractions(rerunService);
    }

    @Test
    @DisplayName("★재수행은_단건_서비스에_위임한다_되돌린묶음_승인이력_판정을_복제하지_않는다")
    void rerunDelegatesToSingleService() {
        service.rerunAll(VLM, req(9L));

        verify(rerunService).rerun(9L, VLM);
        verifyNoInteractions(skipService);
    }

    @Test
    @DisplayName("★사유는_요청당_하나이며_대상_전건에_같은_값으로_기록된다")
    void oneReasonAppliesToEveryTarget() {
        service.skipAll(VLM, skipReq(1L, 2L, 3L));

        verify(skipService).skip(1L, VLM, REASON);
        verify(skipService).skip(2L, VLM, REASON);
        verify(skipService).skip(3L, VLM, REASON);
    }

    // ────────────────────────── 대상 묶음 제한 (R5) ──────────────────────────

    @Test
    @DisplayName("★★일괄축은_오토라벨을_받지_않는다_400_세_경로_모두")
    void autolabelRejectedOnEveryBulkPath() {
        // 오토라벨을 일괄로 열면 산출물 품질 축을 사람이 대량으로 건너뛸 수 있게 된다.
        assertThatThrownBy(() -> service.skipAll(AUTOLABEL, skipReq(1L)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.clearAll(AUTOLABEL, req(1L)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.rerunAll(AUTOLABEL, req(1L)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);

        // 거부는 <어떤 단건 서비스도 부르지 않고> 끝난다 — 부분 처리가 남으면 안 된다.
        verifyNoInteractions(skipService, rerunService);
    }

    @Test
    @DisplayName("★단건_경로는_오토라벨을_계속_받는다_이_제한은_일괄축만이다")
    void singlePathStillAcceptsAutolabel() {
        // 일괄 제한을 단건에 전이시키지 않았음을 계약으로 고정한다(단건 서비스를 건드리지 않았다).
        assertThat(BatchStageBundle.parse(AUTOLABEL)).isEqualTo(BatchStageBundle.AUTOLABEL);
    }

    @Test
    @DisplayName("★미지의_묶음도_400이고_거부_메시지가_요청값을_되비추지_않는다")
    void unknownBundleRejectedWithoutEcho() {
        String malicious = "<script>alert(1)</script>";

        assertThatThrownBy(() -> service.skipAll(malicious, skipReq(1L)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    // 반사 XSS·로그 오염 차단(CWE-79/117) — 기존 requireSkippableBundle 관례.
                    assertThat(ce.getMessage()).doesNotContain(malicious).doesNotContain("script");
                });
        assertThatThrownBy(() -> service.rerunAll(null, req(1L)))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(skipService, rerunService);
    }

    // ────────────────────────── D1 — 이미/아님은 성공 ──────────────────────────

    @Test
    @DisplayName("★★D1_이미_건너뛴_영상도_성공이다_단건과_같은_append_only")
    void alreadySkippedIsSuccess() {
        // 단건 skip 은 append-only 로 항상 성공한다(사유 변경 이력 자체가 감사 대상).
        //   일괄이 "이미 스킵됨"을 실패로 만들면 같은 조건에서 단건과 일괄이 달라진다.
        //   → 여기서 사전 조회 가드를 넣지 않았음을 고정한다.
        BatchStageBulkResponse response = service.skipAll(VLM, skipReq(1L));

        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.results().get(0).reason()).isNull();
        verify(skipService).skip(1L, VLM, REASON);
    }

    @Test
    @DisplayName("★★D1_건너뛴_상태가_아닌_영상의_해제도_성공이다_단건과_같은_멱등")
    void clearWhenNotSkippedIsSuccess() {
        // 단건 clearSkip 은 스킵 상태가 아니면 멱등 no-op 성공이다(해제 두 번 눌러도 감사 행이 안 쌓이게).
        BatchStageBulkResponse response = service.clearAll(VLM, req(1L));

        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.results().get(0).reason()).isNull();
        verify(skipService).clearSkip(1L, VLM);
    }

    @Test
    @DisplayName("★해제_실패도_그_건만_사유와_함께_돌아온다")
    void clearFailureIsolated() {
        doThrow(new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."))
                .when(skipService).clearSkip(eq(2L), anyString());

        BatchStageBulkResponse response = service.clearAll(VLM, req(1L, 2L));

        assertThat(response.successCount()).isEqualTo(1);
        assertThat(response.results()).anySatisfy(item -> {
            assertThat(item.rawSn()).isEqualTo(2L);
            assertThat(item.reason()).isEqualTo("영상을 찾을 수 없습니다.");
        });
    }

    @Test
    @DisplayName("요청_본문이_null이면_400이다")
    void nullRequestRejected() {
        assertThatThrownBy(() -> service.skipAll(VLM, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(skipService, never()).skip(anyLong(), anyString(), any());
    }

    // ────────────────────────────────────────────────────────────────────────
    // ★ 건별로 갈릴 수 없는 것은 루프 전에 판정한다 — 사유는 요청당 하나다
    // ────────────────────────────────────────────────────────────────────────

    /**
     * ★★같은 입력에 <b>단건은 400, 일괄은 200 + 전건 실패</b>이던 비대칭의 회귀 가드.
     *
     * <p>제어문자만으로 이루어진 사유는 {@code @NotBlank} 를 통과하지만 정제 후 빈 문자열이 되어 단건
     * 서비스가 400 을 던진다. 그 400 이 건별 실패로 삼켜지면 클래스 Javadoc 의 「건별 실패는 영상 없음·
     * 파생영상·예상 밖 오류에서만 난다」 단언이 깨지고, 확정적으로 실패할 N 건의 DB 왕복까지 돈다.
     */
    @Test
    @DisplayName("★★제어문자만_있는_사유는_일괄에서도_요청_전체가_400이다_단건과_같은_코드")
    void controlCharOnlyReasonRejectsWholeRequest() {
        BatchStageSkipBulkRequest req =
                new BatchStageSkipBulkRequest(List.of(1L, 2L, 3L), "\n\r\t");

        assertThatThrownBy(() -> service.skipAll(VLM, req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 루프에 들어가기 전에 거부해야 한다 — 들어가면 건별 실패로 삼켜지고 왕복도 낭비된다.
        verifyNoInteractions(skipService);
    }

    @Test
    @DisplayName("정상_사유는_종전대로_전건_처리된다")
    void validReasonStillProcessesEveryTarget() {
        BatchStageSkipBulkRequest req =
                new BatchStageSkipBulkRequest(List.of(1L, 2L), REASON);

        BatchStageBulkResponse res = service.skipAll(VLM, req);

        assertThat(res.successCount()).isEqualTo(2);
        verify(skipService, times(2)).skip(anyLong(), eq(VLM), eq(REASON));
    }

}
