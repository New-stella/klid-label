// 라벨링 코어의 라디오 세 자리 — 포털이면 포털 라디오, 관제면 공통 라디오.
//
// 배경 (2026-09-15 포털 개발망 실측): 포털 Host 스타일이 네이티브 라디오를 1px·clip 으로 숨긴다.
//   공통 라디오는 입력 자체가 동그라미라 포털에서 「박스 / 폴리곤」 선택지의 동그라미가 사라졌다.
//   공통 부품은 관제 화면이 함께 쓰므로 고치지 않고, 포털일 때만 포털 라디오로 그린다.
//
// 이 파일이 고정하는 계약(세 자리 각각 두 방향):
//  - 포털 표시(prop)를 받으면 라디오가 포털 라디오다 — 보이는 동그라미가 입력과 별개로 선다.
//  - 받지 않으면(기본값) 공통 라디오 그대로다 — 관제 렌더 무변경.
//  ⚠ jsdom 에는 Host 스타일이 없어 «사라진다»를 재현하지 못한다 — 구조(포털 라디오 표식)로 가른다.
//  ★세 자리를 채널로 가르는 것은 호출부가 넘긴 prop 하나뿐이다(두 번째 채널 판정 축을 두지 않는다).
//
// @design SCREEN-029, SCREEN-005, AC-1108

import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { mockUseLabelAttrs, mockUseLabelAttrValues } = vi.hoisted(() => ({
  mockUseLabelAttrs: vi.fn(),
  mockUseLabelAttrValues: vi.fn(),
}));

vi.mock('@/features/label/hooks/useLabelAttrs', () => ({ useLabelAttrs: mockUseLabelAttrs }));
vi.mock('@/features/label/hooks/useLabelAttrValues', () => ({
  useLabelAttrValues: mockUseLabelAttrValues,
}));

import { AiToolModal } from '@/features/label/components/AiToolModal';
import { AutoTrackPanel } from '@/features/label/components/AutoTrackPanel';
import { ObjectAttributeSection } from '@/features/label/components/ObjectAttributeSection';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useLabelStore } from '@/stores/useLabelStore';

/** 포털 라디오인가 — 보이는 동그라미가 입력의 다음 형제로 선다. */
function isPortalRadio(radio: HTMLElement): boolean {
  return (
    radio.closest('[data-portal-radio]') !== null &&
    radio.nextElementSibling?.hasAttribute('data-portal-radio-dot') === true
  );
}

function expectPortalRadios(names: string[]) {
  for (const name of names) {
    const radio = screen.getByRole('radio', { name });
    expect(isPortalRadio(radio), `${name} 는 포털 라디오여야 한다`).toBe(true);
  }
}

function expectCommonRadios(names: string[]) {
  for (const name of names) {
    const radio = screen.getByRole('radio', { name });
    expect(isPortalRadio(radio), `${name} 는 공통 라디오여야 한다`).toBe(false);
    expect(document.querySelector('[data-portal-radio-dot]')).toBeNull();
  }
}

describe('AI 탐지 팝업 — 형태 라디오', () => {
  it('★포털이면_박스_폴리곤이_포털_라디오다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} runMode="detectOnly" portalMode />,
    );
    expectPortalRadios(['박스', '폴리곤']);
  });

  it('관제는_공통_라디오_그대로다_기본값', () => {
    renderWithProviders(<AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} />);
    expectCommonRadios(['박스', '폴리곤']);
  });

  // 실행 버튼 구성(runMode)과 라디오 모양은 다른 축이다 — 한쪽이 다른 쪽을 끌고 가지 않는다.
  it('실행_버튼_구성만_포털형이어도_포털_표시가_없으면_공통_라디오다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} runMode="detectOnly" />,
    );
    expectCommonRadios(['박스', '폴리곤']);
  });
});

describe('AI 자동 추적 패널 — 적용 방식 라디오', () => {
  beforeEach(() => useLabelStore.getState().reset());
  afterEach(() => useLabelStore.getState().reset());

  const baseProps = {
    srcSn: 300,
    frames: [
      { srcSn: 300, frameNo: 0 },
      { srcSn: 301, frameNo: 1 },
    ],
    nextSrcSns: [301],
    onApply: () => ({ appliedLabels: 0, skippedDuplicates: 0 }),
  };

  it('★포털이면_적용_방식이_포털_라디오다', () => {
    renderWithProviders(<AutoTrackPanel {...baseProps} portal />);
    expect(screen.getByRole('radiogroup', { name: '트랙 결과 적용 방식' })).toBeInTheDocument();
    expectPortalRadios(['검토 후 수락', '자동 반영']);
  });

  it('관제는_공통_라디오_그대로다_기본값', () => {
    renderWithProviders(<AutoTrackPanel {...baseProps} />);
    expect(screen.getByRole('radiogroup', { name: '트랙 결과 적용 방식' })).toBeInTheDocument();
    expectCommonRadios(['검토 후 수락', '자동 반영']);
  });
});

describe('객체 속성 — RADIO 속성', () => {
  beforeEach(() => {
    mockUseLabelAttrs.mockReturnValue({
      data: [
        {
          attrId: 10,
          name: '색상',
          inputType: 'RADIO',
          valuesJson: JSON.stringify(['빨강', '파랑']),
          defaultVal: null,
          mutable: 'Y',
          sortNo: 1,
          useYn: 'Y',
        },
      ],
      isLoading: false,
      isError: false,
    });
    mockUseLabelAttrValues.mockReturnValue({
      valueMap: { 10: '파랑' },
      save: vi.fn(),
      saveError: null,
      isError: false,
    });
  });

  it('★포털이면_속성_선택지가_포털_라디오다', () => {
    renderWithProviders(<ObjectAttributeSection classId={1} serverId={99} portalMode />);
    expectPortalRadios(['빨강', '파랑']);
    expect(screen.getByRole('radio', { name: '파랑' })).toBeChecked();
  });

  it('관제는_공통_라디오_그대로다_기본값', () => {
    render(<ObjectAttributeSection classId={1} serverId={99} />);
    expectCommonRadios(['빨강', '파랑']);
  });
});
