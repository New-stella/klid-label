import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { DeidentReportListPage } from '@/pages/manage/DeidentReportListPage';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function pageBody<T>(content: T[]) {
  return {
    success: true,
    data: {
      content,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  };
}

const OPEN_ROW = {
  rprtSn: 7,
  rawSn: 42,
  reporterNo: 100,
  reporterName: null as string | null,
  reason: '얼굴 미블러',
  status: 'OPEN',
  reportDt: '2026-06-05T10:00:00',
  resolvedDt: null,
  stage: 'LABELING',
};

/** 신고자 축만 바꾼 행 (BE DeidentReportListResponse: reporterNo 원값 + reporterName 표시명). */
function rowWith(reporterName: string | null, reporterNo: number | null) {
  return { ...OPEN_ROW, reporterName, reporterNo };
}

describe('DeidentReportListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('OPEN_신고_목록_렌더', async () => {
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));

    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() => {
      expect(screen.getByTestId('deident-report-row-7')).toBeInTheDocument();
    });
    expect(screen.getByText('얼굴 미블러')).toBeInTheDocument();
    expect(screen.getByText('영상 #42')).toBeInTheDocument();
  });

  it('신고_단계가_사용자_언어로_표시된다', async () => {
    // given — 마킹 화면 신고 1건 + 라벨링 화면 신고 1건
    mock.onGet('/deident-reports').reply(
      200,
      pageBody([
        { ...OPEN_ROW, rprtSn: 7, stage: 'LABELING' },
        { ...OPEN_ROW, rprtSn: 8, stage: 'MARKING' },
      ]),
    );

    renderWithProviders(<DeidentReportListPage />);

    // then — 코드값 원문(MARKING/LABELING)·내부 컬럼명은 노출하지 않는다
    await waitFor(() => {
      expect(screen.getByTestId('deident-stage-7')).toHaveTextContent('라벨링');
    });
    expect(screen.getByTestId('deident-stage-8')).toHaveTextContent('마킹');
    expect(screen.queryByText('LABELING')).not.toBeInTheDocument();
    expect(screen.queryByText('MARKING')).not.toBeInTheDocument();
    expect(screen.queryByText(/DCLR_STP_CD/)).not.toBeInTheDocument();
  });

  it('단계가_null_인_레거시_신고는_미상으로_표시되고_빈칸이_아니다', async () => {
    // given — V171 이전 신고(백필하지 않아 영구히 null)
    mock.onGet('/deident-reports').reply(200, pageBody([{ ...OPEN_ROW, stage: null }]));

    renderWithProviders(<DeidentReportListPage />);

    // then — 빈칸이면 "값이 없다"와 "로딩 실패"가 구분되지 않는다
    await waitFor(() => {
      expect(screen.getByTestId('deident-stage-7')).toHaveTextContent('미상');
    });
    // 해소해도 단계별 재개가 없다는 사실이 툴팁으로 읽힌다
    expect(screen.getByTitle(/재마킹·프레임 재추출은 자동으로 진행되지 않습니다/)).toBeInTheDocument();
  });

  it('stage_필드가_없는_구_응답도_미상으로_표시된다', async () => {
    // given — 필드 추가 이전 형태의 응답(하위호환) — 화면이 깨지지 않아야 한다
    const legacyRow: Record<string, unknown> = { ...OPEN_ROW };
    delete legacyRow.stage;
    mock.onGet('/deident-reports').reply(200, pageBody([legacyRow]));

    renderWithProviders(<DeidentReportListPage />);

    await waitFor(() => {
      expect(screen.getByTestId('deident-stage-7')).toHaveTextContent('미상');
    });
  });

  // ── R3 — 해소는 "재비식별 산출물 선택" 절차다 ────────────────────────
  //
  // 외부 비식별 솔루션은 결과를 원본과 다른 이름으로 만든다(예: 001.mp4 → 001-mask.mp4).
  // 구 화면은 버튼 클릭 즉시 해소 요청을 보냈고, 서버는 기록된 경로 1개만 봤다 — 다른 이름으로
  // 산출되면 그 신고는 영영 해소되지 않았다. 이제 후보 목록에서 사람이 고른다.

  /** 후보 응답 헬퍼 — BE ApiResponse<DeidentCandidate[]> 미러. */
  function candidatesBody(items: unknown[]) {
    return { success: true, data: items, message: null, errorCode: null };
  }

  const FRESH_CANDIDATE = {
    fileName: '001-mask.mp4',
    sizeBytes: 2048,
    modifiedAt: '2026-06-05T10:05:00',
    eligible: true,
    current: false,
  };
  const CURRENT_CANDIDATE = {
    fileName: '001.mp4',
    sizeBytes: 1546,
    modifiedAt: '2026-06-05T09:00:00',
    eligible: false,
    current: true,
  };

  it('해소_처리_클릭시_바로_요청하지_않고_후보_선택_모달을_연다', async () => {
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));
    mock
      .onGet('/deident-reports/7/deident-candidates')
      .reply(200, candidatesBody([FRESH_CANDIDATE, CURRENT_CANDIDATE]));
    let resolveCalled = false;
    mock.onPost('/deident-reports/7/resolve').reply(() => {
      resolveCalled = true;
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<DeidentReportListPage />);
    await waitFor(() => {
      expect(screen.getByTestId('deident-resolve-7')).toBeInTheDocument();
    });

    await user.click(screen.getByTestId('deident-resolve-7'));

    // 모달만 열리고 아직 아무것도 전송되지 않는다.
    await waitFor(() => {
      expect(screen.getByTestId('deident-candidate-dialog')).toBeInTheDocument();
    });
    expect(resolveCalled).toBe(false);
    // 기본 선택 없음 → 확인 비활성
    expect(screen.getByTestId('deident-resolve-confirm')).toBeDisabled();
  });

  it('후보를_고르면_확인이_활성되고_선택한_파일명이_전송된다', async () => {
    let listCalls = 0;
    let sentBody: string | undefined;
    mock.onGet('/deident-reports').reply(() => {
      listCalls += 1;
      // 1차: OPEN 1건, 해소 후 재조회(2차): 0건
      return [200, pageBody(listCalls === 1 ? [OPEN_ROW] : [])];
    });
    mock
      .onGet('/deident-reports/7/deident-candidates')
      .reply(200, candidatesBody([FRESH_CANDIDATE, CURRENT_CANDIDATE]));
    mock.onPost('/deident-reports/7/resolve').reply((config) => {
      sentBody = config.data as string;
      return [200, { success: true, data: null, message: null, errorCode: null }];
    });

    const user = userEvent.setup();
    renderWithProviders(<DeidentReportListPage />);
    await waitFor(() => {
      expect(screen.getByTestId('deident-resolve-7')).toBeInTheDocument();
    });
    await user.click(screen.getByTestId('deident-resolve-7'));
    await screen.findByTestId('deident-candidate-001-mask.mp4');

    await user.click(screen.getByTestId('deident-candidate-001-mask.mp4'));
    expect(screen.getByTestId('deident-resolve-confirm')).toBeEnabled();
    await user.click(screen.getByTestId('deident-resolve-confirm'));

    // 서버가 기본값을 고르지 않으므로 선택값이 반드시 실려야 한다.
    await waitFor(() => expect(sentBody).toBeDefined());
    expect(JSON.parse(sentBody as string)).toEqual({ fileName: '001-mask.mp4' });
    // invalidate 로 목록 재조회 → 2회 이상 호출
    await waitFor(() => expect(listCalls).toBeGreaterThanOrEqual(2));
  });

  it('선택_불가_후보는_고를_수_없어_확인이_계속_비활성이다', async () => {
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));
    mock
      .onGet('/deident-reports/7/deident-candidates')
      .reply(200, candidatesBody([CURRENT_CANDIDATE]));

    const user = userEvent.setup();
    renderWithProviders(<DeidentReportListPage />);
    await waitFor(() => {
      expect(screen.getByTestId('deident-resolve-7')).toBeInTheDocument();
    });
    await user.click(screen.getByTestId('deident-resolve-7'));
    const row = await screen.findByTestId('deident-candidate-001.mp4');

    expect(row.querySelector('input')).toBeDisabled();
    await user.click(row);
    expect(screen.getByTestId('deident-resolve-confirm')).toBeDisabled();
    // 고를 수 있는 후보가 없다는 사실을 안내한다(빈칸으로 두지 않는다).
    expect(screen.getByTestId('deident-no-selectable-notice')).toBeInTheDocument();
  });

  it('후보가_0건이면_확인이_비활성이고_외부_비식별_안내가_뜬다', async () => {
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));
    mock.onGet('/deident-reports/7/deident-candidates').reply(200, candidatesBody([]));

    const user = userEvent.setup();
    renderWithProviders(<DeidentReportListPage />);
    await waitFor(() => {
      expect(screen.getByTestId('deident-resolve-7')).toBeInTheDocument();
    });
    await user.click(screen.getByTestId('deident-resolve-7'));

    await waitFor(() => {
      expect(screen.getByText(/외부 솔루션으로 비식별을 완료한 뒤/)).toBeInTheDocument();
    });
    expect(screen.getByTestId('deident-resolve-confirm')).toBeDisabled();
  });

  it('후보_목록에_내부_저장_경로가_렌더되지_않는다', async () => {
    // BE 응답은 파일명만 담지만, 화면이 경로를 조합해 보여주는 회귀를 막는다(CWE-209).
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));
    mock
      .onGet('/deident-reports/7/deident-candidates')
      .reply(200, candidatesBody([FRESH_CANDIDATE, CURRENT_CANDIDATE]));

    const user = userEvent.setup();
    renderWithProviders(<DeidentReportListPage />);
    await waitFor(() => {
      expect(screen.getByTestId('deident-resolve-7')).toBeInTheDocument();
    });
    await user.click(screen.getByTestId('deident-resolve-7'));
    const dialog = await screen.findByTestId('deident-candidate-dialog');

    expect(dialog.textContent ?? '').not.toMatch(/\//);
    expect(dialog.textContent ?? '').not.toMatch(/nas|storage|videos|deid\b/i);
  });

  it('다른_신고를_열면_이전_선택이_남지_않는다', async () => {
    // 선택이 남으면 "기본 선택 없음"이 깨지고 다른 신고의 파일명이 그대로 전송될 수 있다.
    mock
      .onGet('/deident-reports')
      .reply(200, pageBody([OPEN_ROW, { ...OPEN_ROW, rprtSn: 8, rawSn: 43 }]));
    mock
      .onGet('/deident-reports/7/deident-candidates')
      .reply(200, candidatesBody([FRESH_CANDIDATE]));
    mock
      .onGet('/deident-reports/8/deident-candidates')
      .reply(200, candidatesBody([FRESH_CANDIDATE]));

    const user = userEvent.setup();
    renderWithProviders(<DeidentReportListPage />);
    await waitFor(() => {
      expect(screen.getByTestId('deident-resolve-7')).toBeInTheDocument();
    });

    await user.click(screen.getByTestId('deident-resolve-7'));
    await user.click(await screen.findByTestId('deident-candidate-001-mask.mp4'));
    expect(screen.getByTestId('deident-resolve-confirm')).toBeEnabled();
    await user.click(screen.getByRole('button', { name: '취소' }));

    await user.click(screen.getByTestId('deident-resolve-8'));
    await screen.findByTestId('deident-candidate-001-mask.mp4');
    expect(screen.getByTestId('deident-resolve-confirm')).toBeDisabled();
  });

  // 신고자 표시 — 내부 사용자 번호(USER_NO)가 아니라 표시명을 보여야 한다.
  // BE DeidentReportListResponse 는 reporterNo(원값) + reporterName(LS_ACNT_USER.USER_NM) 을 모두 내린다.
  it('신고자는_표시명으로_노출된다', async () => {
    // given: 사용자 마스터에서 이름이 해석된 신고
    mock.onGet('/deident-reports').reply(200, pageBody([rowWith('김작업', 100)]));

    // when
    renderWithProviders(<DeidentReportListPage />);

    // then: 이름이 보이고 내부 번호는 신고자 칸에 노출되지 않는다
    await waitFor(() => {
      expect(screen.getByTestId('deident-reporter-7')).toHaveTextContent('김작업');
    });
    expect(screen.getByTestId('deident-reporter-7')).not.toHaveTextContent('100');
  });

  it('표시명이_null이면_기존_reporterNo로_폴백한다', async () => {
    // given: 레거시 행·탈퇴 계정 등으로 이름 해석 실패 (reporterName=null)
    mock.onGet('/deident-reports').reply(200, pageBody([rowWith(null, 100)]));

    // when
    renderWithProviders(<DeidentReportListPage />);

    // then: 신고자가 통째로 사라지지 않고 원값으로 표시된다
    await waitFor(() => {
      expect(screen.getByTestId('deident-reporter-7')).toHaveTextContent('100');
    });
  });

  it('표시명이_공백문자뿐이면_기존_reporterNo로_폴백한다', async () => {
    // given: 이름이 빈 문자열/공백만 (null 만 보면 빈칸이 그대로 표시된다)
    mock.onGet('/deident-reports').reply(200, pageBody([rowWith('   ', 100)]));

    // when
    renderWithProviders(<DeidentReportListPage />);

    // then
    await waitFor(() => {
      expect(screen.getByTestId('deident-reporter-7')).toHaveTextContent('100');
    });
  });

  it('표시명과_reporterNo가_모두_없으면_대시로_표시한다', async () => {
    // given
    mock.onGet('/deident-reports').reply(200, pageBody([rowWith(null, null)]));

    // when
    renderWithProviders(<DeidentReportListPage />);

    // then: 기존 '-' 동작 유지 (빈칸 금지)
    await waitFor(() => {
      expect(screen.getByTestId('deident-reporter-7')).toHaveTextContent('-');
    });
  });

  // ── 사양 SCREEN-032 '상태' 전용 컬럼 회귀 가드 ──────────────────────

  it('상태_전용_컬럼이_미처리_처리완료를_배지로_말한다', async () => {
    // given: 미처리 신고 1건.
    // 구 구현은 '처리' 컬럼(해소 버튼/해소일 텍스트)이 상태를 암묵 표현해, 상태를 읽으려면
    // 버튼 유무를 역추론해야 했다 — 그 컬럼은 '무엇을 할 수 있는가'(액션) 축이라 축이 다르다.
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));

    // when
    renderWithProviders(<DeidentReportListPage />);

    // then
    await waitFor(() => {
      expect(screen.getByTestId('deident-status-7')).toHaveTextContent('미처리');
    });
    expect(
      screen.getByRole('columnheader', { name: '상태' }),
    ).toBeInTheDocument();
  });

  it('해소된_신고는_상태_컬럼이_처리완료로_표시된다', async () => {
    // given
    mock.onGet('/deident-reports').reply(
      200,
      pageBody([
        {
          ...OPEN_ROW,
          status: 'RESOLVED',
          resolvedDt: '2026-06-06T11:00:00',
        },
      ]),
    );

    // when
    renderWithProviders(<DeidentReportListPage />);

    // then
    await waitFor(() => {
      expect(screen.getByTestId('deident-status-7')).toHaveTextContent('처리완료');
    });
  });

  // ── 사양 SCREEN-032 컬럼 순서 회귀 가드 ─────────────────────────────

  it('컬럼_순서가_사양과_같다', async () => {
    // given: 사양은 신고 사실(누가·왜·언제)을 먼저 읽히고 그 뒤에 부가 축(단계·상태)을 둔다.
    // 구 구현은 '신고 단계'가 '신고자' 앞에 있어 신고자보다 분류 축을 먼저 읽게 했다.
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));

    // when
    renderWithProviders(<DeidentReportListPage />);

    // then
    await waitFor(() => {
      expect(screen.getByTestId('deident-report-row-7')).toBeInTheDocument();
    });
    const headers = screen
      .getAllByRole('columnheader')
      .map((th) => th.textContent?.trim());
    expect(headers).toEqual([
      '신고 번호',
      '영상',
      '신고자',
      '사유',
      '신고일시',
      '신고 단계',
      '상태',
      '처리',
    ]);
  });

  it('본문_셀_순서도_헤더_순서를_따른다', async () => {
    // 헤더만 옮기고 td 는 그대로 두면 값이 다른 컬럼 아래로 들어간다 — 헤더 단언만으로는 못 잡는다.
    mock.onGet('/deident-reports').reply(200, pageBody([OPEN_ROW]));

    renderWithProviders(<DeidentReportListPage />);

    const row = await screen.findByTestId('deident-report-row-7');
    const cells = Array.from(row.querySelectorAll('td'));
    // 인덱스 2=신고자, 3=사유, 4=신고일시, 5=신고 단계, 6=상태
    expect(cells[2]).toHaveAttribute('data-testid', 'deident-reporter-7');
    expect(cells[3]).toHaveTextContent('얼굴 미블러');
    expect(cells[5]).toHaveAttribute('data-testid', 'deident-stage-7');
    expect(cells[6]).toHaveAttribute('data-testid', 'deident-status-7');
  });
});
