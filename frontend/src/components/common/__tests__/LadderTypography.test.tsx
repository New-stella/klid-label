// 공통 컴포넌트가 **원시 t-shirt 스케일이 아니라 DS-001 ladder step** 으로 글자 크기를 적는지 고정한다.
//
// ★ 왜 필요한가
//   `tailwind.config.js` 는 원시 스케일(`text-xs`/`text-sm` …)도 ladder 값으로 재매핑해 두었다.
//   그래서 원시 스케일을 써도 **크기는 맞게 나오고**, 어긋난 사실이 화면에서 드러나지 않는다.
//   문제는 원시 스케일이 <b>weight 를 싣지 않는다</b>는 것 — `label`(600)/`button`(500)/
//   `title-*`(600~700) 같은 step 의 굵기가 통째로 빠지고, 그 자리를 호출부의 `font-*` 가
//   제각각 메운다. 즉 "크기는 우연히 맞고 의미·굵기는 흩어진" 상태가 된다.
//   이 파일은 공통 컴포넌트에서 그 회귀(원시 스케일로의 복귀)를 소스 레벨에서 막는다.
//
// ⚠ mutation 확인 절차: 아래 아무 컴포넌트에서 ladder 토큰을 원시 스케일로 되돌리면
//   (예: Button `text-label` → `text-xs`) 첫 번째 테스트가 FAIL 해야 한다.

import fs from 'node:fs';
import path from 'node:path';

import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Button } from '@/components/common/Button';
import { KpiCard } from '@/components/common/KpiCard';
// 배지 크기 계약은 StageBadge 로 확인한다 — EventTypeBadge 는 같은 SIZE_CLASSES 규약을 쓰지만
// 라벨 맵 조회(useQuery) 때문에 QueryClientProvider 가 필요해 크기 단언에 잡음이 섞인다.
import { StageBadge } from '@/components/common/StageBadge';

const COMMON_DIR = path.resolve(__dirname, '..');
const LAYOUT_DIR = path.resolve(__dirname, '../../layout');

/** tailwind-merge 기본 검증기가 인식하는 원시 t-shirt 스케일 — 공통 컴포넌트에서 사용 금지. */
const RAW_SCALE = ['xs', 'sm', 'base', 'lg', 'xl', '2xl', '3xl', '4xl', '5xl'];
const RAW_RE = new RegExp(`(?<![\\w-])text-(${RAW_SCALE.join('|')})(?![\\w-])`);

function sourceFiles(dir: string): string[] {
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.tsx'))
    .map((f) => path.join(dir, f));
}

/**
 * 주석을 걷어낸 코드 줄만 돌려준다 — 폐기된 구 동작을 설명하는 주석에 원시 스케일 이름이
 * 그대로 등장하므로(예: "구 `text-sm` 과 동일 크기"), 걷어내지 않으면 <b>주석이 곧 위반</b>이 된다.
 * 블록 주석은 줄 수를 보존하며 지워 줄번호가 어긋나지 않게 한다.
 * `//` 는 `https://` 를 오인하지 않도록 앞에 `:` 가 없을 때만 주석으로 본다.
 */
function codeLines(file: string): Array<{ no: number; text: string }> {
  const stripped = fs
    .readFileSync(file, 'utf-8')
    .replace(/\{?\/\*[\s\S]*?\*\/\}?/g, (m) => '\n'.repeat((m.match(/\n/g) ?? []).length))
    .replace(/(?<!:)\/\/.*$/gm, '');
  return stripped.split('\n').map((text, i) => ({ no: i + 1, text }));
}

describe('공통 컴포넌트 타이포 — DS-001 ladder step 배정', () => {
  it('★공통_컴포넌트와_레이아웃에_원시_t셔츠_스케일이_남아있지_않다', () => {
    const offenders: string[] = [];
    for (const dir of [COMMON_DIR, LAYOUT_DIR]) {
      for (const file of sourceFiles(dir)) {
        // 포털 레이아웃은 이번 배정 범위 밖(사용자 확정) — 별도 판단.
        if (path.basename(file).startsWith('Portal')) continue;
        for (const { no, text } of codeLines(file)) {
          if (RAW_RE.test(text)) {
            offenders.push(`${path.relative(COMMON_DIR, file)}:${no}  ${text.trim().slice(0, 90)}`);
          }
        }
      }
    }
    expect(
      offenders,
      `원시 스케일은 weight 를 싣지 않아 ladder step 의 굵기가 유실된다. ladder 토큰으로 적을 것:\n${offenders.join('\n')}`,
    ).toEqual([]);
  });

  it('Button_의_size_변형이_ladder_step으로_배정된다', () => {
    // md/lg = `btn-label`(= ladder `button` 17px) · sm = `label`(14px, 밀집 UI 예외).
    // sm 을 `button` 으로 올리면 테이블 액션·툴바가 무너지므로 의도적으로 다른 step 이다.
    const cls = (size: 'sm' | 'md' | 'lg') =>
      (render(<Button size={size}>확인</Button>).container.querySelector('button')?.className ?? '')
        .split(/\s+/)
        .filter(Boolean);

    expect(cls('sm')).toContain('text-label');
    expect(cls('md')).toContain('text-btn-label');
    expect(cls('lg')).toContain('text-btn-label');

    // 세 변형 모두 크기 클래스를 정확히 하나만 갖는다(twMerge 삼킴·중복 방지).
    for (const size of ['sm', 'md', 'lg'] as const) {
      const sizes = cls(size).filter((c) => /^text-(label|btn-label|button)$/.test(c));
      expect(sizes, `Button(size=${size}) 의 크기 토큰`).toHaveLength(1);
    }
  });

  it('KpiCard_는_지표명=label_지표값=display-sm_으로_위계를_세운다', () => {
    const { container } = render(<KpiCard label="완료 작업" value={1234} />);
    const ps = Array.from(container.querySelectorAll('p'));
    const labelEl = ps.find((p) => p.textContent === '완료 작업');
    const valueEl = ps.find((p) => p.textContent?.includes('1,234'));

    // 구 동작(`text-sm` 17px)에서 ladder `label`(14px)로 내려 값과의 대비를 만든 지점이다.
    expect(labelEl?.className).toContain('text-label');
    expect(labelEl?.className).not.toMatch(RAW_RE);
    expect(valueEl?.className).toContain('text-display-sm');
  });

  it('배지는_label_축이고_md_변형만_크기를_보존한다', () => {
    const sm = render(<StageBadge stage="YOLO" />).container.querySelector('span');
    const md = render(<StageBadge stage="YOLO" size="md" />).container.querySelector('span');

    expect(sm?.className).toContain('text-label');
    // md 는 ladder 에 "큰 label" step 이 없어 크기 보존(`body-md` 17px)을 택했다.
    // 판정이 확정되면 이 단언을 `text-label` 로 좁힌다(약화가 아니라 강화 방향).
    expect(md?.className).toContain('text-body-md');
    expect(md?.className).not.toMatch(RAW_RE);
  });
});
