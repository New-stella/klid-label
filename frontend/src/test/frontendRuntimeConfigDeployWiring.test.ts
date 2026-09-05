// 프론트엔드 <런타임 설정> 배포 배선 가드.
//
// 배경: Vite 는 VITE_* 를 빌드 시점에 정적 치환한다. 그래서 환경마다 다른 값(상위 로그인
// 주소·개발용 화면 토글)이 산출물에 굳어, 폐쇄망 반입에서는 배포 가능한 산출물 자체를 만들 수
// 없었다(예시 주소가 구워진 dist 가 실제로 반입 대상이었다). 이제 값의 정본은 대상 서버의
// `/etc/klid/frontend.env` 이고, 설치·반영 스크립트가 거기서 `klid-config.js` 를 생성한다.
//
// 이 파일이 고정하는 것은 <그 사슬이 실제로 이어져 있는가>다. 사슬은 서로 다른 언어·파일에
// 흩어져 있어(HTML · 셸 · TS) 런타임 테스트로는 어느 한 칸이 끊긴 것을 알 수 없다.
//   ① index.html 이 번들보다 먼저 설정 스크립트를 읽는가
//   ② 셸 생성기와 TS 해석기가 같은 전역 이름·같은 키 목록을 쓰는가
//   ③ 생성기가 allowlist 밖 값(=비밀값)을 내보내지 않는가   ← 회귀하면 즉시 유출이다
//   ④ 필수 값이 비면 생성을 거부하는가 (빌드에서 옮겨 온 fail-closed)
//   ⑤ 설치가 생성기를 부르고 현장값을 덮어쓰지 않는가
//   ⑥ 웹 서버가 생성물을 캐시하지 않는가 (캐시되면 "고친 줄 알고 넘어간다")
//   ⑦ 값이 비었을 때 <어디서> 설치가 종결되는가 (표식 단계와 종결 단계가 갈려 있다)
//
// ⚠⚠ <이 시험은 `deploy/onprem/` 아래 셸 스크립트와 템플릿을 여럿 읽는다.> 그래서 프론트 코드를 한 줄도
//    건드리지 않은 배포 스크립트 편집이 이 파일을 red 로 만들 수 있다. 실제로 그런 일이
//    있었다(온프렘 편집이 33줄을 끼워 넣자 거리 기반 정규식이 깨졌다). 여기가 red 면 먼저
//    `git diff deploy/onprem/scripts/install/` 부터 볼 것 — 원인이 프론트에 없을 수 있다.
//
// ⚠ 그리고 <셸 본문에 거리 기반 정규식(`A[\s\S]{0,N}B`)을 쓰지 마라.> 주석 한 문단만 늘어도
//   깨지고, 더 나쁘게는 <무관한 토큰>을 붙잡아 공허하게 통과한다. 아래 `⑦` 절의 실패가 정확히
//   그것이었다 — 변수 정의 한 줄과 93행 떨어진 무관한 `die` 를 이어 붙여 놓고, 정작 그 시험이
//   지킨다고 적은 동작은 이미 다른 파일로 옮겨간 뒤였다. 구조(if 덩어리)로 앵커할 것.

import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { RUNTIME_CONFIG_GLOBAL, RUNTIME_CONFIG_KEYS } from '@/lib/runtimeConfig';

const REPO_ROOT = path.resolve(__dirname, '../../..');
const RENDERER = path.join(REPO_ROOT, 'deploy/onprem/scripts/install/render-frontend-config.sh');
const TEMPLATE = path.join(REPO_ROOT, 'deploy/onprem/config/frontend/frontend.env.template');

const read = (rel: string): string => readFileSync(path.join(REPO_ROOT, rel), 'utf-8');

/** 유효한 필수 값 — 이것만으로 생성이 성공해야 한다. */
const VALID_REQUIRED = [
  'VITE_CONTROL_LOGIN_URL=https://control.site.internal/login',
  'VITE_PORTAL_LOGIN_URL=https://portal.site.internal/login',
].join('\n');

interface RenderResult {
  status: number;
  stdout: string;
  stderr: string;
  output: string | null;
  outPath: string;
}

/** 생성기를 <실제로 실행>한다 — grep 정적 검사로는 셸 로직 오류가 드러나지 않는다. */
function render(configBody: string, opts: { seedOutput?: string } = {}): RenderResult {
  const dir = mkdtempSync(path.join(tmpdir(), 'klid-fe-config-'));
  const etc = path.join(dir, 'etc');
  const web = path.join(dir, 'web');
  mkdirSync(etc);
  mkdirSync(web);
  writeFileSync(path.join(etc, 'frontend.env'), configBody, 'utf-8');
  const outPath = path.join(web, 'klid-config.js');
  if (opts.seedOutput !== undefined) writeFileSync(outPath, opts.seedOutput, 'utf-8');

  const res = spawnSync('bash', [RENDERER], {
    env: {
      PATH: process.env.PATH ?? '',
      KLID_ETC: etc,
      KLID_PREFIX: path.join(dir, 'opt'),
      WEB_ROOT: web,
    },
    encoding: 'utf-8',
  });

  return {
    status: res.status ?? -1,
    stdout: res.stdout ?? '',
    stderr: res.stderr ?? '',
    output: existsSync(outPath) ? readFileSync(outPath, 'utf-8') : null,
    outPath,
  };
}

/** 생성물을 실제로 평가해 앱이 보게 될 값을 얻는다(형식 가정 없이 결과로 판정). */
function evalGenerated(js: string): Record<string, string> {
  const fn = new Function(`const window = {}; ${js}; return window;`) as () => Record<
    string,
    Record<string, string>
  >;
  return fn()[RUNTIME_CONFIG_GLOBAL] ?? {};
}

describe('① 브라우저가 설정을 먼저 읽는다', () => {
  it('index_html이_번들보다_먼저_설정_스크립트를_로드한다', () => {
    const html = read('frontend/index.html');
    const configIdx = html.indexOf('/klid-config.js');
    const bundleIdx = html.indexOf('/src/main.tsx');
    expect(configIdx, 'index.html 이 klid-config.js 를 로드하지 않는다').toBeGreaterThanOrEqual(0);
    expect(bundleIdx).toBeGreaterThanOrEqual(0);
    expect(configIdx, '설정이 번들보다 뒤에 오면 모듈 평가 시점에 값이 없다').toBeLessThan(
      bundleIdx,
    );
  });

  it('설정_스크립트는_module이_아니다', () => {
    // `type="module"` 은 defer 라 번들과 같은 큐에 들어간다 — 순서 보장이 깨진다.
    const html = read('frontend/index.html');
    const tag = html.slice(html.indexOf('<script src="/klid-config.js"'));
    expect(tag.slice(0, 80)).not.toContain('type="module"');
  });

  it('기본_판은_전역만_심고_값은_비어_있다', () => {
    // 생성물이 없는 경로(`npm run dev`·컨테이너)에서 빌드 시점 값으로 떨어져야 한다.
    const js = read('frontend/public/klid-config.js');
    expect(js).toContain(RUNTIME_CONFIG_GLOBAL);
    expect(Object.keys(evalGenerated(js))).toHaveLength(0);
  });
});

describe('② 셸 생성기와 TS 해석기가 같은 계약을 쓴다', () => {
  it('전역_이름이_같다', () => {
    // 셸은 TS 상수를 import 할 수 없다 — 어긋나면 값이 조용히 무시된다.
    expect(readFileSync(RENDERER, 'utf-8')).toContain(`GLOBAL_NAME='${RUNTIME_CONFIG_GLOBAL}'`);
  });

  it('allowlist가_양쪽에서_동일하다', () => {
    // 생성기에만 있는 키 = 앱이 무시하는 값 / 앱에만 있는 키 = 절대 채워지지 않는 값.
    const script = readFileSync(RENDERER, 'utf-8');
    const block = script.slice(script.indexOf('ALLOWED_KEYS=('));
    const shellKeys = block
      .slice(0, block.indexOf(')'))
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter((l) => /^VITE_[A-Z_]+$/.test(l));

    expect([...shellKeys].sort()).toEqual([...RUNTIME_CONFIG_KEYS].sort());
  });
});

describe('③ 비밀값은 생성물에 나오지 않는다 (allowlist)', () => {
  it('정본에_비밀값을_넣어도_생성물에_나타나지_않는다', () => {
    // 회귀하면 즉시 유출이다 — 이 파일은 브라우저로 그대로 내려간다.
    const secret = 'SUPER-SECRET-VALUE-42';
    const res = render(
      [
        VALID_REQUIRED,
        `JWT_SECRET=${secret}`,
        `CONTROL_DB_PASSWORD=${secret}`,
        `WEBHOOK_SIGNING_KEY=${secret}`,
      ].join('\n'),
    );

    expect(res.status).toBe(0);
    expect(res.output, '생성물이 없다').not.toBeNull();
    expect(res.output).not.toContain(secret);
    expect(res.output).not.toContain('JWT_SECRET');
    expect(Object.keys(evalGenerated(res.output as string))).not.toContain('JWT_SECRET');
  });

  it('무시한_키는_이름을_로그로_알린다_값은_찍지_않는다', () => {
    // 조용히 버리면 "왜 값이 안 나오나"를 아무도 모른다. 반대로 값을 찍으면 그게 유출이다.
    const secret = 'SUPER-SECRET-VALUE-42';
    const res = render([VALID_REQUIRED, `JWT_SECRET=${secret}`].join('\n'));
    const log = res.stdout + res.stderr;

    expect(log).toContain('JWT_SECRET');
    expect(log).not.toContain(secret);
  });
});

describe('④ 필수 값이 비면 생성을 거부한다 (빌드에서 옮겨 온 fail-closed)', () => {
  it('로그인_주소가_없으면_비정상_종료한다', () => {
    const res = render('VITE_API_BASE_URL=/api/v1\n');
    expect(res.status).not.toBe(0);
  });

  it('한쪽만_있어도_거부한다', () => {
    expect(render('VITE_CONTROL_LOGIN_URL=https://control.site.internal/login\n').status).not.toBe(
      0,
    );
    expect(render('VITE_PORTAL_LOGIN_URL=https://portal.site.internal/login\n').status).not.toBe(0);
  });

  it('스킴이_없으면_거부한다', () => {
    // 스킴이 빠지면 브라우저가 상대경로로 해석해 저작도구 자기 자신으로 되돌아온다.
    const res = render(
      [
        'VITE_CONTROL_LOGIN_URL=control.site.internal/login',
        'VITE_PORTAL_LOGIN_URL=https://portal.site.internal/login',
      ].join('\n'),
    );
    expect(res.status).not.toBe(0);
  });

  it('거부할_때_기존_생성물을_망가뜨리지_않는다', () => {
    // 운영 중 재생성이 실패했다고 <이미 돌던 화면>이 죽으면 안 된다.
    const previous = 'window.__KLID_RUNTIME_CONFIG__ = {"VITE_API_BASE_URL":"/api/v1"};\n';
    const res = render('VITE_API_BASE_URL=/api/v1\n', { seedOutput: previous });
    expect(res.status).not.toBe(0);
    expect(res.output).toBe(previous);
  });
});

describe('⑤ 정상 생성', () => {
  it('정본_값이_그대로_실린다', () => {
    const res = render(
      [
        VALID_REQUIRED,
        'VITE_API_BASE_URL=/api/v1',
        'VITE_TOKEN_INGRESS=localStorage',
        'VITE_DEV_LOGIN_ENABLED=false',
        'VITE_DEV_UPLOAD_ENABLED=true',
      ].join('\n'),
    );

    expect(res.status).toBe(0);
    expect(evalGenerated(res.output as string)).toEqual({
      VITE_API_BASE_URL: '/api/v1',
      VITE_TOKEN_INGRESS: 'localStorage',
      VITE_CONTROL_LOGIN_URL: 'https://control.site.internal/login',
      VITE_PORTAL_LOGIN_URL: 'https://portal.site.internal/login',
      VITE_DEV_LOGIN_ENABLED: 'false',
      VITE_DEV_UPLOAD_ENABLED: 'true',
    });
  });

  it('따옴표로_감싼_값과_공백을_해석한다', () => {
    const res = render(
      [
        '  VITE_CONTROL_LOGIN_URL = "https://control.site.internal/login"  ',
        "VITE_PORTAL_LOGIN_URL='https://portal.site.internal/login'",
      ].join('\n'),
    );
    expect(res.status).toBe(0);
    expect(evalGenerated(res.output as string).VITE_CONTROL_LOGIN_URL).toBe(
      'https://control.site.internal/login',
    );
  });

  it('값에_따옴표가_섞여도_생성물이_유효한_JS_다', () => {
    // 깨지면 설정이 통째로 무시되고, 그 실패는 화면에 아무 신호도 남기지 않는다.
    const res = render(
      [VALID_REQUIRED, 'VITE_API_BASE_URL=/api/"v1"\\x'].join('\n'),
    );
    expect(res.status).toBe(0);
    const check = spawnSync('node', ['--check', res.outPath], { encoding: 'utf-8' });
    expect(check.status, `문법 오류: ${check.stderr}`).toBe(0);
    expect(evalGenerated(res.output as string).VITE_API_BASE_URL).toBe('/api/"v1"\\x');
  });

  it('주석은_값으로_읽지_않는다', () => {
    const res = render(
      ['# VITE_CONTROL_LOGIN_URL=https://commented.example/login', VALID_REQUIRED].join('\n'),
    );
    expect(res.status).toBe(0);
    expect(evalGenerated(res.output as string).VITE_CONTROL_LOGIN_URL).toBe(
      'https://control.site.internal/login',
    );
  });

  it('생성물_머리말이_직접_수정_금지와_비밀값_금지를_적는다', () => {
    // 이 두 문장이 없으면 다음 사람이 생성물을 직접 고치거나 여기에 토큰을 넣는다.
    const res = render(VALID_REQUIRED);
    expect(res.output).toMatch(/직접 고치지 마라|덮인다/);
    expect(res.output).toContain('비밀값');
  });
});

describe('⑥ 템플릿과 설치 배선', () => {
  it('템플릿이_필수_키_자리표시자를_갖는다', () => {
    const tpl = readFileSync(TEMPLATE, 'utf-8');
    expect(tpl).toContain('VITE_CONTROL_LOGIN_URL=@CONTROL_LOGIN_URL@');
    expect(tpl).toContain('VITE_PORTAL_LOGIN_URL=@PORTAL_LOGIN_URL@');
  });

  it('템플릿이_비밀값_금지를_명시한다', () => {
    // 이 파일의 내용은 브라우저로 내려간다 — 안 적으면 다음 사람이 여기에 토큰을 넣는다.
    expect(readFileSync(TEMPLATE, 'utf-8')).toMatch(/비밀값(을)? 넣/);
  });

  it('템플릿_기본값이_URL_쿼리_토큰_채널을_열지_않는다', () => {
    // 토큰 인계 기본값이 새 지점(런타임 정본)에서 되살아나면 안 된다 —
    // 판정 축은 `tokenIngressDeployWiring.test.ts` 와 같다(URL 채널을 여는가).
    const line = readFileSync(TEMPLATE, 'utf-8')
      .split(/\r?\n/)
      .find((l) => /^\s*VITE_TOKEN_INGRESS\s*=/.test(l));
    expect(line).toBeDefined();
    const value = (line as string).split('=')[1].trim();
    expect(['url', 'both', 'all']).not.toContain(value);
  });

  it('설치가_생성기를_설치하고_호출한다', () => {
    // ⚠ 「생성 실패가 여기서 설치를 멈춘다」는 <이 단계의 계약이 아니다> — 아래 ⑦ 절 참조.
    //   그 단언은 2026-08-30 결정 이후 공허했고, 거리 기반 정규식이라 무관한 `die` 를 잡고 있었다.
    const install = read('deploy/onprem/scripts/install/14-install-frontend.sh');
    expect(install).toContain('render-frontend-config.sh');
    expect(install, '생성기를 실제로 실행해야 한다').toContain('"${FE_CONFIG_RENDERER}"');
  });

  it('설치가_기존_정본을_덮어쓰지_않는다', () => {
    // 재설치가 현장값을 날리면 이 구조 전체가 무의미해진다.
    const install = read('deploy/onprem/scripts/install/14-install-frontend.sh');
    expect(install).toMatch(/if \[\[ -f "\$\{FE_CONFIG_DST\}" \]\]; then/);
  });

  it('반영_명령이_대상_서버에_설치된다', () => {
    // 매체가 없어도 값 변경이 한 줄로 끝나야 한다.
    const install = read('deploy/onprem/scripts/install/14-install-frontend.sh');
    expect(install, '설치 루트의 bin 에 놓지 않는다').toMatch(/BIN_DIR="\$\{KLID_PREFIX\}\/bin"/);
    expect(install).toContain('klid-frontend-config');
    expect(install, 'install 로 실행권한과 함께 배치해야 한다').toMatch(
      /install -m 0755 .*render-frontend-config\.sh/,
    );
  });
});

describe('⑦ 웹 서버가 생성물을 캐시하지 않는다', () => {
  it('httpd_템플릿이_no_store를_건다', () => {
    const conf = read('deploy/onprem/config/frontend/httpd-klid.conf.template');
    expect(conf).toContain('klid-config');
    expect(conf).toMatch(/Cache-Control\s+"no-store/);
  });

  it('nginx_설정_2종이_no_store를_건다', () => {
    for (const rel of ['deploy/onprem/config/frontend/nginx.conf.template', 'frontend/nginx.conf']) {
      const conf = read(rel);
      expect(conf, `${rel}: 위치 블록 없음`).toContain('location = /klid-config.js');
      expect(conf, `${rel}: no-store 없음`).toMatch(/Cache-Control "no-store/);
    }
  });
});

describe('⑧ 빌드는 환경 무관이다', () => {
  const BUILD_SCRIPTS = [
    'deploy/onprem/scripts/package/20-build-frontend.sh',
    'deploy/onprem/scripts/install/build-from-source.sh',
  ] as const;

  it('빌드_스크립트가_로그인_URL을_더_이상_요구하지_않는다', () => {
    // 요구하면 빌드머신이 고객 환경의 실주소를 알아야 하고, 그러면 매체를 만들 수 없다.
    for (const rel of BUILD_SCRIPTS) {
      const lines = read(rel)
        .split(/\r?\n/)
        .filter((l) => !l.trim().startsWith('#'));
      expect(
        lines.some((l) => l.includes('require_upstream_login_urls')),
        `${rel}: 구 빌드 가드가 되살아났다 (fail-closed 는 설치 시점으로 옮겼다)`,
      ).toBe(false);
    }
  });

  it('Dockerfile에도_빌드_중단_가드가_없다', () => {
    const lines = read('frontend/Dockerfile')
      .split(/\r?\n/)
      .filter((l) => !l.trim().startsWith('#'));
    expect(
      lines.some((l) => l.startsWith('RUN ') && l.includes('VITE_CONTROL_LOGIN_URL')),
      'docker 빌드가 환경값을 요구하면 이미지도 환경 종속이 된다',
    ).toBe(false);
  });

  it('구_가드_함수가_공용_라이브러리에서_사라졌다', () => {
    const lines = read('deploy/onprem/scripts/lib/common.sh')
      .split(/\r?\n/)
      .filter((l) => !l.trim().startsWith('#'));
    expect(lines.some((l) => l.includes('require_upstream_login_urls'))).toBe(false);
  });

  it('빌드가_주입값을_산출물_옆에_기록한다', () => {
    // 로그로만 찍으면 매체를 받은 사람이 dist 만 보고는 무엇으로 구워졌는지 알 수 없다.
    const script = read('deploy/onprem/scripts/package/20-build-frontend.sh');
    expect(script).toContain('BUILD-INFO.txt');
    for (const key of RUNTIME_CONFIG_KEYS) {
      expect(script, `BUILD-INFO 에 ${key} 미기록`).toContain(`${key}=%s`);
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// ⑦ 값이 비었을 때 어디서 종결되는가 — 표식 단계와 종결 단계는 갈려 있다
//
// ## 왜 갈렸나 (2026-08-30 결정, 구속 — 「완화」로 오인하지 말 것)
// 이 fail-closed 는 원래 <빌드>에 있었고, 빌드를 환경 무관으로 바꾸면서 <설치>로 옮겼다.
// 그런데 처음 옮긴 자리가 `14-install-frontend.sh` 안, 그것도 **자기가 방금 놓은 빈 템플릿을
// 즉시 읽는** 자리였다. 그래서 첫 설치는 <구조적으로 반드시> 거기서 죽었고, `set -e` 라
// httpd 설치·SELinux 문맥·DB 초기화가 통째로 실행되지 않아 현장에서는 "설치는 돌았는데 웹
// 서버가 없다"로 보였다.
//
// ⇒ 되돌리기 어렵고 값과 무관한 것을 먼저 끝내고, 값 누락은 <맨 마지막 20 단계>가 막는다.
//   · 3-1 단계(`14-install-frontend.sh`) = **표식을 세우고 넘긴다** (`FE_CONFIG_PENDING=1` + warn)
//   · 20 단계(`20-verify-frontend-config.sh`) = **설치를 실패로 종결한다** (`die`)
//
// ★ **방어가 사라진 것이 아니라 자리가 옮겨간 것이다.** 3-1 에 `die` 가 없다는 사실만 보고
//   「완화됐다」고 판단해 되돌리면, 위의 「첫 설치가 반드시 죽는」 상태로 정확히 회귀한다.
//
// ★ 세 축을 **함께** 본다 — 하나만 보면 조용히 무너진다:
//   ① 3-1 이 표식을 세우는가  ② 20 이 종결하는가  ③ **20 이 설치 순서에 실제로 등록돼 있는가**
//   ③ 이 빠지면 종결 코드가 파일에 실재하는데 한 번도 실행되지 않는다(죽은 게이트).
// ─────────────────────────────────────────────────────────────────────────────

/** `if … ; then … else … fi` 한 덩어리를 <구조로> 잘라 낸다. */
function ifBlockContaining(
  source: string,
  needle: string,
): { thenPart: string; elsePart: string; tail: string } {
  const lines = source.split(/\r?\n/);
  const hit = lines.findIndex((l) => l.includes(needle));
  if (hit === -1) throw new Error(`앵커를 찾지 못했다: ${needle}`);

  // `if` 는 여러 줄로 이어질 수 있다(줄 끝 `\`) — 앵커에서 뒤로 걸어 블록 머리를 찾는다.
  let head = hit;
  while (head >= 0 && !/^\s*if\s/.test(lines[head])) head -= 1;
  if (head < 0) throw new Error(`if 블록 머리를 찾지 못했다: ${needle}`);

  let depth = 0;
  let elseAt = -1;
  let end = -1;
  for (let i = head; i < lines.length; i += 1) {
    if (/^\s*if\s/.test(lines[i])) depth += 1;
    if (/^\s*fi\b/.test(lines[i])) {
      depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
    if (depth === 1 && /^\s*else\s*$/.test(lines[i]) && elseAt === -1) elseAt = i;
  }
  if (end === -1) throw new Error(`fi 를 찾지 못했다: ${needle}`);

  const thenEnd = elseAt === -1 ? end : elseAt;
  return {
    thenPart: lines.slice(head, thenEnd).join('\n'),
    elsePart: elseAt === -1 ? '' : lines.slice(elseAt + 1, end).join('\n'),
    tail: lines.slice(end + 1).join('\n'),
  };
}

describe('⑦ 설정 미충족의 종결 지점 — 표식(3-1)과 종결(20)', () => {
  const INSTALL = 'deploy/onprem/scripts/install/14-install-frontend.sh';
  const VERIFY = 'deploy/onprem/scripts/install/20-verify-frontend-config.sh';

  // 스캐너 자신에 대한 가드가 먼저다 — 잘라내기가 조용히 눈이 멀면 아래 단언이 전부 공짜다.
  it('스캐너가_if_덩어리를_then과_else로_실제로_잘라낸다', () => {
    const sample = [
      'echo before',
      'if run_it; then',
      '  ok "성공"',
      'else',
      '  FLAG=1',
      '  warn "실패"',
      'fi',
      'die "끝"',
    ].join('\n');

    const block = ifBlockContaining(sample, 'run_it; then');

    expect(block.thenPart).toContain('ok "성공"');
    expect(block.thenPart).not.toContain('FLAG=1');
    expect(block.elsePart).toContain('FLAG=1');
    expect(block.elsePart).not.toContain('ok "성공"');
    expect(block.tail).toContain('die "끝"');
  });

  it('앵커가_없으면_조용히_통과하지_않고_실패한다', () => {
    // 스크립트가 개편돼 앵커가 사라지면 「위반 0건」이 아니라 <에러>로 드러나야 한다.
    expect(() => ifBlockContaining('echo hi', 'nope; then')).toThrow(/앵커를 찾지 못했다/);
  });

  it('★3-1단계는_생성에_실패해도_설치를_멈추지_않고_표식을_세운다', () => {
    const block = ifBlockContaining(read(INSTALL), '"${FE_CONFIG_RENDERER}"; then');

    // 실패 경로의 참인 계약 — 표식 + 큰 경고. 「멈춘다」가 아니다.
    expect(block.elsePart, '실패 표식을 세워야 20 단계가 판정할 수 있다').toContain(
      'FE_CONFIG_PENDING=1',
    );
    expect(block.elsePart, '조용히 넘어가면 지금보다 나쁘다').toContain('warn');
    // ★ 여기서 die 하면 2026-08-30 이전으로 회귀한다(첫 설치가 구조적으로 반드시 죽는다).
    expect(block.elsePart, '이 단계에서 종결하면 첫 설치가 반드시 죽는다 — 종결은 20 단계 몫이다')
      .not.toContain('die');
    // 성공 경로도 함께 본다(실패 축만 보면 성공 안내가 사라져도 통과한다).
    expect(block.thenPart).toContain('ok');
  });

  it('3-1단계가_세운_표식을_같은_스크립트가_읽어_다시_안내한다', () => {
    // 세우기만 하고 아무도 읽지 않으면 표식이 죽은 변수가 된다.
    const install = read(INSTALL);
    expect(install).toMatch(/\[\[\s*"\$\{FE_CONFIG_PENDING:-0\}"\s*==\s*"1"\s*\]\]/);
  });

  it('★종결은_20단계가_한다_필수값이_비면_설치가_실패로_끝난다', () => {
    const verify = read(VERIFY);
    const block = ifBlockContaining(verify, '"${RENDERER}"; then');

    // 생성기를 <다시 실행>해 성패를 읽는다 — 조건을 재유도하지 않는다(생산자/소비자 규칙).
    expect(verify, '판정을 재유도하지 말고 생성기를 실행해야 한다').toContain('"${RENDERER}"');
    // 성공 경로는 여기서 끝난다.
    expect(block.thenPart).toContain('exit 0');
    // 실패 경로 = if 덩어리 <뒤>. 여기에 종결이 있어야 한다.
    expect(block.tail, '필수 설정이 비면 설치를 실패로 종결해야 한다').toMatch(/^die /m);
    expect(block.tail, '실패 경로가 성공으로 빠져나가면 안 된다').not.toContain('exit 0');
  });

  it('★생성기가_성공했는데_산출물이_비면_그것도_실패로_막는다', () => {
    // "생성은 됐다"만 보면 빈 파일이 그대로 배포된다 — 화면은 뜨고 설정만 없다.
    const block = ifBlockContaining(read(VERIFY), '"${RENDERER}"; then');

    expect(block.thenPart).toMatch(/\[\[\s*-s\s*"\$\{OUT_FILE\}"\s*\]\]/);
    expect(block.thenPart).toContain('die');
  });

  it('★그_종결_게이트가_설치_순서에_등록돼_있고_14단계보다_뒤에_온다', () => {
    // 파일에 die 가 있어도 실행되지 않으면 죽은 게이트다. 순서까지 함께 본다 —
    // 14 보다 앞서면 생성기·정본이 아직 없어 「14 가 안 돌았다」로 오진단한다.
    const orchestrator = read('deploy/onprem/scripts/install.sh');
    const at = (step: string): number => orchestrator.indexOf(`STEPS+=("${step}")`);

    expect(at('20-verify-frontend-config.sh'), '종결 단계가 설치 순서에 없다').toBeGreaterThan(-1);
    expect(at('14-install-frontend.sh')).toBeGreaterThan(-1);
    expect(at('20-verify-frontend-config.sh')).toBeGreaterThan(at('14-install-frontend.sh'));
  });
});
