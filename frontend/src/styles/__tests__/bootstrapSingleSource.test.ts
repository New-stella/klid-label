// 회귀 가드 — 폰트·전역 CSS 로드 지점은 채널마다 하나뿐이다.
//
// 진입점이 둘(독립 앱 `main.tsx` / Remote `remote/AuthoringRemote.tsx`)이 되면서 CSS import 를
// 양쪽에 복제하면, 한쪽만 갱신될 때 **채널별로 스타일이 갈린다**(포털에서만 폰트가 빠지는 식).
// 그래서 CSS 부트스트랩 모듈 한 곳에만 두고 두 진입점이 그것을 import 한다.
//
// ★ **2026-09-16 — 부트스트랩이 둘로 갈렸다(의도).**
//   ① `styles/bootstrap.ts`   — 두 채널이 함께 쓰는 폰트·전역 CSS
//   ② `styles/portalLook.ts`  — **포털 채널 전용** KRDS 킷 + 부모 포털 토큰
//   ②를 ①에 합칠 수 없다 — 킷 CSS 가 `html{font-size:62.5%}` 를 전역에 깔아 **관제 축 Tailwind
//   치수를 전부 줄인다.** 그래서 포털 셸만 지연 로드로 끌어온다(관제 산출물에서 접힌다).
//   ⇒ 규칙은 「한 곳」이 아니라 **「채널마다 한 곳」** 이 됐고, 이 가드가 그 둘을 못 박는다.
//
// ★★ **부품에 딸린 스킨은 이 규칙의 대상이 아니다.** 부모 포털에서 들여온 부품은
//   `Alert.tsx` 옆에 `Alert.css` 가 붙어 있는 짜임이라 부품이 제 스킨을 직접 부른다.
//   그것은 **전역 CSS 가 아니라 그 부품의 일부**이고, 부품을 안 쓰면 딸려 오지도 않으므로
//   「채널마다 갈린다」는 이 가드의 위험이 성립하지 않는다. 대신 **딴 데 있는 CSS 를 끌어오는
//   것은 여전히 막는다** — 허용은 «자기 옆에 있는 파일»로 한정한다.

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC_ROOT = path.resolve(__dirname, '../..');
const SCANNED_EXTENSIONS = ['.ts', '.tsx'];

/** 공용 CSS 부트스트랩 — 폰트·전역 스타일을 싣는 유일한 파일. */
const BOOTSTRAP_MODULE = path.join(SRC_ROOT, 'styles', 'bootstrap.ts');
/** 포털 채널 전용 부트스트랩 — 킷·토큰을 싣는 유일한 파일. */
const PORTAL_LOOK_MODULE = path.join(SRC_ROOT, 'styles', 'portalLook.ts');

/** side-effect 스타일시트 import 문(`import '<...>.css';`)만 매칭한다. */
const STYLESHEET_IMPORT = /^[ \t]*import\s+['"]([^'"]+\.css)['"]\s*;?[ \t]*$/gm;

function collectSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      collectSourceFiles(full, acc);
      continue;
    }
    if (SCANNED_EXTENSIONS.some((ext) => entry.endsWith(ext))) acc.push(full);
  }
  return acc;
}

/** 그 파일이 부르는 스타일시트 경로들. */
function stylesheetImports(file: string): string[] {
  STYLESHEET_IMPORT.lastIndex = 0;
  const out: string[] = [];
  let m: RegExpExecArray | null;
  const body = readFileSync(file, 'utf-8');
  while ((m = STYLESHEET_IMPORT.exec(body)) !== null) out.push(m[1]);
  return out;
}

/**
 * **자기 옆에 있는 스킨**인가 — `./X.css` 꼴이고 그 파일이 실제로 같은 폴더에 있어야 한다.
 * `../`·`@/`·패키지 경로는 전부 거짓이다(딴 데 있는 CSS 를 끌어오는 것은 계속 막는다).
 */
function isCoLocatedSkin(importer: string, spec: string): boolean {
  if (!spec.startsWith('./')) return false;
  if (spec.includes('/', 2)) return false; // `./sub/x.css` 는 옆이 아니다
  return existsSync(path.join(path.dirname(importer), spec.slice(2)));
}

describe('CSS 부트스트랩 — 채널마다 단일 지점', () => {
  it('전역CSS_를_끌어오는_파일은_두_부트스트랩뿐이고_나머지는_자기_옆_스킨만_부른다', () => {
    // given
    const files = collectSourceFiles(SRC_ROOT);

    // 스캔이 실제로 돌았음을 먼저 확인한다(0건 스캔이 통과로 보이는 것을 막는다).
    expect(files.length).toBeGreaterThan(300);

    // when: 부트스트랩 둘을 뺀 나머지에서 «자기 옆이 아닌» 스타일시트 import 를 모은다
    const offenders = files
      .filter((f) => f !== BOOTSTRAP_MODULE && f !== PORTAL_LOOK_MODULE)
      .flatMap((f) =>
        stylesheetImports(f)
          .filter((spec) => !isCoLocatedSkin(f, spec))
          .map((spec) => `${path.relative(SRC_ROOT, f)} → ${spec}`),
      );

    // then
    expect(offenders).toEqual([]);
  });

  /**
   * ★ 양성 대조 — 위 검사가 실제로 무언가를 잡는지 확인한다. 「0건」이 스캔이 안 돈 결과가
   *   아니라는 것을 이 한 줄이 보장한다(이 저장소가 0건에 네 번 뚫린 뒤 세운 관례).
   */
  it('양성_대조_딴_곳의_CSS_를_끌어오면_걸린다', () => {
    const fake = path.join(SRC_ROOT, 'components', 'portal', 'kit', 'Alert.tsx');
    expect(isCoLocatedSkin(fake, './Alert.css')).toBe(true);
    expect(isCoLocatedSkin(fake, '../../../styles/global.css')).toBe(false);
    expect(isCoLocatedSkin(fake, './없는파일.css')).toBe(false);
  });

  it('공용_부트스트랩이_폰트와_전역CSS를_모두_싣는다', () => {
    // given / when
    const imports = stylesheetImports(BOOTSTRAP_MODULE);
    const body = readFileSync(BOOTSTRAP_MODULE, 'utf-8');

    // then: 폰트 2종 + 전역 스타일 — 한 곳에 모여 있는지가 이 모듈의 존재 이유다.
    expect(imports.length).toBeGreaterThanOrEqual(3);
    expect(body).toContain('pretendard-gov');
    expect(body).toContain('d2coding');
    expect(body).toContain('global.css');
  });

  /**
   * 포털 부트스트랩은 **차례가 곧 규칙**이다 — 킷이 먼저, 그 위에 테마, 마지막이 시각보정이다.
   * 뒤에 온 테마가 이겨야 하고 시각보정이 빠지면 테마의 계산식이 통째로 무효가 된다.
   */
  it('포털_부트스트랩이_킷과_토큰을_부모_포털과_같은_차례로_싣는다', () => {
    const imports = stylesheetImports(PORTAL_LOOK_MODULE);
    expect(imports).toEqual([
      'krds-react/dist/index.css',
      './portal/krds-theme.css',
      './portal/krds-focus.css',
      './portal/portal-base.css',
      './portal/klid-optical.css',
      // 저작도구 화면 짜임 — 토큰을 쓰므로 반드시 테마 뒤다
      './portal/authoring-layout.css',
      './portal/augment-view.css',
      './portal/marking-view.css',
      './portal/labeling-view.css',
    ]);
  });
});
