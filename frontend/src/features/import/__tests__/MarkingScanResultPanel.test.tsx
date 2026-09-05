import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { MarkingScanResultPanel } from '@/features/import/components/MarkingScanResultPanel';
import type { MarkingScanItem, MarkingScanResult } from '@/features/import/markingTypes';
import { renderWithProviders } from '@/test/renderWithProviders';

function item(overrides: Partial<MarkingScanItem>): MarkingScanItem {
  return {
    markingFileName: 'org_REPORT_A.json',
    clipId: 'A',
    videoFileName: 'A.mp4',
    videoFound: true,
    segmentCount: 1,
    markCount: 21,
    declaredFps: 29.997,
    probedFps: 29.97,
    videoFrameCount: 2396,
    importable: true,
    warnings: [],
    ...overrides,
  };
}

function result(overrides: Partial<MarkingScanResult> = {}): MarkingScanResult {
  return {
    items: [item({})],
    scannedFileCount: 214,
    matchedCount: 106,
    importableCount: 104,
    unmatchedVideoCount: 2,
    unmatchedVideoNames: [],
    truncated: false,
    warnings: [],
    ...overrides,
  };
}

/**
 * ★적재를 막는 사유와 막지 않는 사유가 한 목록에 섞여 오므로, 알림 유무로 판정하면 두 방향
 * 모두 틀린다. 그래서 이 짝을 한 픽스처에 함께 둔다 —
 * - 알림이 있는데 적재 가능한 항목(막지 않는 알림)
 * - 알림이 없는데 적재 불가한 항목
 * 판정을 알림 개수로 바꾸면 아래 두 단언 가운데 하나가 반드시 깨진다.
 */
const ADVISORY_BUT_IMPORTABLE = item({
  markingFileName: 'org_REPORT_ADVISORY.json',
  importable: true,
  warnings: [{ code: 'SYMBOLIC_LINK_SKIPPED', message: '바로가기를 건너뛰었습니다.' }],
});
const SILENT_BUT_BLOCKED = item({
  markingFileName: 'org_REPORT_SILENT.json',
  importable: false,
  warnings: [],
});

function renderPanel(overrides: Partial<MarkingScanResult> = {}, selected = new Set<string>()) {
  const onSubmit = vi.fn();
  renderWithProviders(
    <MarkingScanResultPanel
      result={result(overrides)}
      selected={selected}
      metaReady
      submitting={false}
      errorMessage={null}
      onToggle={vi.fn()}
      onToggleAll={vi.fn()}
      onSubmit={onSubmit}
    />,
  );
  return { onSubmit };
}

describe('SCREEN-039 이벤트 마킹 — 검사 결과', () => {
  it('★일부만_훑었으면_그_사실을_알린다', () => {
    renderPanel({ truncated: true });

    const alert = screen.getByTestId('marking-scan-truncated');
    expect(alert).toHaveTextContent('폴더 전체가 아닙니다');
    expect(alert).toHaveAttribute('role', 'alert');
  });

  it('전부_훑었으면_그_안내를_띄우지_않는다', () => {
    renderPanel({ truncated: false });
    expect(screen.queryByTestId('marking-scan-truncated')).not.toBeInTheDocument();
  });

  it('★적재_가능_여부의_판정은_importable_하나가_한다_알림이_있어도_고를_수_있다', () => {
    renderPanel({ items: [ADVISORY_BUT_IMPORTABLE, SILENT_BUT_BLOCKED] });

    expect(
      screen.getByTestId('marking-pair-check-org_REPORT_ADVISORY.json'),
    ).not.toBeDisabled();
  });

  it('★알림이_하나도_없어도_importable_이_거짓이면_고를_수_없다', () => {
    renderPanel({ items: [ADVISORY_BUT_IMPORTABLE, SILENT_BUT_BLOCKED] });

    expect(screen.getByTestId('marking-pair-check-org_REPORT_SILENT.json')).toBeDisabled();
    expect(
      screen.getByTestId('marking-pair-importable-org_REPORT_SILENT.json'),
    ).toHaveTextContent('불가');
  });

  it('짝을_찾지_못한_항목도_목록에_남는다', () => {
    renderPanel({
      items: [
        item({
          markingFileName: 'org_REPORT_LOST.json',
          videoFound: false,
          videoFileName: 'LOST.mp4',
          importable: false,
          warnings: [{ code: 'VIDEO_NOT_FOUND', message: '영상을 찾지 못했습니다.' }],
        }),
      ],
    });

    const row = screen.getByTestId('marking-pair-row-org_REPORT_LOST.json');
    expect(row).toHaveTextContent('짝을 찾지 못함');
    expect(row).toHaveTextContent('영상을 찾지 못했습니다.');
  });

  it('★어느_문서도_가리키지_않은_영상_수를_함께_보여준다', () => {
    renderPanel({ unmatchedVideoCount: 2, unmatchedVideoNames: ['안내영상.mp4', '테스트.mp4'] });

    expect(screen.getByTestId('marking-unmatched-video-count')).toHaveTextContent('2');
    expect(screen.getByTestId('marking-unmatched-videos')).toHaveTextContent('안내영상.mp4');
  });

  it('★상한_숫자를_화면에_적지_않는다_서버_설정이_바뀌면_화면이_거짓을_말한다', () => {
    renderPanel({ truncated: true });

    // 「일부만 훑었다」는 플래그로만 알린다. 상한값을 베끼면 그 값이 바뀌는 순간 거짓이 된다.
    expect(screen.getByTestId('marking-scan-truncated').textContent ?? '').not.toMatch(/\d{2,}/);
  });

  it('고른_항목이_없으면_적재할_수_없고_사유가_보인다', () => {
    renderPanel({}, new Set());

    expect(screen.getByTestId('marking-submit-button')).toBeDisabled();
    expect(screen.getByTestId('marking-submit-disabled-reason')).toHaveTextContent(
      '하나 이상 고르세요',
    );
  });

  it('공통_정보가_비면_고른_항목이_있어도_적재할_수_없다', () => {
    renderWithProviders(
      <MarkingScanResultPanel
        result={result()}
        selected={new Set(['org_REPORT_A.json'])}
        metaReady={false}
        submitting={false}
        errorMessage={null}
        onToggle={vi.fn()}
        onToggleAll={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    expect(screen.getByTestId('marking-submit-button')).toBeDisabled();
    expect(screen.getByTestId('marking-submit-disabled-reason')).toHaveTextContent(
      '일괄 공통 정보',
    );
  });

  it('고른_항목과_공통_정보가_모두_갖춰지면_적재할_수_있다', async () => {
    const user = userEvent.setup();
    const { onSubmit } = renderPanel({}, new Set(['org_REPORT_A.json']));

    expect(screen.queryByTestId('marking-submit-disabled-reason')).not.toBeInTheDocument();
    await user.click(screen.getByTestId('marking-submit-button'));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});
