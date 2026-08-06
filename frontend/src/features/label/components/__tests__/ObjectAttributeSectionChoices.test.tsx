// ObjectAttributeSection — RADIO / CHECKBOX 선택지 렌더·커밋 특성화 테스트.
//
// 배경: 두 분기는 그동안 테스트가 없어(SELECT 만 ObjectAttributePanel.attrValues.test 로 커버)
// 마크업을 공통 컴포넌트(Radio/Checkbox)로 교체할 때 안전망이 없었다. 정의(valuesJson)의
// 선택지 <b>순서·값</b>과 커밋 payload(RADIO=원문 / CHECKBOX=JSON 배열 직렬화)를 고정한다.

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { mockUseLabelAttrs, mockUseLabelAttrValues, mockSave } = vi.hoisted(() => ({
  mockUseLabelAttrs: vi.fn(),
  mockUseLabelAttrValues: vi.fn(),
  mockSave: vi.fn(),
}));

vi.mock('../../hooks/useLabelAttrs', () => ({ useLabelAttrs: mockUseLabelAttrs }));
vi.mock('../../hooks/useLabelAttrValues', () => ({ useLabelAttrValues: mockUseLabelAttrValues }));
vi.mock('../../hooks/useBlockNotice', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../hooks/useBlockNotice')>()),
  useBlockNotice: () => vi.fn(),
}));

import { ObjectAttributeSection } from '../ObjectAttributeSection';

const CHOICES = ['빨강', '파랑', '초록'];

function mockDef(inputType: 'RADIO' | 'CHECKBOX', value?: string) {
  mockUseLabelAttrs.mockReturnValue({
    data: [
      {
        attrId: 10,
        name: '색상',
        inputType,
        valuesJson: JSON.stringify(CHOICES),
        defaultVal: value ?? null,
        mutable: 'Y',
        sortNo: 1,
        useYn: 'Y',
      },
    ],
    isLoading: false,
    isError: false,
  });
  mockUseLabelAttrValues.mockReturnValue({
    valueMap: {},
    save: mockSave,
    saveError: null,
    isError: false,
  });
}

describe('ObjectAttributeSection 선택형 속성', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('RADIO_정의_순서대로_선택지가_렌더되고_라디오_그룹으로_묶인다', () => {
    mockDef('RADIO');
    render(<ObjectAttributeSection classId={1} serverId={99} />);

    const radios = screen.getAllByRole('radio');
    expect(radios.map((r) => (r as HTMLInputElement).value)).toEqual(CHOICES);
    // 같은 name 으로 묶여야 단일 선택이 성립한다.
    expect(new Set(radios.map((r) => (r as HTMLInputElement).name))).toEqual(
      new Set(['attr-10']),
    );
    // legend(속성명)로 그룹 라벨이 유지된다.
    expect(screen.getByRole('group', { name: '색상' })).toBeInTheDocument();
  });

  it('RADIO_선택하면_선택값_원문이_그대로_커밋된다', async () => {
    const user = userEvent.setup();
    mockDef('RADIO');
    render(<ObjectAttributeSection classId={1} serverId={99} />);

    await user.click(screen.getByRole('radio', { name: '파랑' }));

    expect(mockSave).toHaveBeenCalledWith([{ attrId: 10, value: '파랑' }]);
  });

  it('CHECKBOX_정의_순서대로_렌더되고_선택값은_JSON_배열로_직렬화된다', async () => {
    const user = userEvent.setup();
    mockDef('CHECKBOX');
    render(<ObjectAttributeSection classId={1} serverId={99} />);

    const boxes = screen.getAllByRole('checkbox');
    expect(boxes.map((b) => (b as HTMLInputElement).value)).toEqual(CHOICES);

    await user.click(screen.getByRole('checkbox', { name: '초록' }));

    expect(mockSave).toHaveBeenCalledWith([{ attrId: 10, value: JSON.stringify(['초록']) }]);
  });

  it('차단_상태면_선택지가_비활성이고_커밋되지_않는다', async () => {
    const user = userEvent.setup();
    mockDef('CHECKBOX');
    render(<ObjectAttributeSection classId={1} serverId={99} editBlocked />);

    const box = screen.getByRole('checkbox', { name: '빨강' });
    expect(box).toBeDisabled();

    await user.click(box);
    expect(mockSave).not.toHaveBeenCalled();
  });
});
