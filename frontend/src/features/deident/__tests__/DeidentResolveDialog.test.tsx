import type { ComponentProps } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  DeidentResolveDialog,
  isPreReportArtifact,
} from '@/features/deident/components/DeidentResolveDialog';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

/**
 * 비식별 신고 해소 — 후보 선택 다이얼로그 회귀 가드.
 *
 * ## 이 파일이 지키는 것 (`@design SCREEN-032` v29 · `API-202` v3 · `ADR-027`)
 * 서버의 자격 판정({@code eligible})에서 **「신고 이후에 만들어졌는가」 조건이 빠지고 무결성 하나만
 * 남았다.** 그래서 화면에서 갈라 지켜야 하는 축이 셋이다:
 *
 * 1. **선택 가능 축** — 신고 이전 산출물(지금 쓰고 있는 비식별 영상 포함)도 고를 수 있다.
 * 2. **잠금 축** — 무결성 불통과 후보는 **여전히** 고를 수 없고, 그 안내가 **무결성**을 말한다.
 *    구 문구는 시간을 말했고 그 시간 조건이 서버에서 빠져 **거짓**이 됐다.
 * 3. **표시 축** — 「현재 사용 중」·「신고 이전 파일」은 구분 표시일 뿐 선택을 막지 않는다.
 *
 * ⚠ 1과 3은 **짝으로** 단언한다. 잠금을 푸는 것만 지키면 「고를 수 있게 됐는데 그것이 신고된 그
 * 파일인지 아무도 모르는」 상태를 통과시킨다 — 자격이 더 이상 시간을 걸러 주지 않으므로 그 사실을
 * 아는 수단이 화면 표시밖에 없다.
 */

/** 신고 접수 시각 — 후보의 `modifiedAt` 과 대조하는 기준. 두 값 다 오프셋 없는 표기다. */
const REPORT_DT = '2026-06-05T10:00:00';
/** 신고 **이전**에 만들어진 산출물(= 지금 쓰고 있는 그 파일). 구 사양에서는 고를 수 없었다. */
const BEFORE_REPORT = '2026-06-01T09:00:00';
/** 신고 **이후**에 외부 솔루션이 새로 만든 산출물. */
const AFTER_REPORT = '2026-06-06T11:30:00';

interface Candidate {
  fileName: string;
  sizeBytes: number;
  modifiedAt: string;
  eligible: boolean;
  current: boolean;
}

/** 신고 이전부터 있던 현재 사용 중 산출물 — 이 CO 가 「고를 수 있게」 만든 바로 그 후보. */
const CURRENT_OLD: Candidate = {
  fileName: '001.mp4',
  sizeBytes: 48_213_504,
  modifiedAt: BEFORE_REPORT,
  eligible: true,
  current: true,
};

/** 신고 이후 산출된 새 후보. */
const FRESH: Candidate = {
  fileName: '001-mask.mp4',
  sizeBytes: 50_000_000,
  modifiedAt: AFTER_REPORT,
  eligible: true,
  current: false,
};

/** 무결성 불통과 — 영상으로 열리지 않는 파일(스텁 등). 이 축은 그대로 막혀 있어야 한다. */
const BROKEN: Candidate = {
  fileName: 'broken.mp4',
  sizeBytes: 18,
  modifiedAt: AFTER_REPORT,
  eligible: false,
  current: false,
};

function body(items: Candidate[]) {
  return { success: true, data: items, message: null, errorCode: null };
}

const RPRT_SN = 7;

function renderDialog(
  overrides: Partial<ComponentProps<typeof DeidentResolveDialog>> = {},
) {
  const onConfirm = vi.fn();
  const result = renderWithProviders(
    <DeidentResolveDialog
      rprtSn={RPRT_SN}
      rawSn={42}
      reportDt={REPORT_DT}
      onClose={vi.fn()}
      onConfirm={onConfirm}
      {...overrides}
    />,
  );
  return { ...result, onConfirm };
}

/** 후보 항목(라벨) — 파일명 기준. 배지·안내 단언을 이 범위로 좁힌다. */
function candidateRow(fileName: string) {
  return screen.getByTestId(`deident-candidate-${fileName}`);
}

describe('DeidentResolveDialog — 후보 선택', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
    vi.restoreAllMocks();
  });

  it('신고_이전부터_있던_현재_사용중_산출물도_선택할_수_있다', async () => {
    mock
      .onGet(`/deident-reports/${RPRT_SN}/deident-candidates`)
      .reply(200, body([CURRENT_OLD]));

    const { onConfirm } = renderDialog();

    await waitFor(() => {
      expect(candidateRow(CURRENT_OLD.fileName)).toBeInTheDocument();
    });

    // ★ 선택 가능 축 — 라디오가 잠겨 있지 않다(구 사양은 여기서 disabled 였다).
    const radio = within(candidateRow(CURRENT_OLD.fileName)).getByRole('radio');
    expect(radio).not.toBeDisabled();

    await userEvent.click(radio);
    expect(radio).toBeChecked();

    // 고르면 해소 버튼이 열린다.
    const confirm = screen.getByTestId('deident-resolve-confirm');
    expect(confirm).toBeEnabled();
    await userEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalledWith(CURRENT_OLD.fileName);
  });

  it('현재_사용중_배지가_그대로_있고_그_배지가_선택을_막지_않는다', async () => {
    mock
      .onGet(`/deident-reports/${RPRT_SN}/deident-candidates`)
      .reply(200, body([CURRENT_OLD, FRESH]));

    renderDialog();

    await waitFor(() => {
      expect(candidateRow(CURRENT_OLD.fileName)).toBeInTheDocument();
    });

    // 표시 축 — 배지는 그대로 있다.
    expect(
      screen.getByTestId(`deident-candidate-current-${CURRENT_OLD.fileName}`),
    ).toHaveTextContent('현재 사용 중');
    // 현재 사용 중이 아닌 후보에는 붙지 않는다(배지가 늘 뜨는 것을 잡는다).
    // ⚠ 부재 단언은 **그 자리가 실제로 선다는 존재 단언과 짝**이어야 한다 — 후보 행이 아예
    //    렌더되지 않아도 부재 단언은 공허하게 통과한다.
    expect(candidateRow(FRESH.fileName)).toBeInTheDocument();
    expect(
      screen.queryByTestId(`deident-candidate-current-${FRESH.fileName}`),
    ).toBeNull();

    // 표시와 잠금은 다른 축 — 배지가 붙은 후보도 고를 수 있다.
    expect(within(candidateRow(CURRENT_OLD.fileName)).getByRole('radio')).not.toBeDisabled();
  });

  it('신고_이전_파일에는_구분_표시가_붙고_이후_파일에는_붙지_않는다', async () => {
    mock
      .onGet(`/deident-reports/${RPRT_SN}/deident-candidates`)
      .reply(200, body([CURRENT_OLD, FRESH]));

    renderDialog();

    await waitFor(() => {
      expect(candidateRow(CURRENT_OLD.fileName)).toBeInTheDocument();
    });

    // 자격이 시간을 더 이상 걸러 주지 않으므로 이 표시가 「신고된 그 파일인가」의 유일한 단서다.
    expect(
      screen.getByTestId(`deident-candidate-prereport-${CURRENT_OLD.fileName}`),
    ).toHaveTextContent('신고 이전 파일');
    // 존재 단언과 짝지어 공허한 통과를 막는다(위 케이스와 같은 이유).
    expect(candidateRow(FRESH.fileName)).toBeInTheDocument();
    expect(
      screen.queryByTestId(`deident-candidate-prereport-${FRESH.fileName}`),
    ).toBeNull();

    // ⚠ 구분 표시일 뿐이다 — 잠그면 이 CO 가 없앤 그 잠금이 화면 쪽에 되살아난다.
    expect(within(candidateRow(CURRENT_OLD.fileName)).getByRole('radio')).not.toBeDisabled();
  });

  it('신고시각을_모르면_구분_표시를_하지_않는다', async () => {
    mock
      .onGet(`/deident-reports/${RPRT_SN}/deident-candidates`)
      .reply(200, body([CURRENT_OLD]));

    // reportDt 를 넘기지 않는다 — 판정 근거가 없는데 「신고 이전」이라고 단정하면 거짓말이 된다.
    renderDialog({ reportDt: undefined });

    await waitFor(() => {
      expect(candidateRow(CURRENT_OLD.fileName)).toBeInTheDocument();
    });
    expect(
      screen.queryByTestId(`deident-candidate-prereport-${CURRENT_OLD.fileName}`),
    ).toBeNull();
    // 그래도 고를 수는 있다(표시 부재가 선택을 막지 않는다).
    expect(within(candidateRow(CURRENT_OLD.fileName)).getByRole('radio')).not.toBeDisabled();
  });

  it('무결성_불통과_후보는_여전히_선택할_수_없고_안내가_무결성을_말한다', async () => {
    mock
      .onGet(`/deident-reports/${RPRT_SN}/deident-candidates`)
      .reply(200, body([BROKEN, FRESH]));

    renderDialog();

    await waitFor(() => {
      expect(candidateRow(BROKEN.fileName)).toBeInTheDocument();
    });

    // 잠금 축 — 이 축까지 풀면 서버가 409 로 돌려보내는 값을 화면이 보내게 된다.
    const row = candidateRow(BROKEN.fileName);
    expect(within(row).getByRole('radio')).toBeDisabled();
    expect(within(row).getByText('선택 불가')).toBeInTheDocument();

    // ★ 문구 축 — 사유는 「정상적으로 열리는 영상 파일이 아니다」 하나다.
    expect(row).toHaveTextContent('정상적으로 열리는 영상 파일이 아니라 선택할 수 없습니다.');
    // 구 문구는 시간을 말했고 그 조건이 서버 판정에서 빠져 거짓이 됐다 — 되살아나면 여기서 죽는다.
    expect(row).not.toHaveTextContent('신고 이후에 만들어진');

    // 정상 후보는 그 안내를 달지 않는다(안내가 전 후보에 뿌려지는 것을 잡는다).
    expect(candidateRow(FRESH.fileName)).not.toHaveTextContent('선택할 수 없습니다');
  });

  it('고르기_전에는_해소_버튼이_잠겨_있다', async () => {
    mock
      .onGet(`/deident-reports/${RPRT_SN}/deident-candidates`)
      .reply(200, body([FRESH]));

    renderDialog();

    await waitFor(() => {
      expect(candidateRow(FRESH.fileName)).toBeInTheDocument();
    });
    expect(screen.getByTestId('deident-resolve-confirm')).toBeDisabled();
  });
});

/**
 * 판정기 순수 단언 — 렌더 단언과 **짝**으로 둔다.
 *
 * 렌더 케이스는 고정 픽스처 두 개(이전/이후)만 지나가므로, 판정이 인자를 무시하도록 바뀌어도
 * 그중 하나가 우연히 맞으면 통과할 여지가 있다. 경계·부재·파싱 실패는 여기서 고정한다.
 */
describe('isPreReportArtifact', () => {
  it('신고보다_오래된_수정시각이면_참이다', () => {
    expect(isPreReportArtifact(BEFORE_REPORT, REPORT_DT)).toBe(true);
  });

  it('신고_이후_수정시각이면_거짓이다', () => {
    expect(isPreReportArtifact(AFTER_REPORT, REPORT_DT)).toBe(false);
  });

  it('같은_시각이면_신고_이후에_새로_만들어졌다고_말할_수_없으므로_참이다', () => {
    expect(isPreReportArtifact(REPORT_DT, REPORT_DT)).toBe(true);
  });

  it('신고시각이_없거나_해석되지_않으면_표시를_생략한다', () => {
    expect(isPreReportArtifact(BEFORE_REPORT, undefined)).toBe(false);
    expect(isPreReportArtifact(BEFORE_REPORT, 'not-a-date')).toBe(false);
    expect(isPreReportArtifact('not-a-date', REPORT_DT)).toBe(false);
  });
});
