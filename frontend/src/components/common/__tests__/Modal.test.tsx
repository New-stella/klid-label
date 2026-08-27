import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Modal } from '../Modal';

describe('Modal', () => {
  // ── 시안(SCREEN-009 `.lightbox-box`) 정합 — 모서리 축 ────────────────
  // `.lightbox-box { border-radius: var(--radius-lg) }` = 8px 이다. 구 구현은 토큰에 없는
  // 상위 단을 써 Tailwind 기본값(12px)으로 폴백하고 있었다(값이 아니라 출처가 어긋난 상태).
  it('Modal_모서리는_토큰_lg_8px_이다', () => {
    render(
      <Modal open onClose={() => {}} title="제목">
        본문
      </Modal>,
    );
    const cls = screen.getByRole('dialog').className.split(/\s+/);
    expect(cls).toContain('rounded-lg');
    expect(cls.filter((c) => /^rounded-(xl|2xl|3xl)$/.test(c))).toEqual([]);
  });

  it('Modal_open_false_시_렌더하지_않음', () => {
    render(
      <Modal open={false} onClose={() => {}} title="제목">
        본문
      </Modal>,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Modal_ESC_키로_닫기', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="제목">
        본문
      </Modal>,
    );
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Modal_백드롭_클릭_닫기_옵션', () => {
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="제목">
        본문
      </Modal>,
    );
    fireEvent.click(screen.getByTestId('modal-backdrop'));
    expect(onClose).toHaveBeenCalled();
  });

  it('Modal_백드롭_클릭_disabled_시_닫지_않음', () => {
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="제목" closeOnBackdrop={false}>
        본문
      </Modal>,
    );
    fireEvent.click(screen.getByTestId('modal-backdrop'));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('IconButton_44x44_보장', () => {
    // given/when: 모달의 아이콘 전용 닫기 버튼
    render(
      <Modal open onClose={() => {}} title="제목">
        본문
      </Modal>,
    );
    const closeBtn = screen.getByRole('button', { name: '닫기' });
    // then: 아이콘 전용 버튼은 44x44(h-11 w-11) 터치타깃 확보
    expect(closeBtn.className).toMatch(/h-11/);
    expect(closeBtn.className).toMatch(/w-11/);
  });

  // ── UI-004 회귀 가드: showCloseButton ─────────────────────────────────────
  it('Modal_showCloseButton_기본값은_표시다', () => {
    render(
      <Modal open onClose={() => {}} title="제목">
        본문
      </Modal>,
    );
    expect(screen.getByRole('button', { name: '닫기' })).toBeInTheDocument();
  });

  it('Modal_showCloseButton_false_면_X_버튼을_숨기고_ESC_는_유지한다', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <Modal open onClose={onClose} title="제목" showCloseButton={false}>
        본문
      </Modal>,
    );
    expect(screen.queryByRole('button', { name: '닫기' })).not.toBeInTheDocument();
    // 닫기 버튼을 숨겨도 키보드 접근성(ESC)은 깨지지 않는다
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  // ★뷰포트 상한 + 본문 내부 스크롤 계약 (2026-08-08 실측 결함 회귀 가드).
  //   내용이 긴 모달이 뷰포트를 넘어 자라면 상·하단이 잘리는데, 스크롤 컨테이너가 없어
  //   잘린 부분(제목·닫기 버튼 포함)에 도달할 방법이 아예 없었다.
  // ⚠ jsdom 은 레이아웃을 계산하지 않아(모든 rect 가 0) **잘림 자체를 재현하지 못한다** —
  //   여기서는 계약이 걸려 있는지(클래스 지정)만 본다. 실제 좌표 검증은 브라우저 실측 몫이다.
  it('★모달은_뷰포트_높이_상한과_본문_내부_스크롤_계약을_갖는다_jsdom_은_잘림_자체는_재현_못함', () => {
    render(
      <Modal open onClose={() => {}} title="제목">
        <p data-testid="body-content">본문</p>
      </Modal>,
    );
    const dialog = screen.getByRole('dialog');
    // 상한은 고정 px 이 아니라 뷰포트 기준이어야 한다(고정값은 다른 해상도에서 같은 결함을 만든다).
    expect(dialog.className).toMatch(/max-h-\[calc\(100vh/);
    expect(dialog.className).toContain('flex-col');

    const body = screen.getByTestId('body-content').parentElement;
    expect(body?.className).toContain('overflow-y-auto');
    // min-h-0 이 없으면 flex 아이템의 자동 최소 크기 때문에 overflow 가 발동하지 않는다.
    expect(body?.className).toContain('min-h-0');
  });

  it('Modal_포커스_트랩_Tab_순환', async () => {
    const user = userEvent.setup();
    render(
      <Modal open onClose={() => {}} title="제목">
        <input data-testid="first" />
        <input data-testid="last" />
      </Modal>,
    );
    const last = screen.getByTestId('last');
    const closeBtn = screen.getByRole('button', { name: '닫기' });
    last.focus();
    await user.tab();
    // 닫기 버튼이 DOM 첫 번째 focusable이므로 last → 닫기 순환
    expect(document.activeElement).toBe(closeBtn);
  });
  // ── 시안(SCREEN-009 `.lightbox-close`) 정합 — 닫기 버튼 색 축 ──────────
  // `color: var(--n-6)`(#58616a=gray-600) · `:hover { background: var(--n-0)(gray-50);
  // color: var(--n-9)(gray-900) }`. 구 구현은 평상시 gray-400(흰 배경 위 3.08:1)이라
  // 닫기 아이콘이 흐렸다 — gray-600 은 6.30:1 이다.
  it('Modal_닫기_버튼_색은_시안_단계를_따른다', () => {
    render(
      <Modal open onClose={() => {}} title="제목">
        본문
      </Modal>,
    );
    const cls = screen.getByRole('button', { name: '닫기' }).className.split(/\s+/);
    expect(cls).toContain('text-gray-600');
    expect(cls).not.toContain('text-gray-400');
    expect(cls).toContain('hover:text-gray-900');
    // hover 표면도 시안 단계(`--n-0`)로 맞춘다 — 이 저장소의 다수 관례이기도 하다.
    expect(cls).toContain('hover:bg-gray-50');
  });

  // K2 — 시안 `.dlg-desc` = `.t-body-md`(17/400) + 색 `--n-7`(gray-700).
  //   구 구현은 `text-sub`(14/400) + gray-500 이라 DS-001 Do's 「본문 17px 이상」에 미달했다.
  //   이 자리는 다이얼로그의 **본문 문단**이지 보조 캡션이 아니다.
  it('Modal_설명_타이포는_시안_본문_단계를_따른다', () => {
    render(
      <Modal open onClose={() => {}} title="제목" description="설명 문단">
        본문
      </Modal>,
    );
    const cls = screen.getByText('설명 문단').className.split(/\s+/);
    expect(cls, '시안 .t-body-md = 17px/400').toContain('text-body-md');
    expect(cls, '구 text-sub(14px)로 되돌리지 말 것').not.toContain('text-sub');
    expect(cls, '시안 색 --n-7').toContain('text-gray-700');
    expect(cls, '구 gray-500 로 되돌리지 말 것').not.toContain('text-gray-500');
  });
});
