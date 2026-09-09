/**
 * 포털 전용 UI 계층 회귀 가드.
 *
 * 이 계층은 **포털 화면만** 쓴다 — 관제 공통 컴포넌트를 고칠 수 없어서(관제향 불변 구속) 따로
 * 둔 것이다. 여기서 고정하는 것은 **모양이 아니라 계약**이다: 접근 이름·역할·상태 표기·조작
 * 영역처럼 바뀌면 사용자가 손해를 보는 축만 단언한다. 클래스 문자열은 단언하지 않는다 —
 * 그것을 고정하면 리디자인이 곧 빨간불이 되고, 정작 지켜야 할 것은 못 지킨다.
 *
 * @design DS-002
 * @design SCREEN-033
 * @design UI-131
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { PortalAlert } from '@/components/portal/ui/PortalAlert';
import { PortalCard } from '@/components/portal/ui/PortalCard';
import { PortalProgress } from '@/components/portal/ui/PortalProgress';
import { PortalUploadStatusBadge } from '@/components/portal/ui/PortalUploadStatusBadge';
import { UploadDropzone } from '@/components/portal/ui/UploadDropzone';
import { PortalUploadStatus } from '@/features/portal/uploads/types';

describe('PortalCard', () => {
  it('제목을_주면_랜드마크와_제목이_함께_선다', () => {
    render(
      <PortalCard ariaLabel="업로드 자산 목록" title="업로드 자산" count="24건 중 1-20">
        <p>본문</p>
      </PortalCard>,
    );
    expect(screen.getByRole('region', { name: '업로드 자산 목록' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '업로드 자산' })).toBeInTheDocument();
    expect(screen.getByText('24건 중 1-20')).toBeInTheDocument();
  });

  it('제목이_없으면_제목_줄_자체를_그리지_않는다', () => {
    // 빈 제목 줄이 남으면 본문이 까닭 없이 아래로 밀린다.
    render(<PortalCard ariaLabel="빈 카드">본문</PortalCard>);
    expect(screen.queryByRole('heading')).toBeNull();
  });
});

describe('PortalAlert', () => {
  it('제목과_설명을_읽을_수_있다', () => {
    render(<PortalAlert title="여기에 올린 자산은 본인만 볼 수 있습니다" description="설명" />);
    expect(screen.getByText('여기에 올린 자산은 본인만 볼 수 있습니다')).toBeInTheDocument();
    expect(screen.getByText('설명')).toBeInTheDocument();
  });

  /*
   * ★ 늘 서 있는 안내를 `alert` 로 두면 화면에 들어올 때마다 보조기술에 끼어든다.
   *   «방금 일어난 일» 에만 켠다 — 이 구분이 사라지면 안내가 소음이 된다.
   */
  it('기본은_alert_역할을_갖지_않는다', () => {
    render(<PortalAlert title="상시 안내" />);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('live_를_켜야_alert_로_읽힌다', () => {
    render(<PortalAlert live tone="error" title="목록을 불러올 수 없습니다" />);
    expect(screen.getByRole('alert')).toHaveTextContent('목록을 불러올 수 없습니다');
  });
});

describe('PortalProgress', () => {
  it('진행률을_보조기술이_읽을_수_있다', () => {
    render(<PortalProgress label="영상 업로드 진행률" percent={68} />);
    const bar = screen.getByRole('progressbar', { name: '영상 업로드 진행률' });
    expect(bar).toHaveAttribute('aria-valuenow', '68');
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
  });

  /* 범위를 벗어난 값이 그대로 나가면 보조기술이 «168%» 를 읽고 막대도 넘친다. */
  it('범위_밖_값은_0과_100으로_잘린다', () => {
    const { rerender } = render(<PortalProgress label="p" percent={168} />);
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100');
    rerender(<PortalProgress label="p" percent={-4} />);
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0');
  });

  it('보낸_용량과_보조_안내를_함께_보여준다', () => {
    render(
      <PortalProgress
        label="p"
        percent={68}
        caption="2.23 GB / 3.28 GB 보냈습니다"
        note="보낸 만큼은 남아 있습니다"
      />,
    );
    // 퍼센트만 두면 5GB 영상에서 «얼마나 더» 를 가늠할 수 없다.
    expect(screen.getByText('2.23 GB / 3.28 GB 보냈습니다')).toBeInTheDocument();
    expect(screen.getByText('보낸 만큼은 남아 있습니다')).toBeInTheDocument();
  });
});

describe('PortalUploadStatusBadge', () => {
  /*
   * ★★ 이 단언이 이 파일의 핵심이다 — 시안(SD-026)은 이 자리를 「업로드됨」으로 그렸지만 그
   *    시안은 골격 v11 기준이라 마킹 단계(2026-09-02 신설) 이전이다. **골격 v38 이 「마킹 대기」를
   *    못박는다** — 시안을 따라 되돌리면 확정 사양이 뒤집히고, 사용자는 다음에 무엇을 해야 하는지
   *    알 수 없게 된다.
   */
  it('★UPLOADED_는_업로드됨이_아니라_마킹_대기로_표기한다', () => {
    render(<PortalUploadStatusBadge status={PortalUploadStatus.UPLOADED} />);
    expect(screen.getByText('마킹 대기')).toBeInTheDocument();
    expect(screen.queryByText('업로드됨')).toBeNull();
  });

  it('네_상태를_모두_한글로_표기한다', () => {
    const expected: Array<[string, string]> = [
      [PortalUploadStatus.UPLOADED, '마킹 대기'],
      [PortalUploadStatus.PROCESSING, '처리중'],
      [PortalUploadStatus.READY, '준비 완료'],
      [PortalUploadStatus.FAILED, '실패'],
    ];
    for (const [status, label] of expected) {
      const { unmount } = render(<PortalUploadStatusBadge status={status} />);
      expect(screen.getByText(label)).toBeInTheDocument();
      unmount();
    }
  });

  /* 기다려야 하는 상태에는 «다음에 무슨 일이 일어나는지» 를 붙인다 — 배지만 있으면 기다려야
     하는지 잘못된 것인지 알 수 없다. */
  it('withSub_를_켜면_기다리는_상태에_다음_할_일이_붙는다', () => {
    render(<PortalUploadStatusBadge status={PortalUploadStatus.PROCESSING} withSub />);
    expect(screen.getByText('프레임을 뽑는 중입니다')).toBeInTheDocument();
  });

  it('준비_완료에는_덧붙이는_설명이_없다', () => {
    // 할 수 있는 일이 행의 조작으로 이미 드러나 있어 설명이 소음이 된다.
    const { container } = render(
      <PortalUploadStatusBadge status={PortalUploadStatus.READY} withSub />,
    );
    expect(container.textContent).toBe('준비 완료');
  });

  /* 계약에 없는 값이 와도 자리를 비우지 않는다 — 빈 칸은 «상태가 없다» 로 읽힌다. */
  it('알_수_없는_상태값은_값_자체를_보여준다', () => {
    render(<PortalUploadStatusBadge status="WEIRD" withSub />);
    expect(screen.getByText('WEIRD')).toBeInTheDocument();
  });
});

describe('UploadDropzone', () => {
  /*
   * ★ 이 컴포넌트의 존재 이유 — 파일 입력을 **숨기지 않는다.** 숨김 입력 + 가짜 버튼 조합은
   *   실제로 눌리는 영역이 버튼만 남아, 큰 표면을 겨냥한 클릭이 빗나간다. 입력이 표면을 덮고
   *   있어야 눌리는 영역과 보이는 영역이 일치한다.
   *   ⚠ 이 단언이 없으면 «접근성 개선» 을 명분으로 입력을 `sr-only` 로 숨기는 변경이 조용히 통과한다.
   */
  it('★파일_입력을_숨기지_않고_표면에_깔아_둔다', () => {
    const { container } = render(<UploadDropzone label="영상 파일" onFiles={vi.fn()} />);
    const input = container.querySelector('input[type="file"]');
    expect(input).not.toBeNull();
    // 숨김 관용구(`hidden`·`sr-only`)로 빠지지 않았는지 본다.
    expect(input).not.toHaveAttribute('hidden');
    expect(input?.className).not.toContain('sr-only');
    expect(input?.className).toContain('absolute');
  });

  it('라벨과_힌트가_입력에_연결된다', () => {
    render(
      <UploadDropzone label="영상 파일" hint="mp4 · mov · avi / 최대 5GB" onFiles={vi.fn()} />,
    );
    const input = screen.getByLabelText('영상 파일');
    expect(input).toHaveAccessibleDescription('mp4 · mov · avi / 최대 5GB');
  });

  it('고른_파일을_그대로_올려_준다', async () => {
    const onFiles = vi.fn();
    const user = userEvent.setup();
    render(<UploadDropzone label="영상 파일" onFiles={onFiles} />);

    const file = new File(['x'], 'clip.mp4', { type: 'video/mp4' });
    await user.upload(screen.getByLabelText('영상 파일'), file);

    expect(onFiles).toHaveBeenCalledTimes(1);
    expect(onFiles.mock.calls[0][0][0].name).toBe('clip.mp4');
  });

  /*
   * ★ 같은 파일을 다시 골라도 알림이 와야 한다 — 「이어서 올리기」가 바로 이 경로를 탄다.
   *   입력값을 비우지 않으면 두 번째 선택에서 change 가 뜨지 않아 **조용히 아무 일도 안 일어난다.**
   */
  it('★같은_파일을_다시_골라도_다시_알려_준다', async () => {
    const onFiles = vi.fn();
    const user = userEvent.setup();
    render(<UploadDropzone label="영상 파일" onFiles={onFiles} />);
    const input = screen.getByLabelText('영상 파일');
    const file = new File(['x'], 'clip.mp4', { type: 'video/mp4' });

    await user.upload(input, file);
    await user.upload(input, file);

    expect(onFiles).toHaveBeenCalledTimes(2);
  });

  it('비활성이면_입력도_함께_잠긴다', () => {
    render(<UploadDropzone label="영상 파일" disabled onFiles={vi.fn()} />);
    expect(screen.getByLabelText('영상 파일')).toBeDisabled();
  });
});
