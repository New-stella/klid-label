// H-ISSUE-02 — 상위 로그인 URL(VITE_CONTROL_LOGIN_URL / VITE_PORTAL_LOGIN_URL) 배포 배선 가드.
//
// 배경: 두 변수는 `.env.example`/`.env.development` 에만 있고, 실제 배포 산출물(dist)을 만드는
//   어떤 경로에도 주입되지 않았다. Vite 는 VITE_* 를 <b>빌드 시점</b>에 정적 치환하므로, 빌드에
//   값이 없으면 dist 안에 빈 문자열이 박힌다 → 세션 만료·401 시 redirectToUpstream() 이
//   fail-open(아무 반응 없는 막다른 화면)으로 떨어진다.
//
// 산출물을 만드는 진입점은 서로 <b>완전히 독립</b>인 3곳이며, 하나라도 빠지면 그 배포 경로만
// 조용히 로그인 URL 이 안 먹는다. 런타임 테스트로는 잡히지 않으므로 파일을 직접 읽어 고정한다.
//   ① frontend/Dockerfile                                   (컨테이너 이미지 빌드)
//   ② deploy/onprem/scripts/package/20-build-frontend.sh     (빌드머신 사전빌드)
//   ③ deploy/onprem/scripts/install/build-from-source.sh     (폐쇄망 대상서버 재빌드, ②와 무관)

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const REPO_ROOT = path.resolve(__dirname, '../../..');
const ENV_KEYS = ['VITE_CONTROL_LOGIN_URL', 'VITE_PORTAL_LOGIN_URL'] as const;

const read = (rel: string): string => readFileSync(path.join(REPO_ROOT, rel), 'utf-8');

const DEPLOY_SCRIPTS = [
  'deploy/onprem/scripts/package/20-build-frontend.sh',
  'deploy/onprem/scripts/install/build-from-source.sh',
] as const;

/** 중복 구현 드리프트를 막기 위해 fail-closed 검사는 common.sh 헬퍼 하나로 모은다. */
const GUARD_FN = 'require_upstream_login_urls';
const COMMON_SH = path.join(REPO_ROOT, 'deploy/onprem/scripts/lib/common.sh');

/** common.sh 를 source 해 가드만 실행하고 종료코드를 돌려준다(실제 셸 동작 검증). */
function runGuard(env: Record<string, string>): number {
  const res = spawnSync(
    'bash',
    ['-c', `set -euo pipefail; source "${COMMON_SH}"; ${GUARD_FN}`],
    {
      env: { PATH: process.env.PATH ?? '', ...env },
      encoding: 'utf-8',
    },
  );
  return res.status ?? -1;
}

/** Dockerfile 의 `\` 줄바꿈 연결(line continuation)을 펼쳐 명령 단위 문자열 배열로 만든다. */
function dockerfileInstructions(dockerfile: string): string[] {
  return dockerfile
    .replace(/\\\r?\n\s*/g, ' ')
    .split(/\r?\n/)
    .map((l) => l.trim());
}

/**
 * Dockerfile 안에 선언된 fail-closed 가드 RUN 명령을 그대로 꺼내 셸로 실행한다.
 *
 * 전체 `docker build` 를 돌리지 않는 이유: 가드 앞에 `npm ci`(네트워크 + 수 분)가 있어
 * 단위 테스트에서 매번 돌릴 수 없고 오프라인 CI 에서 오탐이 된다. 대신 **Dockerfile 에 적힌
 * 명령 문자열 자체를** 실행하므로 grep 정적 검사와 달리 가드 로직 오류를 실제로 잡는다
 * (common.sh 가드를 `runGuard` 로 검증하는 것과 같은 방식).
 */
function runDockerfileGuard(env: Record<string, string>): number {
  const guard = dockerfileInstructions(read('frontend/Dockerfile')).find(
    (l) => l.startsWith('RUN ') && l.includes('VITE_CONTROL_LOGIN_URL'),
  );
  if (!guard) return -1;
  const res = spawnSync('sh', ['-c', guard.replace(/^RUN /, '')], {
    env: { PATH: process.env.PATH ?? '', ...env },
    encoding: 'utf-8',
  });
  return res.status ?? -1;
}

describe('상위 로그인 URL 배포 배선 (H-ISSUE-02)', () => {
  it('Dockerfile_build_스테이지가_두_URL을_ARG_ENV로_받는다', () => {
    // given: Vite 빌드가 일어나는 스테이지
    const dockerfile = read('frontend/Dockerfile');

    // then: ARG 로 받고 ENV 로 승격해야 npm run build 가 값을 본다
    for (const key of ENV_KEYS) {
      expect(dockerfile, `${key} ARG 누락`).toMatch(new RegExp(`^ARG ${key}`, 'm'));
      expect(dockerfile, `${key} ENV 누락`).toMatch(new RegExp(`^ENV ${key}=\\$${key}`, 'm'));
    }
  });

  it('docker_build_시_URL_미지정이면_빌드가_실패한다', () => {
    // given: 두 래퍼 스크립트를 우회해 `docker build` 를 직접 호출한 경우 (--build-arg 없음).
    //        ARG 기본값이 빈 문자열이라 가드가 없으면 조용히 성공해버린다 (CWE-1188).
    // when/then: Dockerfile 에 적힌 가드 명령이 비정상 종료해야 RUN 이 실패하고 빌드가 멈춘다
    expect(runDockerfileGuard({})).not.toBe(0);
    expect(
      runDockerfileGuard({ VITE_CONTROL_LOGIN_URL: 'https://control.local/login', VITE_PORTAL_LOGIN_URL: '' }),
    ).not.toBe(0);
    expect(
      runDockerfileGuard({ VITE_CONTROL_LOGIN_URL: '', VITE_PORTAL_LOGIN_URL: 'https://portal.local/login' }),
    ).not.toBe(0);
  });

  it('docker_build_시_URL을_모두_지정하면_빌드가_성공한다', () => {
    // 회귀: --build-arg 로 정상 전달되면 가드가 빌드를 막지 않아야 한다
    expect(
      runDockerfileGuard({
        VITE_CONTROL_LOGIN_URL: 'https://control.example.local/login',
        VITE_PORTAL_LOGIN_URL: 'https://portal.example.local/login',
      }),
    ).toBe(0);
  });

  it('Dockerfile_가드는_npm_run_build_보다_먼저_실행된다', () => {
    // 가드가 빌드 뒤에 있으면 빈 값이 박힌 dist 가 이미 만들어진 뒤라 의미가 없다.
    const lines = dockerfileInstructions(read('frontend/Dockerfile'));
    const guardIdx = lines.findIndex(
      (l) => l.startsWith('RUN ') && l.includes('VITE_CONTROL_LOGIN_URL'),
    );
    const buildIdx = lines.findIndex((l) => l === 'RUN npm run build');
    expect(guardIdx).toBeGreaterThanOrEqual(0);
    expect(buildIdx).toBeGreaterThanOrEqual(0);
    expect(guardIdx).toBeLessThan(buildIdx);
  });

  it('Dockerfile_ARG는_로그인_URL에_기본값을_두지_않는다', () => {
    // 기본값을 두면 가드를 통과하면서 잘못된 URL 로 조용히 배포된다.
    for (const key of ENV_KEYS) {
      expect(read('frontend/Dockerfile'), `${key} 에 기본값이 있으면 안 된다`).toMatch(
        new RegExp(`^ARG ${key}=\\s*$`, 'm'),
      );
    }
  });

  it('docker_compose_frontend_빌드에_두_URL이_전달된다', () => {
    // given
    const compose = read('docker-compose.yml');

    // then: compose 로 이미지를 빌드할 때도 값이 흘러야 한다
    for (const key of ENV_KEYS) {
      expect(compose, `${key} 미배선`).toContain(key);
    }
  });

  it('온프렘_빌드_스크립트_2곳이_두_URL을_export한다', () => {
    for (const rel of DEPLOY_SCRIPTS) {
      const script = read(rel);
      for (const key of ENV_KEYS) {
        expect(script, `${rel}: ${key} export 누락`).toMatch(
          new RegExp(`export ${key}=`, 'm'),
        );
      }
    }
  });

  it('온프렘_빌드_스크립트_2곳이_fail_closed_가드를_호출한다', () => {
    // 조용히 빈 값으로 빌드되면 배포 후에야 "로그인 화면으로 못 감"으로 드러난다.
    // 두 스크립트가 각자 검사를 복제하면 한쪽만 갱신되는 드리프트가 생기므로 공통 헬퍼로 모은다.
    for (const rel of DEPLOY_SCRIPTS) {
      expect(read(rel), `${rel}: ${GUARD_FN} 호출 누락`).toMatch(
        new RegExp(`^\\s*${GUARD_FN}\\b`, 'm'),
      );
    }
  });

  it('가드는_값_미설정시_비정상_종료한다', () => {
    // when/then: 셸을 실제로 실행해 종료코드로 판정 (grep 만으로는 로직 오류를 못 잡는다)
    expect(runGuard({})).not.toBe(0);
    expect(
      runGuard({ VITE_CONTROL_LOGIN_URL: 'https://control.local/login', VITE_PORTAL_LOGIN_URL: '' }),
    ).not.toBe(0);
    expect(
      runGuard({ VITE_CONTROL_LOGIN_URL: '', VITE_PORTAL_LOGIN_URL: 'https://portal.local/login' }),
    ).not.toBe(0);
  });

  it('가드는_스킴_없는_값을_거부한다', () => {
    // redirectToUpstream 은 값을 그대로 location.assign 에 넘긴다 — 스킴이 없으면
    // 상대경로로 해석돼 저작도구 자기 자신으로 돌아가는 무한 루프가 된다.
    expect(
      runGuard({
        VITE_CONTROL_LOGIN_URL: 'control.local/login',
        VITE_PORTAL_LOGIN_URL: 'https://portal.local/login',
      }),
    ).not.toBe(0);
  });

  it('가드는_두_값이_모두_완전한_URL이면_통과한다', () => {
    expect(
      runGuard({
        VITE_CONTROL_LOGIN_URL: 'https://control.example.local/login',
        VITE_PORTAL_LOGIN_URL: 'http://portal.example.local/login',
      }),
    ).toBe(0);
  });

  it('frontend_로컬_빌드_스크립트에는_fail_closed_가드를_넣지_않는다', () => {
    // .env.development 의 빈 값이 정상인 개발/CI 빌드까지 막으면 오탐이다.
    const pkg = JSON.parse(read('frontend/package.json')) as {
      scripts: Record<string, string>;
    };
    expect(pkg.scripts.build).not.toContain('VITE_CONTROL_LOGIN_URL');
    expect(pkg.scripts.build).not.toContain('VITE_PORTAL_LOGIN_URL');
  });

  it('온프렘_설정문서에_두_URL이_필수_항목으로_등재된다', () => {
    const doc = read('deploy/onprem/docs/04-configuration.md');
    for (const key of ENV_KEYS) {
      expect(doc, `${key} 문서 미등재`).toContain(key);
    }
  });
});
