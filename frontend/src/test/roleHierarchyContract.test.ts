// 역할 계층 계약 가드 — 서버 선언 ↔ 프론트 사본. [@design ROLE-004] [@design ADR-055] [@design AC-125]
//
// 배경: 인가의 1차 원천은 서버다. 그런데 서버의 역할 상속 선언은 브라우저로 내려오지 않는다
//   — 토큰이 실어 오는 것은 역할 **값 하나**뿐이다. 그래서 화면이 「관리자에게 검수자 버튼을
//   그릴지」를 스스로 정하려면 포함 관계를 프론트에도 적어 둘 수밖에 없고, 그 사본은 없앨 수
//   있는 중복이 아니라 채널의 한계에서 나온다.
//
//   사본을 지울 수 없으면 **갈리는 순간 드러나게** 하는 것이 다음으로 좋은 수다. 이 가드가 그
//   역할을 한다 — 서버가 역할을 늘리거나 상속을 바꾸면 여기가 깨져 프론트 사본이 낡았다는
//   사실이 즉시 보인다. 없으면 두 축은 조용히 갈리고, 그때는 화면과 서버 중 어느 쪽이 사양인지
//   판단할 근거가 남지 않는다.
//
// ⚠ 파일이 없거나 추출이 0건이면 통과시키지 않는다 — 모노레포라 항상 존재해야 하고,
//   graceful skip 은 가드를 조용히 무력화한다(같은 저장소의 헬스 컴포넌트 계약 가드와 동일 규약).

import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { Role } from '@/lib/api/types';
import { ROLE_INHERITS } from '@/lib/authz';

const REPO_ROOT = path.resolve(__dirname, '../../..');
const SERVER_ROLE = path.join(
  REPO_ROOT,
  'backend/src/main/java/kr/co/cudo/authoring/common/security/Role.java',
);

function serverSource(): string {
  return readFileSync(SERVER_ROLE, 'utf-8');
}

/**
 * 서버 역할 enum 의 상수 이름을 소스에서 그대로 뽑는다.
 *
 * enum 본문(`public enum Role { ... ;`)의 첫 세미콜론까지가 상수 선언 구간이다. 그 뒤로는
 * 필드·메서드가 오므로 함께 읽으면 엉뚱한 식별자가 섞인다.
 */
function serverRoles(): string[] {
  const src = serverSource();
  const body = src.slice(src.indexOf('public enum Role {'));
  const constants = body.slice(0, body.indexOf(';'));
  return [...constants.matchAll(/^\s{4}([A-Z][A-Z_]*)\s*[,;]?\s*$/gm)].map((m) => m[1]);
}

/**
 * 서버 상속 선언(`Map.of(부모, Set.of(자식…))`)을 소스에서 그대로 뽑는다.
 *
 * 한 단계짜리 선언 여러 쌍을 견디도록 `키, Set.of(값…)` 조각 단위로 훑는다.
 */
function serverInherits(): Record<string, string[]> {
  const src = serverSource();
  // ★javadoc 이 같은 이름을 여러 번 참조하므로 **대입문**에 앵커를 건다. 이름만 찾으면 주석
  //   조각을 읽고 0쌍이 나와, 아래 대조가 「서버에 상속이 없다」로 조용히 뒤집힌다.
  const at = src.search(/INHERITS\s*=/);
  expect(at, '서버 소스에서 상속 선언(INHERITS = ...)을 찾지 못했다').toBeGreaterThan(-1);
  const decl = src.slice(at);
  const stmt = decl.slice(0, decl.indexOf(';'));
  const out: Record<string, string[]> = {};
  for (const m of stmt.matchAll(/([A-Z][A-Z_]*)\s*,\s*Set\.of\(([^)]*)\)/g)) {
    const children = m[2]
      .split(',')
      .map((c) => c.trim())
      .filter((c) => c.length > 0);
    out[m[1]] = children.sort();
  }
  return out;
}

/** 프론트 상속 선언을 서버와 같은 형태(정렬된 배열 맵)로 맞춘다. */
function frontendInherits(): Record<string, string[]> {
  return Object.fromEntries(
    Object.entries(ROLE_INHERITS).map(([k, v]) => [k, [...(v ?? [])].sort()]),
  );
}

describe('역할 계층 계약 (서버 선언 ↔ 프론트 사본)', () => {
  it('서버_소스에서_역할과_상속을_실제로_읽어낸다', () => {
    // 추출이 0건이면 아래 대조가 빈 집합끼리 비교라 공허하게 통과한다 — 먼저 고정한다.
    expect(serverRoles().length).toBeGreaterThan(0);
    expect(Object.keys(serverInherits()).length).toBeGreaterThan(0);
  });

  it('프론트가_서버_역할_전부를_알고_있고_없는_역할을_지어내지도_않는다', () => {
    // 누락 = 그 역할로 들어온 사용자가 화면에서 미부여로 떨어진다(권한이 통째로 사라진다).
    // 잉여 = 서버에 없는 값을 화면이 인정한다(죽은 분기이자 잘못된 기대).
    expect([...Object.values(Role)].sort()).toEqual([...serverRoles()].sort());
  });

  it('상속_선언이_서버와_한_글자도_다르지_않다', () => {
    // 프론트가 더 넓으면 서버가 막을 자리에 버튼을 그리고(누르면 거부되는 화면),
    // 더 좁으면 서버는 허락하는데 화면이 기능을 감춘다(사용자에겐 고장으로 보인다).
    expect(frontendInherits()).toEqual(serverInherits());
  });

  it('서버_상속에_작업자와_포털_회원이_들어있지_않다', () => {
    // 이 가드는 프론트 사본이 아니라 **서버 선언 자체**를 본다 — 두 사본이 함께 넓어지면
    // 위의 일치 단언은 그대로 통과하기 때문이다(같이 틀리면 안 잡힌다).
    const children = Object.values(serverInherits()).flat();
    expect(children).not.toContain('WORKER');
    expect(children).not.toContain('PORTAL_USER');
  });
});
