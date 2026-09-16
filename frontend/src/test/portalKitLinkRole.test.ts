// 회귀 가드 — 킷 버튼을 **링크로 세울 때는 역할도 링크로** 되돌린다.
//
// ## 무엇을 지키나 (2026-09-16 실측)
//
// 부모 포털에서 들여온 KRDS 킷 `Button` 은 다형 부품이라 `as={Link}` 로 앵커가 될 수 있는데,
// **`role` 기본값이 `"button"` 이라 앵커로 세워도 그 값을 그대로 덮어쓴다**(킷 원본 실측 —
// `({ as: t, …, role: o = "button", … })` 가 렌더 요소에 그대로 실린다).
//
// 그대로 두면 **주소·가운데 클릭·새 탭은 살아 있는데 보조기술에는 「버튼」으로 읽힌다.**
// 링크로 남긴 뜻이 절반만 남는 셈이고, 눈으로는 아무 차이가 없어 **리뷰로는 잡히지 않는다.**
// 실제로 이 저장소에서 두 화면이 같은 자리에 걸렸다.
//
// ## 규칙
//
// 포털 채널 소스에서 `as={Link}` 로 세운 킷 `Button` 은 `role="link"` 를 함께 준다.
//
// ⚠ 이 가드는 **소스 문자열**을 본다 — 변수로 조립한 props 는 보지 못한다. 그런 형태가
//   생기면 이 검사가 조용해지므로, 그때는 호출부가 아니라 **감싸는 부품**을 만들어 한 곳에서
//   역할을 박는 쪽이 옳다.

import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC = path.resolve(__dirname, '..');

/** 포털 채널이 소유한 트리 — 킷을 쓰는 자리는 전부 여기다. */
const PORTAL_DIRS = ['pages/portal', 'features/portal', 'components/portal'];

/** `<Button ... >` 여는 태그 한 덩어리(여닫는 `>` 까지). */
const BUTTON_TAG = /<Button\b[^>]*>/g;

function portalSources(): string[] {
  const out: string[] = [];
  for (const dir of PORTAL_DIRS) {
    const base = path.join(SRC, dir);
    for (const f of readdirSync(base, { recursive: true, encoding: 'utf-8' })) {
      if (!/\.tsx$/.test(f) || /\.test\.tsx$/.test(f)) continue;
      out.push(path.join(dir, f));
    }
  }
  return out.sort();
}

/** 그 파일 안에서 «링크로 세웠는데 역할을 안 준» 킷 버튼 태그들. */
function offendingTags(src: string): string[] {
  return (src.match(BUTTON_TAG) ?? [])
    .filter((tag) => /\bas=\{Link\}/.test(tag))
    .filter((tag) => !/\brole="link"/.test(tag));
}

describe('포털 킷 버튼 — 링크로 세우면 역할도 링크다', () => {
  it('스캔이_실제로_돌았다_포털_소스를_모은다', () => {
    // 0건 스캔이 통과로 보이는 것을 막는다.
    expect(portalSources().length).toBeGreaterThan(20);
  });

  it('as_Link_로_세운_킷_버튼은_전부_role_link_를_갖는다', () => {
    const offenders = portalSources().flatMap((rel) => {
      const src = readFileSync(path.join(SRC, rel), 'utf-8');
      return offendingTags(src).map((tag) => `${rel}: ${tag.replace(/\s+/g, ' ').slice(0, 120)}`);
    });

    expect(
      offenders,
      '킷 Button 을 `as={Link}` 로 세웠는데 `role="link"` 가 없다 — 킷 기본값 `role="button"` 이 ' +
        '그대로 실려 보조기술에 **버튼으로 읽힌다**(주소는 살아 있어 눈으로는 안 보인다):\n' +
        offenders.join('\n'),
    ).toEqual([]);
  });

  /**
   * ★ 양성 대조 — 위 검사가 실제로 무는지 확인한다. 「0건」이 판정식이 깨진 결과가 아님을
   *   이 한 줄이 보장한다(이 저장소가 0건에 네 번 뚫린 뒤 세운 관례).
   */
  it('양성_대조_역할이_빠진_태그를_잡는다', () => {
    expect(offendingTags('<Button as={Link} to="/x" size="small">가기</Button>')).toHaveLength(1);
    expect(
      offendingTags('<Button as={Link} to="/x" role="link" size="small">가기</Button>'),
    ).toHaveLength(0);
    // 앵커로 세우지 않은 보통 버튼은 대상이 아니다.
    expect(offendingTags('<Button size="small" onClick={go}>가기</Button>')).toHaveLength(0);
  });
});
