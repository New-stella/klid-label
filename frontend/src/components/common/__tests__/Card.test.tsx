import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '../Card';

/**
 * Card 조합형 전환(UI-011)의 회귀 가드.
 *
 * Card 루트는 title/description/actions/footer 콘텐츠 prop을 갖지 않고 하위 컴포넌트
 * 조합으로만 구성된다. 이 스펙에서 코드로 검증 가능한 3가지 동적 동작을 고정한다:
 * ① CardAction 존재 시 CardHeader가 1fr/auto 2열 그리드로 전환
 * ② CardFooter 존재 시 Card 루트의 하단 패딩이 0으로 줄어듦
 * ③ CardTitle은 시맨틱 헤딩이 아닌 일반 텍스트 요소
 */
describe('Card 조합형', () => {
  it('children_을_그대로_렌더한다_콘텐츠_prop_없음', () => {
    render(
      <Card data-testid="card-root">
        <CardContent>
          <p>본문</p>
        </CardContent>
      </Card>,
    );
    expect(screen.getByText('본문')).toBeInTheDocument();
    expect(screen.getByTestId('card-root')).toHaveAttribute('data-slot', 'card');
  });

  it('CardHeader_는_기본적으로_1열_grid_이다', () => {
    const { container } = render(
      <Card>
        <CardHeader>
          <CardTitle>제목</CardTitle>
        </CardHeader>
      </Card>,
    );
    const header = container.querySelector('[data-slot="card-header"]');
    expect(header?.className).toMatch(/grid-cols-1/);
    expect(header?.className).not.toMatch(/grid-cols-\[1fr_auto\]/);
  });

  it('CardAction_이_있으면_CardHeader_가_1fr_auto_2열_그리드로_전환된다', () => {
    const { container } = render(
      <Card>
        <CardHeader>
          <CardTitle>외부 연동 상태</CardTitle>
          <CardAction>
            <button type="button">새로고침</button>
          </CardAction>
        </CardHeader>
      </Card>,
    );
    const header = container.querySelector('[data-slot="card-header"]');
    expect(header?.className).toMatch(/grid-cols-\[1fr_auto\]/);

    const action = container.querySelector('[data-slot="card-action"]');
    expect(action?.className).toMatch(/col-start-2/);
    expect(action?.className).toMatch(/row-start-1/);
  });

  it('CardFooter_가_없으면_Card_루트에_pb-0_이_없다', () => {
    const { container } = render(
      <Card>
        <CardContent>본문</CardContent>
      </Card>,
    );
    const root = container.querySelector('[data-slot="card"]');
    expect(root?.className).not.toMatch(/\bpb-0\b/);
  });

  it('CardFooter_가_있으면_Card_루트의_하단_패딩이_0으로_줄어든다', () => {
    const { container } = render(
      <Card>
        <CardContent>본문</CardContent>
        <CardFooter>푸터</CardFooter>
      </Card>,
    );
    const root = container.querySelector('[data-slot="card"]');
    expect(root?.className).toMatch(/\bpb-0\b/);

    const footer = container.querySelector('[data-slot="card-footer"]');
    expect(footer).toHaveTextContent('푸터');
    expect(footer?.className).toMatch(/border-t/);
    expect(footer?.className).toMatch(/bg-gray-50/);
  });

  it('CardFooter_가_언마운트되면_Card_루트의_pb-0_이_해제된다', () => {
    const { container, rerender } = render(
      <Card>
        <CardContent>본문</CardContent>
        <CardFooter>푸터</CardFooter>
      </Card>,
    );
    expect(container.querySelector('[data-slot="card"]')?.className).toMatch(/\bpb-0\b/);

    rerender(
      <Card>
        <CardContent>본문</CardContent>
      </Card>,
    );
    expect(container.querySelector('[data-slot="card"]')?.className).not.toMatch(/\bpb-0\b/);
  });

  it('CardTitle_은_시맨틱_헤딩이_아니라_일반_텍스트_요소이다', () => {
    render(
      <Card>
        <CardHeader>
          <CardTitle>제목</CardTitle>
          <CardDescription>설명</CardDescription>
        </CardHeader>
      </Card>,
    );
    const title = screen.getByText('제목');
    expect(['H1', 'H2', 'H3', 'H4', 'H5', 'H6']).not.toContain(title.tagName);
    // aria-labelledby 로 컨테이너와 연결하지도 않는다(접근성 노트대로).
    expect(screen.getByText('제목').closest('[data-slot="card"]')).not.toHaveAttribute(
      'aria-labelledby',
    );
  });

  it('size_sm_이면_CardTitle_폰트가_default_보다_작아지고_여백_토큰도_줄어든다', () => {
    const { container: def } = render(
      <Card>
        <CardHeader>
          <CardTitle>제목</CardTitle>
        </CardHeader>
        <CardContent>본문</CardContent>
      </Card>,
    );
    const { container: sm } = render(
      <Card size="sm">
        <CardHeader>
          <CardTitle>제목</CardTitle>
        </CardHeader>
        <CardContent>본문</CardContent>
      </Card>,
    );

    const defTitle = def.querySelector('[data-slot="card-title"]');
    const smTitle = sm.querySelector('[data-slot="card-title"]');
    expect(defTitle?.className).toMatch(/text-title-sm/);
    expect(smTitle?.className).toMatch(/text-body-sm/);
    expect(smTitle?.className).not.toMatch(/text-title-sm/);

    const defRoot = def.querySelector('[data-slot="card"]');
    const smRoot = sm.querySelector('[data-slot="card"]');
    expect(defRoot?.className).toMatch(/py-6/);
    expect(smRoot?.className).toMatch(/py-3/);

    const defContent = def.querySelector('[data-slot="card-content"]');
    const smContent = sm.querySelector('[data-slot="card-content"]');
    expect(defContent?.className).toMatch(/px-6/);
    expect(smContent?.className).toMatch(/px-3/);
  });

  it('헤더_없이_CardContent만_두는_자유_조합이_가능하다', () => {
    render(
      <Card>
        <CardContent>헤더 없는 카드</CardContent>
      </Card>,
    );
    expect(screen.getByText('헤더 없는 카드')).toBeInTheDocument();
  });

  it('호출부_className_은_병합되어_유지된다', () => {
    const { container } = render(<Card className="w-full max-w-md" data-testid="c" />);
    expect(container.querySelector('[data-slot="card"]')?.className).toMatch(/max-w-md/);
  });
  // ── 시안(SCREEN-009 `.card`) 정합 — 여백 축 ──────────────────────────
  // 시안은 헤더 `padding: 24 24 0` · 본문 `padding: 24` 라, 카드 안쪽 여백이 상하좌우 24 로
  // 균일하고 헤더↔본문 사이도 24 다. 구 구현은 좌우만 24 이고 상하·사이는 16 이어서
  // 가로세로 여백이 어긋나 있었다.
  it('Card_기본_여백은_상하_24_헤더_본문_간격_24_이다', () => {
    const { container } = render(
      <Card>
        <CardHeader>
          <CardTitle>제목</CardTitle>
        </CardHeader>
        <CardContent>본문</CardContent>
      </Card>,
    );
    const cls = container.querySelector('[data-slot="card"]')?.className.split(/\s+/) ?? [];
    expect(cls).toContain('py-6');
    expect(cls).toContain('gap-6');
    expect(cls).not.toContain('py-4');
    expect(cls).not.toContain('gap-4');
  });

  // sm 변형은 시안에 대응 규칙이 없어 **의도적으로 그대로 둔다** — 기본 여백을 올리면서
  // 딸려 바뀌지 않았는지 함께 고정한다(이 단언이 sm 분기의 동결 장치다).
  it('Card_sm_변형의_여백은_이번_정합에서_바뀌지_않는다', () => {
    const { container } = render(
      <Card size="sm">
        <CardContent>본문</CardContent>
      </Card>,
    );
    const cls = container.querySelector('[data-slot="card"]')?.className.split(/\s+/) ?? [];
    expect(cls).toContain('py-3');
    expect(cls).toContain('gap-3');
  });
});
