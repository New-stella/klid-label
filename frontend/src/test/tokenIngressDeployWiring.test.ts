// H-ISSUE-01 — 토큰 인계 채널(VITE_TOKEN_INGRESS) 배포 배선 가드.
//
// 배경: 확정 정책(ADR-012)은 관제/포털이 <b>동일 origin 브라우저 저장소</b>로 JWT 를 인계하며
//   `?token=` 쿼리 파라미터 방식은 쓰지 않는다. URL 에 실린 JWT 는 웹서버 접근 로그·리퍼러
//   헤더·브라우저 히스토리에 잔존해 사후 회수가 불가능하다 (CWE-598 / CWE-200).
//   그런데 배포 기본값이 오래 `all`(url → localStorage → cookie) 이라 URL 채널이 열려 있었고,
//   2차·3차 검증에서 실브라우저로 `GET /ingress?token=<JWT>` 단독 인증 성립이 재현됐다.
//
// Vite 는 VITE_* 를 <b>빌드 시점</b>에 정적 치환하므로 대상 서버에서 바꿀 수 없다. 게다가 기본값을
// 선언하는 지점이 서로 <b>완전히 독립</b>이라 하나만 되돌려도 그 배포 경로만 조용히 URL 채널이
// 열린다. 소스 기본값을 고쳐도 compose 가 값을 <b>명시 전달</b>하면 그대로 덮인다(실제로 그랬다).
// 런타임 테스트로는 잡히지 않으므로 파일을 직접 읽어 고정한다.
//   ① frontend/Dockerfile                                   (컨테이너 이미지 빌드)
//   ② docker-compose.yml                                     (compose 가 ①의 ARG 를 덮어쓴다)
//   ③ deploy/onprem/scripts/package/20-build-frontend.sh     (빌드머신 사전빌드)
//   ④ deploy/onprem/scripts/install/build-from-source.sh     (폐쇄망 대상서버 재빌드, ③과 무관)
//   ⑤ .env.example                                           (운영자가 복사해 쓰는 기준 파일)
//
// 판정 방식 (중요):
//   - 리터럴 'localStorage' 로 못박지 <b>않는다</b>. 정책이 cookie 로 바뀌어도 참이어야 하고,
//     그래야 가드가 정책 변경마다 거짓 실패를 내지 않으면서 이 결함의 재발만 막는다.
//   - 대신 각 지점의 기본값을 꺼내 <b>실제 resolveToken 에 먹여</b> "URL 토큰을 채택하는가"를 본다.
//     URL 을 읽는 전략 목록(url/both/all)을 테스트가 따로 갖지 않으므로 사본 드리프트가 없다.
//   - 셸 기본값은 grep 이 아니라 <b>실제 bash 로 실행</b>해 판정한다. `${VAR:-기본}` 을
//     `${VAR-기본}` 으로 바꾸면 빈 문자열이 그대로 흘러가는데, 이 저장소가 실제로 겪은 사고
//     형태이며 정적 검사로는 두 표기의 차이가 드러나지 않는다.

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  DEFAULT_INGRESS_STRATEGY,
  TOKEN_INGRESS_STRATEGIES,
  resolveToken,
} from '@/features/auth/tokenIngress';

const REPO_ROOT = path.resolve(__dirname, '../../..');
const ENV_KEY = 'VITE_TOKEN_INGRESS';
const COOKIE_NAME = 'klid_jwt';

const read = (rel: string): string => readFileSync(path.join(REPO_ROOT, rel), 'utf-8');

/** 셸 `${VAR:-기본}` 확장을 쓰는 온프렘 빌드 스크립트 2종 (서로 독립 경로). */
const DEPLOY_SCRIPTS = [
  'deploy/onprem/scripts/package/20-build-frontend.sh',
  'deploy/onprem/scripts/install/build-from-source.sh',
] as const;

// --- helpers ---------------------------------------------------------------

function b64url(obj: Record<string, unknown>): string {
  const b64 = btoa(unescape(encodeURIComponent(JSON.stringify(obj))));
  return b64.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

/** 형식·alg 검증을 통과하는 유효 JWT — 채택 여부만 보기 위한 미끼. */
const URL_TOKEN = `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url({ sub: 'url', exp: 9999999999 })}.sig`;

/**
 * 그 전략이 <b>URL 쿼리 토큰을 채택하는가</b>를 실제 resolveToken 으로 판정한다.
 *
 * 전략 목록을 테스트가 복제하지 않는 것이 핵심이다 — 판정 주체가 프로덕션 코드 자신이므로
 * resolveToken 의 분기가 바뀌면 이 가드도 자동으로 따라간다.
 */
function readsUrlToken(strategy: string): boolean {
  vi.stubEnv(ENV_KEY, strategy);
  try {
    return resolveToken({ urlToken: URL_TOKEN, cookieName: COOKIE_NAME }) === URL_TOKEN;
  } finally {
    vi.unstubAllEnvs();
  }
}

/** `KEY=값` / `KEY: 값` / `export KEY="값"` 에서 값 부분만 뽑는다(따옴표 제거). */
function extractAssignment(content: string, rel: string): string {
  const line = content
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => !l.startsWith('#'))
    .find((l) => new RegExp(`(^|\\s)(ARG |ENV |export )?${ENV_KEY}\\s*[:=]`).test(l));
  if (!line) throw new Error(`${rel}: ${ENV_KEY} 선언을 찾지 못했다`);
  const value = line.slice(line.indexOf(ENV_KEY) + ENV_KEY.length).replace(/^\s*[:=]\s*/, '');
  return value.replace(/^["']|["']$/g, '').trim();
}

/**
 * 셸 파라미터 확장을 <b>실제 bash 로</b> 평가해 실효 기본값을 얻는다.
 * `${VAR:-기본}`(빈값도 기본) 과 `${VAR-기본}`(빈값 유지) 의 차이는 정적 검사로 안 드러난다.
 */
function evalInShell(expression: string, env: Record<string, string>): string {
  const res = spawnSync('bash', ['-c', `printf '%s' "${expression.replace(/"/g, '\\"')}"`], {
    env: { PATH: process.env.PATH ?? '', ...env },
    encoding: 'utf-8',
  });
  if (res.status !== 0) throw new Error(`shell eval 실패: ${expression} — ${res.stderr}`);
  return res.stdout;
}

/** 배포 지점별 기본값 선언 — 값은 파일에서 읽고, 셸 확장은 셸로 평가한다. */
const DEPLOY_POINTS: { rel: string; shellExpanded: boolean }[] = [
  { rel: 'frontend/Dockerfile', shellExpanded: false },
  { rel: 'docker-compose.yml', shellExpanded: true },
  { rel: 'deploy/onprem/scripts/package/20-build-frontend.sh', shellExpanded: true },
  { rel: 'deploy/onprem/scripts/install/build-from-source.sh', shellExpanded: true },
  { rel: '.env.example', shellExpanded: false },
];

function defaultStrategyOf(rel: string, shellExpanded: boolean): string {
  const raw = extractAssignment(read(rel), rel);
  return shellExpanded ? evalInShell(raw, {}) : raw;
}

// --- tests -----------------------------------------------------------------

describe('토큰 인계 채널 배포 배선 (H-ISSUE-01)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('URL을_읽는_전략이_실제로_존재한다_가드_자체_유효성', () => {
    // 이 전제가 깨지면(= 어떤 전략도 URL 을 안 읽으면) 아래 가드들이 전부 공허하게 통과한다.
    // 가드가 조용히 아무것도 안 지키게 되는 것을 막는 자기 점검이다.
    const urlReading = TOKEN_INGRESS_STRATEGIES.filter(readsUrlToken);
    expect(urlReading.length).toBeGreaterThan(0);
  });

  it('배포_5지점의_기본값이_URL_쿼리_토큰을_채택하지_않는다', () => {
    // 핵심 불변식. 리터럴로 값을 못박지 않으므로 정책이 다른 비-URL 채널로 바뀌어도 통과한다.
    for (const { rel, shellExpanded } of DEPLOY_POINTS) {
      const strategy = defaultStrategyOf(rel, shellExpanded);
      expect(readsUrlToken(strategy), `${rel}: 기본값 '${strategy}' 이 URL 쿼리 채널을 연다`).toBe(
        false,
      );
    }
  });

  it('배포_5지점의_기본값이_해석_가능한_전략값이다', () => {
    // 오타(예: 'localstorage')는 런타임 폴백에 가려 조용히 넘어간다 — 배포 설정 단계에서 잡는다.
    for (const { rel, shellExpanded } of DEPLOY_POINTS) {
      const strategy = defaultStrategyOf(rel, shellExpanded);
      expect(
        TOKEN_INGRESS_STRATEGIES as readonly string[],
        `${rel}: '${strategy}' 은 해석되지 않는 값이다`,
      ).toContain(strategy);
    }
  });

  it('소스_런타임_폴백도_URL_쿼리_토큰을_채택하지_않는다', () => {
    // 배포 5지점이 전부 비어도 마지막에 남는 값. 여기가 열리면 위 가드가 의미를 잃는다.
    expect(readsUrlToken(DEFAULT_INGRESS_STRATEGY)).toBe(false);
  });

  it('Dockerfile_build_스테이지가_ARG_ENV로_받는다', () => {
    const dockerfile = read('frontend/Dockerfile');
    expect(dockerfile, `${ENV_KEY} ARG 누락`).toMatch(new RegExp(`^ARG ${ENV_KEY}=`, 'm'));
    expect(dockerfile, `${ENV_KEY} ENV 누락`).toMatch(new RegExp(`^ENV ${ENV_KEY}=\\$${ENV_KEY}`, 'm'));
  });

  it('셸_기본값은_빈_문자열이_들어와도_안전한_값으로_떨어진다', () => {
    // `${VAR:-기본}` 을 `${VAR-기본}` 으로 바꾸면 빈 문자열이 그대로 흘러 dist 에 박힌다.
    // (.env 의 빈값이 기본값을 무력화한 사고 이력이 이 저장소에 있다.)
    for (const rel of DEPLOY_SCRIPTS) {
      const raw = extractAssignment(read(rel), rel);

      const empty = evalInShell(raw, { [ENV_KEY]: '' });
      expect(empty, `${rel}: 빈 문자열이 그대로 통과한다 (:- 가 아니라 - 를 쓰고 있다)`).not.toBe('');
      expect(readsUrlToken(empty), `${rel}: 빈값 폴백이 URL 채널을 연다`).toBe(false);

      const unset = evalInShell(raw, {});
      expect(unset, `${rel}: 미설정 시 빈값이 된다`).not.toBe('');
      expect(readsUrlToken(unset), `${rel}: 미설정 폴백이 URL 채널을 연다`).toBe(false);
    }
  });

  it('셸_기본값은_명시_override를_덮지_않는다', () => {
    // 이번 변경은 <기본값>만 좁힌 것이다. 레거시 호환이 필요한 현장은 종전대로 동작해야 한다.
    for (const rel of DEPLOY_SCRIPTS) {
      const raw = extractAssignment(read(rel), rel);
      expect(evalInShell(raw, { [ENV_KEY]: 'all' }), `${rel}: override 가 무시된다`).toBe('all');
      expect(evalInShell(raw, { [ENV_KEY]: 'cookie' }), `${rel}: override 가 무시된다`).toBe('cookie');
    }
  });

  it('compose가_frontend에_토큰_인계_채널을_전달한다', () => {
    // compose 가 environment 로 명시 전달하므로 Dockerfile ARG 기본값을 덮는다.
    // 배선 자체가 사라지면 어느 값이 실효인지 추적이 끊긴다.
    expect(read('docker-compose.yml'), `${ENV_KEY} 미배선`).toContain(ENV_KEY);
  });

  it('운영_문서가_URL_채널을_기본값·명령예시로_안내하지_않는다', () => {
    // 문서가 옛 기본값을 적고 있으면 다음 사람이 코드를 문서에 맞춰 되돌린다.
    // 특히 <b>실행 가능한 명령 예시</b>가 낡으면 운영자가 그대로 복사해 명시 override 로
    // URL 채널을 되켠다 — 코드를 고쳐도 현장에서 무효화되는 경로다.
    //
    // 두 형태를 따로 판정한다. 한 형태만 보면 다른 쪽이 빠져나간다(실측으로 한 번 뚫렸다:
    // 표 셀의 `기본 \`all\`` 은 `KEY=값` 형태가 아니라 대입형 검사만으로는 안 잡혔다).
    //   ⓐ 대입형   `VITE_TOKEN_INGRESS=all`      — 복사 가능한 명령/설정 예시
    //   ⓑ 기본값형 `... 기본 \`all\` ...`         — 설명문·항목표의 기본값 서술
    // 반면 "`url`/`both`/`all` 은 위험하다" 같은 <b>경고 문구</b>는 두 형태 어디에도 걸리지
    // 않아야 한다(걸리면 경고를 적을수록 가드가 실패하는 자가당착이 된다).
    const GUIDES = [
      'deploy/onprem/docs/04-configuration.md',
      'deploy/onprem/docs/02-build-package.md',
      'deploy/onprem/docs/08-build-from-source.md',
      ...DEPLOY_SCRIPTS, // 사용법 주석에도 복사용 명령 예시가 있다
    ];

    for (const rel of GUIDES) {
      for (const line of read(rel).split(/\r?\n/)) {
        if (!line.includes(ENV_KEY)) continue;

        const advertised = [
          // ⓐ 대입형 — 셸 확장(`="${...}"`)은 \w 로 시작하지 않아 자연히 제외된다
          ...line.matchAll(new RegExp(`${ENV_KEY}\\s*=\\s*\`?([A-Za-z]+)\`?`, 'g')),
          // ⓑ 기본값형 — 같은 줄에서 "기본 X" 로 안내하는 값
          ...line.matchAll(/기본\s*`?([A-Za-z]+)`?/g),
        ].map((m) => m[1]);

        for (const strategy of advertised) {
          expect(
            readsUrlToken(strategy),
            `${rel}: URL 쿼리 채널을 여는 '${strategy}' 을 기본값·명령예시로 안내하고 있다\n  → ${line.trim()}`,
          ).toBe(false);
        }
      }
    }
  });
});
