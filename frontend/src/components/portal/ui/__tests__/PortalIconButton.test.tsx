/**
 * 포털 아이콘 버튼 — **아이콘만 있는 버튼이 무슨 버튼인지 알려 주는가**의 계약 가드.
 *
 * <h3>이 파일이 고정하는 것</h3>
 * <ul>
 *   <li>짧은 설명이 마크업에 실리고 버튼의 접근 설명으로 이어진다 — 눈으로도, 보조기술로도 읽힌다.</li>
 *   <li>접근 이름(대상까지 밝힌 낭독용)과 설명(짧은 말)이 따로 간다.</li>
 *   <li>★잠긴 버튼에도 설명이 남는다 — 잠긴 이유를 알려야 하는 자리가 바로 거기다.</li>
 * </ul>
 *
 * 모양(색·위치)은 단언하지 않는다 — 채널이 산출 시점에 값을 정한다.
 *
 * @design DS-002
 * @design SCREEN-033
 */

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { Download } from 'lucide-react';

import { PortalIconButton } from '../PortalIconButton';

describe('PortalIconButton', () => {
  it('설명이_버튼의_접근_설명으로_이어지고_이름은_따로_간다', () => {
    render(
      <PortalIconButton aria-label="a.mp4 원본 다운로드" tooltip="원본 파일 내려받기">
        <Download aria-hidden />
      </PortalIconButton>,
    );
    const btn = screen.getByRole('button', { name: 'a.mp4 원본 다운로드' });
    expect(btn).toHaveAccessibleDescription('원본 파일 내려받기');
    expect(screen.getByRole('tooltip')).toHaveTextContent('원본 파일 내려받기');
  });

  it('★잠겨도_설명은_남는다', () => {
    render(
      <PortalIconButton aria-label="a.mp4 삭제" tooltip="처리 중에는 지울 수 없습니다" disabled>
        <Download aria-hidden />
      </PortalIconButton>,
    );
    const btn = screen.getByRole('button', { name: 'a.mp4 삭제' });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAccessibleDescription('처리 중에는 지울 수 없습니다');
  });

  it('누르면_onClick_이_불리고_기본_type_은_button_이다', () => {
    const onClick = vi.fn();
    render(
      <PortalIconButton aria-label="x" tooltip="x" onClick={onClick}>
        <Download aria-hidden />
      </PortalIconButton>,
    );
    const btn = screen.getByRole('button', { name: 'x' });
    expect(btn).toHaveAttribute('type', 'button');
    fireEvent.click(btn);
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
