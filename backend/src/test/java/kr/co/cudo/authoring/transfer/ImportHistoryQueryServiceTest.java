package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.transfer.dto.ImportHistoryItemResponse;
import kr.co.cudo.authoring.transfer.entity.LsOtsdDatstTrnsfHstry;
import kr.co.cudo.authoring.transfer.repository.LsOtsdDatstTrnsfHstryRepository;
import kr.co.cudo.authoring.transfer.service.ImportHistoryQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 이관 이력 목록의 <b>조회 비용</b>과 <b>보류 축 분리</b>를 고정한다(API-207).
 *
 * <h3>왜 여기서 재는가</h3>
 * <p>"조회가 목록 크기에 비례하지 않는다"는 성질은 응답 본문에 드러나지 않아 통합 시험으로는 보이지
 * 않는다. 항목마다 상태를 읽어도 응답은 똑같이 옳게 나오고, 이력이 쌓인 뒤에야 느려진다. 그래서 상태
 * 조회가 <b>몇 번</b> 불렸는지를 직접 센다.
 *
 * @design DOMAIN-017
 * @design API-207
 * @design DFEAT-059
 * @design AC-053
 */
@ExtendWith(MockitoExtension.class)
class ImportHistoryQueryServiceTest {

    @Mock private LsOtsdDatstTrnsfHstryRepository historyRepository;
    @Mock private LsRawDataStatusRepository statusRepository;

    @InjectMocks private ImportHistoryQueryService service;

    /** 영상이 붙은 이력 {@code count} 건 — 홀수 번째만 승인 보류가 서 있다. */
    private void givenHistories(int count) {
        List<LsOtsdDatstTrnsfHstry> rows = new ArrayList<>();
        List<LsRawDataStatus> statuses = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            LsOtsdDatstTrnsfHstry history = LsOtsdDatstTrnsfHstry.start(
                    "/import/f" + i, "f" + i, "DS-" + i, "reviewer01");
            ReflectionTestUtils.setField(history, "trnsfSn", (long) i);
            history.succeed((long) i, 1, 1);
            rows.add(history);

            LsRawDataStatus status = LsRawDataStatus.initial((long) i);
            if (i % 2 == 1) {
                status.markDeidentNotCompleted();
            }
            statuses.add(status);
        }
        Page<LsOtsdDatstTrnsfHstry> page = new PageImpl<>(rows, Pageable.ofSize(100), count);
        given(historyRepository.findAll(any(Pageable.class))).willReturn(page);
        given(statusRepository.findByRawDataIdIn(anyCollection())).willAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            return statuses.stream().filter(s -> ids.contains(s.getRawDataId())).toList();
        });
    }

    @Test
    @DisplayName("★상태_조회는_목록_크기와_무관하게_한_번이다_N더하기1_아님")
    void 상태_조회는_목록_크기와_무관하게_한_번이다() {
        givenHistories(50);

        Page<ImportHistoryItemResponse> page = service.list(null, 0, 100);

        assertThat(page.getContent()).hasSize(50);
        // 항목마다 읽으면 50 번이 된다 — 이력은 산출물을 가져올수록 단조 증가하므로 그 비용이 계속 커진다.
        verify(statusRepository, times(1)).findByRawDataIdIn(anyCollection());
        verify(statusRepository, never()).findById(any());
    }

    @Test
    @DisplayName("★같은_이관_상태여도_영상마다_보류_판정이_갈린다")
    void 같은_이관_상태여도_영상마다_보류_판정이_갈린다() {
        givenHistories(4);

        List<ImportHistoryItemResponse> items = service.list(null, 0, 100).getContent();

        assertThat(items).allSatisfy(item -> assertThat(item.status()).isEqualTo("SUCCESS"));
        // 이관 상태 하나로는 이 값을 만들어낼 수 없다는 뜻이다.
        assertThat(items).extracting(ImportHistoryItemResponse::approvalHeld)
                .containsExactly(true, false, true, false);
    }

    @Test
    @DisplayName("영상이_없는_이력과_상태_행이_없는_이력은_보류_값이_비어_있다")
    void 영상이_없는_이력은_보류_값이_비어_있다() {
        LsOtsdDatstTrnsfHstry failed = LsOtsdDatstTrnsfHstry.start(
                "/import/x", "x", null, "reviewer01");
        ReflectionTestUtils.setField(failed, "trnsfSn", 1L);
        failed.fail("프레임이 한 건도 없습니다.");

        LsOtsdDatstTrnsfHstry orphan = LsOtsdDatstTrnsfHstry.start(
                "/import/y", "y", null, "reviewer01");
        ReflectionTestUtils.setField(orphan, "trnsfSn", 2L);
        orphan.succeed(77L, 1, 1);

        given(historyRepository.findAll(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(failed, orphan), Pageable.ofSize(100), 2));
        // 영상은 있는데 작업 상태 행이 없다 — 없는 것을 "보류 없음"으로 단정하지 않는다.
        given(statusRepository.findByRawDataIdIn(anyCollection())).willReturn(List.of());

        List<ImportHistoryItemResponse> items = service.list(null, 0, 100).getContent();

        assertThat(items.get(0).rawSn()).isNull();
        assertThat(items.get(0).approvalHeld()).isNull();
        assertThat(items.get(1).approvalHeld()).isNull();
    }

    @Test
    @DisplayName("영상이_하나도_없는_쪽에서는_상태를_아예_읽지_않는다")
    void 영상이_하나도_없는_쪽에서는_상태를_아예_읽지_않는다() {
        LsOtsdDatstTrnsfHstry failed = LsOtsdDatstTrnsfHstry.start(
                "/import/x", "x", null, "reviewer01");
        ReflectionTestUtils.setField(failed, "trnsfSn", 1L);
        failed.fail("프레임이 한 건도 없습니다.");
        given(historyRepository.findAll(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(failed), Pageable.ofSize(100), 1));

        service.list(null, 0, 100);

        verify(statusRepository, never()).findByRawDataIdIn(anyCollection());
    }

    @Test
    @DisplayName("이관_상태로_거를_때는_거르는_조회를_쓴다")
    void 이관_상태로_거를_때는_거르는_조회를_쓴다() {
        given(historyRepository.findByTrnsfSttsCd(any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0));

        service.list("FAILED", 0, 20);

        verify(historyRepository).findByTrnsfSttsCd(any(), any(Pageable.class));
        verify(historyRepository, never()).findAll(any(Pageable.class));
    }
}
