import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { VideoFilters } from '../components/VideoFilters';

// 이벤트 유형 옵션은 서버 조회(useEventTypes) 결과라 여기서는 관심 밖 — 빈 목록으로 고정한다.
vi.mock('@/features/eventType/hooks', () => ({
  useEventTypes: () => ({ data: [], isLoading: false }),
}));

describe('VideoFilters 상태 옵션', () => {
  it('상태_드롭다운은_BE_배치단계_상태_5종을_모두_노출한다', async () => {
    // given / when
    const user = userEvent.setup();
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={() => {}} />);
    const select = screen.getByLabelText('상태');
    await user.click(select);

    // then: BE LsDataRaw.DATA_STTS_* 와 1:1 (MARKING_READY 누락 시 마킹 대기 영상을 좁힐 수 없다)
    const optionLabels = (await screen.findAllByRole('option')).map((o) => o.textContent);
    expect(optionLabels).toEqual(['전체 상태', '완료', '처리중', '마킹 대기', '대기', '실패']);
    expect(screen.getByRole('option', { name: '마킹 대기' })).toBeInTheDocument();
  });
});

describe('VideoFilters 날짜 상호 제약', () => {
  // 사양 SCREEN-008: '시작일·종료일은 날짜 입력이며 서로 min/max 제약을 건다.'
  // 제약이 없으면 종료일 < 시작일 인 구간으로도 조회가 나가 결과가 항상 0건이 되는데,
  // 사용자에겐 "그런 영상이 없다"로 읽힌다(입력 오류를 화면이 잡아 주지 않음).

  it('시작일_입력은_종료일을_상한으로_갖는다', async () => {
    // given
    const user = userEvent.setup();
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={() => {}} />);

    // when: 종료일을 먼저 정한다
    await user.type(screen.getByLabelText('종료일'), '2026-05-31');

    // then: 시작일이 그날을 넘지 못한다
    expect(screen.getByLabelText('시작일')).toHaveAttribute('max', '2026-05-31');
  });

  it('종료일_입력은_시작일을_하한으로_갖는다', async () => {
    // given
    const user = userEvent.setup();
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={() => {}} />);

    // when: 시작일을 먼저 정한다
    await user.type(screen.getByLabelText('시작일'), '2026-05-01');

    // then: 종료일이 그날보다 앞설 수 없다
    expect(screen.getByLabelText('종료일')).toHaveAttribute('min', '2026-05-01');
  });

  it('반대편이_비어있으면_제약을_걸지_않는다', () => {
    // given / when: 둘 다 비어 있는 초기 상태
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={() => {}} />);

    // then: 빈 문자열을 min/max 로 흘려보내지 않는다(브라우저가 제약으로 해석할 여지를 없앤다)
    expect(screen.getByLabelText('시작일')).not.toHaveAttribute('max');
    expect(screen.getByLabelText('종료일')).not.toHaveAttribute('min');
  });
});

describe('VideoFilters 날짜 입력 배선', () => {
  // 날짜 두 칸을 네이티브 입력으로 직접 재구현하던 것을 사양 컴포넌트(UI-029 DateRangePicker)로
  // 교체했다. 그 교체가 조회 파라미터 형식을 바꾸면 URL·북마크가 깨지므로 여기서 고정한다.

  it('사양_컴포넌트로_배선되어_두_날짜가_한_그룹으로_묶인다', () => {
    // given / when
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={() => {}} />);

    // then: 두 입력이 한 쌍이라는 사실이 접근성 트리에도 드러난다
    const group = screen.getByRole('group');
    expect(group).toContainElement(screen.getByLabelText('시작일'));
    expect(group).toContainElement(screen.getByLabelText('종료일'));
  });

  it('조회하면_날짜가_종전과_같은_yyyy_MM_dd_형식으로_상위에_전달된다', async () => {
    // given
    const user = userEvent.setup();
    const onApply = vi.fn();
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={onApply} />);

    // when
    fireEvent.change(screen.getByLabelText('시작일'), { target: { value: '2026-05-01' } });
    fireEvent.change(screen.getByLabelText('종료일'), { target: { value: '2026-05-31' } });
    await user.click(screen.getByRole('button', { name: '조회' }));

    // then: 전송·URL 직렬화 형식은 교체 이전과 동일해야 한다
    expect(onApply).toHaveBeenCalledWith(
      expect.objectContaining({ from: '2026-05-01', to: '2026-05-31', page: 0 }),
    );
  });

  it('한쪽만_채워도_그쪽만_필터로_전달된다', async () => {
    // given
    const user = userEvent.setup();
    const onApply = vi.fn();
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={onApply} />);

    // when
    fireEvent.change(screen.getByLabelText('시작일'), { target: { value: '2026-05-01' } });
    await user.click(screen.getByRole('button', { name: '조회' }));

    // then: 빈 쪽은 필터 미적용(undefined) — 빈 문자열을 파라미터로 올리지 않는다
    expect(onApply).toHaveBeenCalledWith(
      expect.objectContaining({ from: '2026-05-01', to: undefined }),
    );
  });

  it('초기화하면_날짜_입력이_비워진다', async () => {
    // given
    const user = userEvent.setup();
    const onApply = vi.fn();
    render(<VideoFilters initial={{ page: 0, size: 20 }} onApply={onApply} />);
    fireEvent.change(screen.getByLabelText('시작일'), { target: { value: '2026-05-01' } });
    fireEvent.change(screen.getByLabelText('종료일'), { target: { value: '2026-05-31' } });

    // when
    await user.click(screen.getByRole('button', { name: '초기화' }));

    // then: 화면 입력과 상위 필터가 함께 비워진다
    expect(screen.getByLabelText('시작일')).toHaveValue('');
    expect(screen.getByLabelText('종료일')).toHaveValue('');
    expect(onApply).toHaveBeenCalledWith(
      expect.objectContaining({ page: 0, dataSttsCd: undefined }),
    );
  });
});
