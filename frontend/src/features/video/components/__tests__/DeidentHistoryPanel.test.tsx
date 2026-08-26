import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { DeidentHistoryItem } from '@/features/video/types';

import { DeidentHistoryPanel } from '../DeidentHistoryPanel';

/**
 * 비식별 이력 패널의 **표면 정합** 가드 — 확정 시안(SCREEN-009 `.deident-panel`/`.dh-item`) 기준.
 *
 * 동작(집계 감춤·상태 문구·배지 색 계열)은 `features/video/__tests__/VideoDetailDeidentHistory.test.tsx`
 * 가 영상 상세 화면째로 검증한다. 여기서는 그 파일이 보지 않는 **표면**만 다루며, 컴포넌트를 직접
 * 렌더해 같은 화면의 다른 패널 변경에 흔들리지 않게 한다.
 */
const base: DeidentHistoryItem = {
  procLogSn: 1,
  procSttsCd: 'SUCCEEDED',
  reqKndCd: null,
  reqDt: '2026-01-15T09:31:04',
};

function item(over: Partial<DeidentHistoryItem> = {}): DeidentHistoryItem {
  return { ...base, ...over };
}

const withCounts = item({
  procLogSn: 9,
  faceDtctCnt: 1284,
  noPltDtctCnt: 96,
  frmeCnt: 5250,
  prcsBgngDt: '2026-01-15T09:31:12',
  prcsEndDt: '2026-01-15T09:36:40',
});

describe('DeidentHistoryPanel — 확정 시안 표면 정합', () => {
  it('★패널과_항목은_한_묶음이다_패널이_흰_카드여야_항목의_흰_카드가_성립한다', () => {
    // ⚠ 이 셋(G1 패널 래퍼 · G2 항목 표면 · G3 모서리)은 따로 옮기면 오히려 나빠진다.
    //    항목만 회색 타일에서 흰 카드로 바꾸면 카드 배경과 같은 색이 되어(흰 위의 흰) 구분이
    //    지금보다 약해진다. 패널이 자기 테두리를 갖고, 항목이 그 안에서 테두리로 갈리는 형태여야
    //    비로소 성립한다 — 그래서 한 케이스에서 함께 단언한다.
    render(<DeidentHistoryPanel history={[withCounts]} />);

    const panel = screen.getByTestId('deident-history-panel');
    expect(panel.className, '패널 표면').toContain('bg-white');
    expect(panel.className, '패널 테두리').toContain('border');
    expect(panel.className, '좌측 강조바').toContain('border-l-4');
    expect(panel.className, '강조바 색').toContain('border-l-gray-400');
    expect(panel.className, '패널 모서리 8px').toContain('rounded-lg');
    expect(panel.className, '패널 안쪽 여백 16px').toContain('p-4');

    const li = screen.getByRole('listitem');
    expect(li.className, '항목 표면').toContain('bg-white');
    expect(li.className, '항목 테두리 — 흰 위의 흰을 가르는 유일한 수단').toContain('border');
    expect(li.className, '항목 모서리 6px').toContain('rounded-md');
    // 되돌림 차단 — 구 회색 타일(bg-gray-50)·8px 모서리로 돌아가면 실패한다.
    expect(li.className).not.toContain('bg-gray-50');
    expect(li.className).not.toContain('rounded-lg');
  });

  it('실패와_진행중_회차만_좌측_강조선을_세우고_완료는_평선이다', () => {
    render(
      <DeidentHistoryPanel
        history={[
          item({ procLogSn: 3, procSttsCd: 'FAILED' }),
          item({ procLogSn: 2, procSttsCd: 'REQUESTED' }),
          item({ procLogSn: 1, procSttsCd: 'SUCCEEDED' }),
        ]}
      />,
    );

    const [failed, progress, done] = screen.getAllByRole('listitem');
    expect(failed).toHaveAttribute('data-state', 'fail');
    expect(failed.className).toContain('border-l-danger');
    expect(progress).toHaveAttribute('data-state', 'progress');
    expect(progress.className).toContain('border-l-primary');
    expect(done).not.toHaveAttribute('data-state');
    expect(done.className, '완료는 평선이다').not.toContain('border-l-4');
    expect(done.className).toContain('border-gray-200');
  });

  it('알_수_없는_상태코드는_진행중과_같은_강조선을_받는다', () => {
    // 상태 문구는 이미 '진행 중' 으로 폴백한다 — 강조선만 다른 상태를 그리면 한 항목이 두 상태를 말한다.
    render(<DeidentHistoryPanel history={[item({ procSttsCd: 'WHO_KNOWS' })]} />);

    expect(screen.getByRole('listitem')).toHaveAttribute('data-state', 'progress');
  });

  it('회차_종류에_출처를_밝히는_부제가_붙는다', () => {
    render(
      <DeidentHistoryPanel
        history={[
          item({ procLogSn: 2, reqKndCd: 'REDEIDENT' }),
          item({ procLogSn: 1, reqKndCd: null }),
        ]}
      />,
    );

    const [redeid, batch] = screen.getAllByRole('listitem');
    expect(within(redeid).getByText('검수 완료 후 재비식별')).toBeInTheDocument();
    expect(within(batch).getByText('배치 비식별')).toBeInTheDocument();
  });

  it('집계는_좌측으로_모으고_값은_고정폭_숫자로_읽힌다', () => {
    // 세 값은 같은 외부 리포트에서 함께 오는 한 덩어리라, 넓은 패널에서 균등 분할하면 서로
    // 무관한 값처럼 흩어져 읽힌다 — 열 폭에 상한을 둬 왼쪽으로 모은다.
    render(<DeidentHistoryPanel history={[withCounts]} />);

    const dl = screen.getByRole('listitem').querySelector('dl');
    expect(dl?.className).toContain('grid-cols-[repeat(3,minmax(0,240px))]');
    expect(dl?.className, '집계 줄 위 구분선').toContain('border-t');

    const dt = screen.getByText('얼굴 검출');
    expect(dt.className, '항목 이름은 라벨 계열(14/600)').toContain('text-label');
    const dd = screen.getByText('1,284');
    expect(dd.className, '수치는 고정폭 글꼴').toContain('font-mono');
    expect(dd.className).toContain('text-body-sm');
  });

  it('처리_구간에_시계_아이콘이_함께_붙는다', () => {
    render(<DeidentHistoryPanel history={[withCounts]} />);

    const line = screen.getByText(/2026-01-15 09:31.*2026-01-15 09:36/);
    expect(line.querySelector('svg'), '처리 구간 앞 시계 아이콘').not.toBeNull();
  });

  it('요청_일시는_넓은_화면에서만_줄바꿈되지_않는다', () => {
    // 폭이 좁아지면 일시가 배지를 밀어내기 전에 접히는 편이 낫다(시안 반응형 규칙).
    render(<DeidentHistoryPanel history={[withCounts]} />);

    const req = screen.getByText(/^요청 /);
    expect(req.className).toContain('xl:whitespace-nowrap');
    expect(req.className).toContain('whitespace-normal');
  });

  it('패널_푸터가_표시_범위와_집계없는_회차의_뜻을_알린다', () => {
    render(<DeidentHistoryPanel history={[withCounts]} />);

    expect(
      screen.getByText(
        '한 항목이 위탁 1회차이며 최신 회차가 위입니다. 최근 20건까지 표시하고, 외부 처리 결과를 받지 못한 회차는 검출 집계와 처리 구간 줄을 표시하지 않습니다.',
      ),
    ).toBeInTheDocument();
  });

  it('이_영역을_함께_보는_대상을_머리말에_밝힌다', () => {
    // 바로 위 배치 실패 패널이 검수자 전용인 것과 달라, 누가 보는 영역인지가 표시돼야 한다.
    render(<DeidentHistoryPanel history={[withCounts]} />);

    expect(screen.getByText('검수자 · 라벨링 작업자')).toBeInTheDocument();
  });

  it('상태_배지는_캡션이_아니라_라벨_계열_타이포다', () => {
    render(<DeidentHistoryPanel history={[item({ procSttsCd: 'SUCCEEDED' })]} />);

    const badge = within(screen.getByRole('listitem')).getByText('완료');
    expect(badge.className).toContain('text-label');
    expect(badge.className, '가로 여백 10px').toContain('px-2.5');
    expect(badge.className, '세로 여백 3px').toContain('py-[3px]');
    expect(badge.className, '구 캡션 계열(14/400)로 되돌리지 말 것').not.toContain('text-caption');
  });

  it('이력이_없으면_아이콘과_함께_안내하고_푸터는_두지_않는다', () => {
    // 목록이 없는데 '최근 20건까지 표시한다' 는 안내만 남으면 무엇에 대한 말인지 알 수 없다.
    render(<DeidentHistoryPanel history={[]} />);

    const empty = screen.getByRole('status');
    expect(within(empty).getByText('비식별 이력이 없습니다.')).toBeInTheDocument();
    expect(empty.querySelector('svg'), '빈 상태 아이콘').not.toBeNull();
    expect(within(empty).getByText('비식별 이력이 없습니다.').className).toContain('text-gray-800');
    expect(
      screen.queryByText(
        '한 항목이 위탁 1회차이며 최신 회차가 위입니다. 최근 20건까지 표시하고, 외부 처리 결과를 받지 못한 회차는 검출 집계와 처리 구간 줄을 표시하지 않습니다.',
      ),
    ).not.toBeInTheDocument();
    // 머리말은 이력 유무와 무관하게 남는다.
    expect(screen.getByText('비식별 이력')).toBeInTheDocument();
  });
});
