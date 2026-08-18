// 앞단(프록시) 요청 크기 상한이 **세 배포 형상에서 같은가** — 판정은 언제나 서버가 한다.
//
// ★ 무엇이 어긋나 있었나: 컨테이너 이미지에 구워지는 `frontend/nginx.conf` 만 512MB 로 남아 있었다.
//   서버 상한은 1200MB(운영 1100MB)라 그 사이 크기의 요청은 **서버에 닿기도 전에** 프록시가
//   거부한다 — 앱이 만든 413(ApiResponse + errorCode)이 아니라 프록시 기본 오류 페이지가 나가고
//   서버 로그에는 아무 흔적도 남지 않는다. 포털 이미지 다중 업로드는 확정 계약상 한 요청 최대
//   1,000MB(개당 20MB × 50장)이므로 512MB 를 정당하게 넘는 요청이 실재한다.
//
// ★ 왜 이 검사가 **프론트엔드에** 있나: backend 쪽 정합 가드(`EdgeRequestSizeAlignmentGuardTest`)는
//   온프렘 템플릿 두 개만 본다. 이 파일은 그 가드의 시야 밖이라, 여기서 함께 고정하지 않으면
//   컨테이너 배포에서만 같은 결함이 조용히 되살아난다.
//
// ⚠ 서버 상한 자체(1200MB 가 맞는가)는 여기서 판정하지 않는다 — 그건 backend 가드의 몫이고,
//   여기서 그 숫자를 다시 적으면 두 번째 진실원이 된다. 이 파일은 **세 형상이 서로 같은가**만 본다.

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

/** 컨테이너 이미지에 구워지는 형상. */
const CONTAINER_NGINX = resolve(__dirname, '../../nginx.conf');
/** 온프렘 대안 형상(기존 nginx 보유 서버). */
const ONPREM_NGINX = resolve(__dirname, '../../../deploy/onprem/config/frontend/nginx.conf.template');
/** 온프렘 기본 형상(Caddy). */
const ONPREM_CADDY = resolve(__dirname, '../../../deploy/onprem/config/frontend/Caddyfile.template');

/** `client_max_body_size 1200m;` */
const NGINX_LIMIT = /^\s*client_max_body_size\s+(\d+)([kKmMgG]?)\s*;/m;
/** Caddy `request_body { max_size 1200MB }` */
const CADDY_LIMIT = /^\s*max_size\s+(\d+)([kKmMgG]?)[bB]?\s*$/m;

const UNIT_BYTES: Record<string, number> = {
  '': 1,
  k: 1024,
  m: 1024 * 1024,
  g: 1024 * 1024 * 1024,
};

function limitBytes(path: string, pattern: RegExp): number {
  const content = readFileSync(path, 'utf-8');
  const m = pattern.exec(content);
  // 설정이 사라졌거나 문법이 바뀐 것도 결함이다 — 못 찾으면 통과시키지 않는다.
  expect(m, `${path} 에서 본문 크기 상한을 찾지 못했다`).not.toBeNull();
  const [, value, unit] = m as RegExpExecArray;
  return Number(value) * UNIT_BYTES[unit.toLowerCase()];
}

describe('앞단 요청 크기 상한 — 배포 형상 정합', () => {
  it('컨테이너_형상과_온프렘_nginx_형상의_상한이_같다', () => {
    expect(limitBytes(CONTAINER_NGINX, NGINX_LIMIT)).toBe(limitBytes(ONPREM_NGINX, NGINX_LIMIT));
  });

  it('컨테이너_형상과_온프렘_Caddy_형상의_상한이_같다', () => {
    expect(limitBytes(CONTAINER_NGINX, NGINX_LIMIT)).toBe(limitBytes(ONPREM_CADDY, CADDY_LIMIT));
  });

  it('컨테이너_형상의_상한이_포털_이미지_다중_업로드_최대_요청을_덮는다', () => {
    // 앞단을 넓힌 근거 자체를 고정한다 — 이 관계가 깨지면 «왜 1200MB 인가» 의 근거가 사라지고,
    // 다음 사람이 앞단을 다시 좁혀도 아무 것도 죽지 않는다.
    // 확정 계약: 개당 20MB × 요청당 50장 = 1,000MB.
    const worstCaseBytes = 20 * 1024 * 1024 * 50;
    expect(limitBytes(CONTAINER_NGINX, NGINX_LIMIT)).toBeGreaterThanOrEqual(worstCaseBytes);
  });
});
