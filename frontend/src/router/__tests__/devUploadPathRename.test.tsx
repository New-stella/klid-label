// dev 업로드 경로 개명 회귀 가드 — 구 이름 `autolabel-test` 가 되살아나는 것을 차단한다.
//
// 무엇이 바뀌었나 (3축 + 설정 키):
//   · 화면 URL: /dev/autolabel-test → /dev/upload
//   · BE API:   /v1/dev/autolabel-test → /v1/dev/upload  (BE 가드는 DevUploadPathRenameGuardTest)
//   · 저장 서브디렉터리: autolabel-test → dev-upload      (BE 소관)
//   · 설정 키: authoring.dev.autolabel-test.max-file-size → authoring.dev.upload.max-file-size
//
// 이 화면이 올린 영상으로 확인하는 것은 오토라벨만이 아니라 적재·비식별·마킹까지의 전 구간이라,
// 이름을 역할(수동 업로드)에 맞췄다. 구 경로는 **별칭·리다이렉트 없이 폐기**됐고 404 가 정상이다 —
// dev 토글로 게이팅되는 내부 화면이라 외부 진입점이 없고, 별칭을 두면 구 이름이 영구히 남는다.
//
// 클래스명·파일명은 개명 대상이 아니다: `DevAutolabelTestPage`·`useAutolabelTest`·
// `AutolabelTestResult` 등 TS 심볼과 파일 경로는 그대로 둔다(후속 작업의 diff 가독성을 위한
// 의도적 범위 제한). 그래서 이 가드는 **케밥 표기 `autolabel-test` 만** 본다 — 카멜 표기 심볼은
// 애초에 매칭되지 않는다.
//
// 스캔 제외 (Critical — 늘리지 말 것):
//   · **이 파일 자신** — 구 이름을 설명해야 하는 유일한 곳이다(자기 자신을 잡는 가드는 못 쓴다).
//   · `docs/**` 는 애초에 스캔 대상이 아니다 — 변경 이력·과거 검증 기록이 구 이름을 언급하는 것이
//     정상이고, `docs/design/**`(설계 키트)은 별도 SYNC 절차로 갱신된다.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';
import type { RouteObject } from 'react-router-dom';

import { registerManualUploadMenu } from '@/components/layout/Lnb';
import { router } from '@/router';

/** 구 이름(케밥 표기). 이 문자열은 `frontend/src` 전역에서 이 파일에만 존재해야 한다. */
const OLD_NAME = 'autolabel-test';
const NEW_SCREEN_PATH = '/dev/upload';

const SRC_ROOT = path.resolve(__dirname, '../..');
/** 이 가드 자신 — 구 이름을 담아야 하므로 스캔에서 제외한다. */
const SELF_FILE = 'devUploadPathRename.test.tsx';
const SCANNED_EXTENSIONS = ['.ts', '.tsx', '.css'];

function collectSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      collectSourceFiles(full, acc);
      continue;
    }
    if (entry === SELF_FILE) continue;
    if (SCANNED_EXTENSIONS.some((ext) => entry.endsWith(ext))) acc.push(full);
  }
  return acc;
}

interface MenuItemLike {
  label: string;
  path: string;
  allow: string[];
}
interface MenuGroupLike {
  group: string;
  items: MenuItemLike[];
}

/** 라우트 트리를 순회해 절대 경로 문자열 목록을 만든다(부모 prefix 결합 포함). */
function collectRoutePaths(routes: RouteObject[], prefix = ''): string[] {
  const found: string[] = [];
  for (const route of routes) {
    const segment = route.path ?? '';
    const joined = segment.startsWith('/')
      ? segment
      : `${prefix}${segment ? `/${segment}` : ''}`;
    const full = joined.replace(/\/{2,}/g, '/');
    if (segment) found.push(full);
    if (route.children) found.push(...collectRoutePaths(route.children, full));
  }
  return found;
}

describe('dev 업로드 경로 개명 (구 이름 회귀 차단)', () => {
  it('구_경로_이름이_프론트엔드_소스에_남아있지_않다', () => {
    const files = collectSourceFiles(SRC_ROOT);
    const offenders = files.filter((file) =>
      readFileSync(file, 'utf-8').includes(OLD_NAME),
    );

    // 스캔이 실제로 돌았음을 먼저 확인한다(0건 스캔이 통과로 보이는 것을 막는다).
    expect(files.length).toBeGreaterThan(300);
    expect(offenders.map((f) => path.relative(SRC_ROOT, f))).toEqual([]);
  });

  it('라우트가_새_URL로_등록되고_구_URL은_등록되지_않는다', () => {
    const paths = collectRoutePaths(router.routes as RouteObject[]);

    // vitest 는 DEV 빌드라 isDevUploadEnabled() 가 true → dev 업로드 라우트가 등록된다.
    expect(paths).toContain(NEW_SCREEN_PATH);
    // 별칭·리다이렉트를 두지 않는다 — 구 URL 은 등록 자체가 없어야 한다(진입 시 404).
    expect(paths.filter((p) => p.includes(OLD_NAME))).toEqual([]);
  });

  it('LNB_관리_메뉴가_새_URL을_가리킨다', () => {
    const menu: MenuGroupLike[] = [{ group: '관리', items: [] }];
    registerManualUploadMenu(menu as never);

    const uploadItem = menu
      .flatMap((group) => group.items)
      .find((item) => item.label === '수동 업로드');

    // 메뉴가 구 URL 을 가리키면 클릭 시 빈 화면(매칭 실패)이 된다 — 라우트와 함께 못 박는다.
    expect(uploadItem?.path).toBe(NEW_SCREEN_PATH);
  });
});
