// H-ISSUE-02 — 상위 로그인 URL(VITE_CONTROL_LOGIN_URL / VITE_PORTAL_LOGIN_URL) 배포 배선 가드.
//
// 배경(원 결함): 두 변수는 `.env.example`/`.env.development` 에만 있고, 실제 배포 산출물(dist)을
//   만드는 어떤 경로에도 주입되지 않았다. Vite 는 VITE_* 를 <b>빌드 시점</b>에 정적 치환하므로,
//   빌드에 값이 없으면 dist 안에 빈 문자열이 박힌다 → 세션 만료·401 시 redirectToUpstream() 이
//   fail-open(아무 반응 없는 막다른 화면)으로 떨어진다.
//
// ★ 2026-08-30 전환 — <b>이 값들의 정본은 더 이상 빌드가 아니다.</b>
//   빌드가 실주소를 요구하면 폐쇄망 반입(빌드머신이 고객 환경을 모른 채 매체를 만든다)에서
//   배포 가능한 산출물 자체를 만들 수 없다. 실제로 예시 주소가 구워진 dist 가 반입 대상이었다.
//   지금은 대상 서버의 `/etc/klid/frontend.env` 가 정본이고, 설치가 `klid-config.js` 를 생성한다.
//   따라서 <b>fail-closed 가드도 빌드에서 설치 시점으로 옮겼다</b>.
//     · 옮겨 간 가드의 실동작 검증 → `frontendRuntimeConfigDeployWiring.test.ts` §④
//     · 빌드가 더 이상 값을 요구하지 않는지        → 같은 파일 §⑧
//   ⚠ 그래서 이 파일에서 구 가드(`require_upstream_login_urls`)를 검증하던 케이스 4건을 걷어냈다.
//     되살리면 "환경 무관 산출물"이 다시 성립하지 않는다.
//
// 이 파일에 남는 축은 <b>빌드 시점 폴백 배선</b>이다. 런타임 설정이 없는 경로(`npm run dev`,
// 컨테이너 이미지)는 여전히 이 값을 쓰므로, 세 진입점에 배선이 살아 있어야 한다.
//   ① frontend/Dockerfile                                   (컨테이너 이미지 빌드)
//   ② deploy/onprem/scripts/package/20-build-frontend.sh     (빌드머신 사전빌드)
//   ③ deploy/onprem/scripts/install/build-from-source.sh     (폐쇄망 대상서버 재빌드, ②와 무관)

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

describe('상위 로그인 URL 배포 배선 (H-ISSUE-02)', () => {
  it('Dockerfile_build_스테이지가_두_URL을_ARG_ENV로_받는다', () => {
    // given: Vite 빌드가 일어나는 스테이지
    const dockerfile = read('frontend/Dockerfile');

    // then: ARG 로 받고 ENV 로 승격해야 npm run build 가 값을 본다 (런타임 설정이 없을 때의 폴백)
    for (const key of ENV_KEYS) {
      expect(dockerfile, `${key} ARG 누락`).toMatch(new RegExp(`^ARG ${key}`, 'm'));
      expect(dockerfile, `${key} ENV 누락`).toMatch(new RegExp(`^ENV ${key}=\\$${key}`, 'm'));
    }
  });

  it('Dockerfile_ARG는_로그인_URL에_기본값을_두지_않는다', () => {
    // 기본값을 두면 <잘못된 주소가 조용히 구워진다>. 값이 없는 것은 이제 정상이며(런타임에서
    // 주입한다) 그때 dist 는 아무 주소도 갖지 않아야 한다 — 예시 주소가 남는 것보다 낫다.
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
    // 값을 준 빌드는 그 값을 폴백으로 갖는다(주지 않아도 빌드는 성공한다 — §⑧).
    for (const rel of DEPLOY_SCRIPTS) {
      const script = read(rel);
      for (const key of ENV_KEYS) {
        expect(script, `${rel}: ${key} export 누락`).toMatch(new RegExp(`export ${key}=`, 'm'));
      }
    }
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

  it('설정문서가_런타임_정본의_위치를_알린다', () => {
    // 문서가 옛 "빌드 타임 변수"로만 남으면 운영자가 값을 고칠 곳을 찾지 못한다.
    const doc = read('deploy/onprem/docs/04-configuration.md');
    expect(doc).toContain('frontend.env');
    expect(doc).toContain('klid-frontend-config');
  });
});
