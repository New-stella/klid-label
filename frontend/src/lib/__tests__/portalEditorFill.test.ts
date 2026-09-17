// 회귀 가드 — 임베드 라벨링 편집기는 **흐름 안에** 서고 화면 아래까지 채운다. [@design SCREEN-029]
//
// 2026-09-17 실화면 실측에서 드러난 결함이다. Host 가 우리 자리에 `contain: layout` 을 걸어
// 두어 `fixed` 가 화면이 아니라 그 자리 안쪽을 덮는데, 우리 판이 흐름 «밖»이라 그 자리는 잴
// 내용이 없어 제 최소 높이로 주저앉는다 — 그 주저앉은 높이를 우리가 다시 덮는 되먹임이다.
// 실측값: 자리 518 · 편집기 518 · 그중 **캔버스 343**. 그 화면으로는 라벨을 그릴 수 없다.
//
// ★같은 화면에서 우리 판을 흐름 «안»으로 돌리자 자리가 **934 까지 따라 자랐다** —
//   Host 가 높이를 묶은 것이 아니라는 뜻이라, 고칠 자리가 우리 쪽임이 확정됐다.

import { describe, expect, it } from 'vitest';

import { PORTAL_EDITOR_MIN_HEIGHT, portalEditorHeight } from '../portalEditorFill';

describe('임베드 편집기 높이', () => {
  it('★제 윗변부터 화면 아래까지 채운다', () => {
    expect(portalEditorHeight(160)).toContain('calc(100vh - 160px)');
  });

  it('★★최소 높이를 밑으로 두지 않는다 — 좁으면 캔버스가 쓸 수 없게 된다', () => {
    // 실측 사고의 높이(518)보다 커야 의미가 있다.
    expect(PORTAL_EDITOR_MIN_HEIGHT).toBeGreaterThan(518);
    expect(portalEditorHeight(160)).toContain(`max(${PORTAL_EDITOR_MIN_HEIGHT}px,`);
  });

  it('음수·소수 오프셋도 성한 글로 만든다 — 잰 값이 그대로 CSS 로 간다', () => {
    expect(portalEditorHeight(-40)).toContain('calc(100vh - 0px)');
    expect(portalEditorHeight(160.4)).toContain('calc(100vh - 160px)');
  });
});
