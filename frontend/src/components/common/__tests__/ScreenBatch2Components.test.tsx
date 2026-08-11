import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { AttachmentList } from '../AttachmentList';
import { Avatar } from '../Avatar';
import { Badge } from '../Badge';
import { FieldCounter } from '../FieldCounter';
import { PresetLabelOverflowChip } from '../PresetLabelOverflowChip';
import { PresetLabelPicker } from '../PresetLabelPicker';
import { RoleBadge } from '../RoleBadge';

/**
 * 화면 설계 2차 배치(SCREEN-024/026/030/031/036/037)를 위해 신설한 공용 컴포넌트
 * 7종(UI-109~115)의 계약 가드.
 *
 * 화면 구현 전 단계라 레이아웃까지 검증하지 않는다 — 여기서 못박는 것은 카탈로그가 명시한
 * **접근성·의미 계약**이다(색상 단독 구분 금지, 아이콘 전용 버튼의 라벨, 읽기 전용성 등).
 * 그 계약이 화면 구현 중 조용히 사라지는 것을 막는 것이 목적이다.
 */

describe('Avatar (UI-109)', () => {
  it('이니셜_1자만_렌더한다', () => {
    render(<Avatar initial="홍길동" />);
    expect(screen.getByText('홍')).toBeInTheDocument();
    expect(screen.queryByText('홍길동')).not.toBeInTheDocument();
  });

  it('스크린리더에_노출되지_않는다_이름텍스트가_식별을_담당한다', () => {
    // 이름 텍스트를 항상 병기하는 것이 전제이므로(UI-109) 아바타가 이니셜을 또 읽으면 중복이다.
    const { container } = render(<Avatar initial="김" />);
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('RoleBadge (UI-110)', () => {
  it('역할별_한글_라벨을_표시한다', () => {
    const { unmount: u1 } = render(<RoleBadge role="REVIEWER" />);
    expect(screen.getByText('검수자')).toBeInTheDocument();
    u1();

    const { unmount: u2 } = render(<RoleBadge role="WORKER" />);
    expect(screen.getByText('작업자')).toBeInTheDocument();
    u2();

    render(<RoleBadge role="PORTAL_USER" />);
    expect(screen.getByText('포털 사용자')).toBeInTheDocument();
  });

  it('role_이_null_이면_미배정으로_표시한다', () => {
    render(<RoleBadge role={null} />);
    expect(screen.getByText('미배정')).toBeInTheDocument();
  });

  it('미배정만_경고_아이콘을_병기한다_색상단독_구분_금지', () => {
    const { container, unmount } = render(<RoleBadge role={null} />);
    expect(container.querySelector('svg')).toBeInTheDocument();
    unmount();

    const { container: reviewer } = render(<RoleBadge role="REVIEWER" />);
    expect(reviewer.querySelector('svg')).toBeNull();
  });

  it('작업자는_secondary_700_텍스트를_쓴다_3단_스케일로는_생성되지_않던_클래스다', () => {
    render(<RoleBadge role="WORKER" />);
    expect(screen.getByText('작업자').className).toMatch(/text-secondary-700/);
  });
});

describe('Badge (UI-111)', () => {
  it('variant별_라벨을_항상_표시한다', () => {
    const { unmount } = render(<Badge variant="pinned" label="중요" />);
    expect(screen.getByText('중요')).toBeInTheDocument();
    unmount();

    render(<Badge variant="success" label="발행" />);
    expect(screen.getByText('발행')).toBeInTheDocument();
  });

  it('pinned_는_핀_아이콘을_기본_병기한다', () => {
    const { container, unmount } = render(<Badge variant="pinned" label="중요" />);
    expect(container.querySelector('svg')).toBeInTheDocument();
    unmount();

    const { container: neutral } = render(<Badge variant="neutral" label="작성중" />);
    expect(neutral.querySelector('svg')).toBeNull();
  });
});

describe('AttachmentList (UI-112)', () => {
  const items = [
    { id: 1, name: '라벨링가이드.pdf', size: '340.0 KB' },
    { id: 2, name: '체크리스트.xlsx', size: '12.5 KB' },
  ];

  it('행마다_파일명_크기_액션버튼을_렌더한다', () => {
    render(<AttachmentList items={items} action="download" onAction={() => {}} />);
    expect(screen.getByText('라벨링가이드.pdf')).toBeInTheDocument();
    expect(screen.getByText('340.0 KB')).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(2);
  });

  it('아이콘_전용_버튼의_aria_label_에_파일명이_들어간다', () => {
    // 목록에 버튼이 여러 개라 "다운로드"만으로는 어느 파일인지 구분되지 않는다.
    render(<AttachmentList items={items} action="download" onAction={() => {}} />);
    expect(screen.getByRole('button', { name: '라벨링가이드.pdf 다운로드' })).toBeInTheDocument();
  });

  it('action_delete_면_버튼_라벨이_삭제다', () => {
    render(<AttachmentList items={items} action="delete" onAction={() => {}} />);
    expect(screen.getByRole('button', { name: '체크리스트.xlsx 삭제' })).toBeInTheDocument();
  });

  it('downloading_행은_aria_busy_disabled_로_재클릭을_막는다', async () => {
    const onAction = vi.fn();
    render(
      <AttachmentList
        items={[{ id: 1, name: 'a.pdf', size: '1.0 KB', status: 'downloading' }]}
        action="download"
        onAction={onAction}
      />,
    );
    const button = screen.getByRole('button', { name: 'a.pdf 다운로드' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(button).catch(() => undefined);
    expect(onAction).not.toHaveBeenCalled();
  });

  it('첨부_0건이면_목록대신_안내를_렌더한다_배타적_분기', () => {
    render(<AttachmentList items={[]} action="download" />);
    expect(screen.getByText('첨부파일이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('파일명이_잘려도_전체이름을_알_수_있게_title_을_단다', () => {
    render(<AttachmentList items={items} action="download" onAction={() => {}} />);
    expect(screen.getByText('라벨링가이드.pdf')).toHaveAttribute('title', '라벨링가이드.pdf');
  });

  it('onAction_클릭이_해당_항목을_전달한다', async () => {
    const onAction = vi.fn();
    render(<AttachmentList items={items} action="download" onAction={onAction} />);
    await userEvent.click(screen.getByRole('button', { name: '체크리스트.xlsx 다운로드' }));
    expect(onAction).toHaveBeenCalledWith(items[1], 1);
  });
});

describe('PresetLabelOverflowChip (UI-113)', () => {
  it('초과_개수를_플러스N_으로_표시한다', () => {
    render(<PresetLabelOverflowChip count={2} />);
    expect(screen.getByText('+2')).toBeInTheDocument();
  });

  it('0건이면_렌더하지_않는다_접힌게_없다는_사실을_잘못_알리지_않는다', () => {
    const { container } = render(<PresetLabelOverflowChip count={0} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('PresetLabelPicker (UI-114)', () => {
  const items = [
    { labelId: 1, labelName: '사람', shapeType: 'BBOX' as const, checked: true },
    { labelId: 2, labelName: '차량', shapeType: 'POLYGON' as const, checked: false },
    { labelId: 3, labelName: '미연결라벨', shapeType: null, checked: false },
  ];

  it('행마다_체크박스_라벨명_형태배지를_렌더한다', () => {
    render(<PresetLabelPicker items={items} />);
    expect(screen.getAllByRole('checkbox')).toHaveLength(3);
    expect(screen.getByText('사람')).toBeInTheDocument();
    expect(screen.getByText('BBOX')).toBeInTheDocument();
    expect(screen.getByText('POLYGON')).toBeInTheDocument();
  });

  it('선택_개수를_표시한다', () => {
    render(<PresetLabelPicker items={items} />);
    expect(screen.getByText('1')).toBeInTheDocument();
  });

  it('onChange_가_숫자_labelId_를_전달한다', async () => {
    // 저장 요청이 labelIds: number[] 라 문자열로 다루면 서버가 거부한다.
    // 선택 상태를 실제로 끌어올리는 호출부 형태로 검증한다(Checkbox 테스트와 동일 컨벤션) —
    // props 를 고정한 채 클릭하면 컨트롤드 컴포넌트가 되돌아가며 act 경고가 난다.
    const onChange = vi.fn();
    function Harness() {
      const [checkedIds, setCheckedIds] = useState<number[]>([1]);
      return (
        <PresetLabelPicker
          items={items.map((i) => ({ ...i, checked: checkedIds.includes(i.labelId) }))}
          onChange={(labelId, checked) => {
            onChange(labelId, checked);
            setCheckedIds((prev) =>
              checked ? [...prev, labelId] : prev.filter((id) => id !== labelId),
            );
          }}
        />
      );
    }
    render(<Harness />);
    await userEvent.click(screen.getAllByRole('checkbox')[1]);
    expect(onChange).toHaveBeenCalledWith(2, true);
    expect(typeof onChange.mock.calls[0][0]).toBe('number');
    expect(screen.getAllByRole('checkbox')[1]).toHaveAttribute('aria-checked', 'true');
  });

  it('체크된_항목이_checked_상태로_반영된다', () => {
    render(<PresetLabelPicker items={items} />);
    expect(screen.getAllByRole('checkbox')[0]).toHaveAttribute('aria-checked', 'true');
    expect(screen.getAllByRole('checkbox')[1]).toHaveAttribute('aria-checked', 'false');
  });

  it('형태가_없으면_배지를_그리지_않는다_미연결', () => {
    render(<PresetLabelPicker items={[items[2]]} />);
    expect(screen.getByText('미연결라벨')).toBeInTheDocument();
    expect(screen.queryByText('BBOX')).toBeNull();
  });

  it('목록_0건이면_안내를_렌더한다', () => {
    render(<PresetLabelPicker items={[]} />);
    expect(screen.getByText('선택할 수 있는 라벨이 없습니다.')).toBeInTheDocument();
  });
});

describe('FieldCounter (UI-115)', () => {
  it('현재_최대_글자수를_표시한다', () => {
    render(<FieldCounter current={18} max={200} />);
    expect(screen.getByText('18/200')).toBeInTheDocument();
  });

  it('스크린리더에_노출되지_않는다_타이핑마다_읽히면_방해가_된다', () => {
    const { container } = render(<FieldCounter current={0} max={200} />);
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true');
  });

  it('한도_초과시_경고색으로_바꾸지_않는다_사양_미정_임계값을_지어내지_않는다', () => {
    render(<FieldCounter current={201} max={200} />);
    expect(screen.getByText('201/200').className).not.toMatch(/warning|danger/);
  });
});
