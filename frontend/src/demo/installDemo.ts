import MockAdapter from 'axios-mock-adapter';

import { registerHostTokenHandoff, type TokenHandoffGateway } from '@/features/auth/tokenHandoff';
import type { PortalMarkingSaveRequest } from '@/features/portal/uploads/markingTypes';
import type { PortalUpload, PortalUploadDetail } from '@/features/portal/uploads/types';
import { apiClient } from '@/lib/api/client';

import { fail, ok } from './mockReply';
import { 증강시연 } from './screens/augments';
import { DETAIL as 라벨링자산, 라벨링응답 } from './screens/labeling';
import { DETAIL as 마킹자산, SAVED_MARKS, STREAM, markAt, markingList, saveResult, 마킹응답 } from './screens/marking';
import { UPLOADED, stamp, 내업로드시연 } from './screens/uploads';
import { WORKS, 내작업목록 } from './screens/works';

/**
 * 서버 없이 띄우는 시연판 — 통신 창구(`apiClient`)에 가짜 응답을 끼우고, 포털 Host 대신 로그인을 건넨다.
 *
 * 스토리북(6010)이 화면 한 장마다 하던 일을 앱 전체에 한 번 한다. 견본 값은 스토리와 같은 자리
 * (`demo/screens/*`)에서 가져오므로 스토리북 그림과 시연판이 어긋나지 않는다.
 *
 * ★ 진입점(main.tsx)이 `VITE_DEMO_STANDALONE=true` 일 때만 이 모듈을 불러온다 — 그 밖의 산출물에는
 *   실리지 않는다(산출 시점에 굳는 값).
 */

/** 시연판 사용자 — 스토리북의 로그인과 같다(포털 사용자 홍길동) */
const DEMO_USER = {
  sub: '3001',
  name: '홍길동',
  role: 'PORTAL_USER',
  channel: 'PORTAL',
  exp: 4102444800,
} as const;

function base64url(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  bytes.forEach((b) => {
    binary += String.fromCharCode(b);
  });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** 서명 없는 토큰 — 화면은 안쪽 내용(이름 · 역할 · 채널 · 만료)만 읽고 서명은 보지 않는다 */
const DEMO_TOKEN = `${base64url(JSON.stringify({ alg: 'none', typ: 'JWT' }))}.${base64url(
  JSON.stringify(DEMO_USER),
)}.demo`;

/** 포털 Host 대역 — 늘 같은 로그인을 건넨다 */
const demoGateway: TokenHandoffGateway = {
  getAccessToken: () => DEMO_TOKEN,
  refresh: async () => DEMO_TOKEN,
  onUnauthorized() {
    // 시연판에는 로그아웃이 없다
  },
  notifyActivity() {
    // 세션 연장이 없다
  },
};

const 영상길이 = 라벨링자산.vdoLenSec ?? 229;
const 초당프레임 = 라벨링자산.fps ?? 30;

/** 목록 줄 → 자산 상세 — 새로 올린 줄(견본에 없는 번호)의 마킹 화면이 이것을 본다 */
function detailOf(row: PortalUpload): PortalUploadDetail {
  return {
    ...마킹자산,
    uldSn: row.uldSn,
    orgnlFileNm: row.orgnlFileNm,
    fileSz: row.fileSz,
    mimeTypeNm: row.mimeTypeNm,
    uldSttsCd: row.uldSttsCd,
    frmeCnt: row.frmeCnt,
    regDt: row.regDt,
    expiresAt: row.expiresAt,
  };
}

/**
 * 새로 올린 자산의 마킹 — 견본 번호(4 · 5)에는 앞서 건 응답이 먼저 걸리고, 나머지 번호만 여기로 온다.
 * 저장하면 그 줄은 「추출 중」이 된다.
 */
function 새자산마킹(mock: MockAdapter, rows: PortalUpload[]) {
  const sn = (url: string | undefined, tail: string) =>
    Number(new RegExp(`/portal/uploads/(\\d+)${tail}$`).exec(url ?? '')?.[1]);
  const saved = new Map<number, ReturnType<typeof saveResult>>();

  mock.onGet(/^\/portal\/uploads\/(\d+)$/).reply((config) => {
    const row = rows.find((r) => r.uldSn === sn(config.url, ''));
    return row ? ok(detailOf(row)) : fail(404, null, 'NOT_FOUND');
  });
  mock.onGet(/^\/portal\/uploads\/(\d+)\/stream-url$/).reply(...ok(STREAM));
  mock.onGet(/^\/portal\/uploads\/(\d+)\/markings$/).reply((config) => {
    const id = sn(config.url, '/markings');
    return ok(markingList(saved.get(id)?.marks ?? null, id));
  });
  mock.onPost(/^\/portal\/uploads\/(\d+)\/markings$/).reply((config) => {
    const id = sn(config.url, '/markings');
    const body = JSON.parse(String(config.data ?? '{}')) as Partial<PortalMarkingSaveRequest>;
    const interval = body.interval ?? 300;
    const marks =
      body.marks ??
      Array.from({ length: Math.floor((영상길이 * 초당프레임) / interval) + 1 }, (_, i) => markAt(i * interval));
    const result = { ...saveResult(marks, marks.length, id), mode: body.mode ?? 'AUTO', interval, regDt: stamp() };
    saved.set(id, result);
    const row = rows.find((r) => r.uldSn === id);
    if (row) {
      row.uldSttsCd = 'PROCESSING';
      row.expiresAt = null;
    }
    return [201, ok(result)[1]];
  });
}

export function installDemo(): void {
  const mock = new MockAdapter(apiClient);

  // 내 정보 — 서버가 아는 역할·이름
  mock.onGet('/me').reply(
    ...ok({ sub: DEMO_USER.sub, role: DEMO_USER.role, channel: DEMO_USER.channel, name: DEMO_USER.name }),
  );

  // AI 보조 기본값 — 서버가 따로 정한 값이 없어 화면의 기본값이 쓰인다
  mock.onGet('/portal/ai-defaults').reply(...ok({}));

  // 내 작업 — 내 업로드 줄만 (데이터마트 영상의 라벨링은 이 시연판에 없다)
  내작업목록(
    mock,
    WORKS.filter((w) => w.assetSource === 'PORTAL_UPLOAD'),
  );

  // 증강 · 내 업로드 — 내 업로드에서 낸 증강 요청이 증강 목록 맨 위에 붙는다
  const augments = 증강시연(mock);
  const uploads = 내업로드시연(mock, { onAugment: augments.add });

  // 마킹 — 「마킹 대기」 줄(5번)은 저장 전, 준비 완료 줄(4번)은 이미 저장함
  마킹응답(mock, {
    uldSn: UPLOADED.uldSn,
    detail: {
      orgnlFileNm: UPLOADED.orgnlFileNm,
      fileSz: UPLOADED.fileSz,
      regDt: UPLOADED.regDt,
      expiresAt: UPLOADED.expiresAt,
    },
    save: saveResult(SAVED_MARKS, SAVED_MARKS.length, UPLOADED.uldSn),
  });
  마킹응답(mock, { uldSn: 라벨링자산.uldSn, saved: SAVED_MARKS });

  // 라벨링 — 4번 자산 상세는 여기 것이 마지막에 걸려 이긴다(프레임 23장을 담은 판)
  라벨링응답(mock);

  // 그 밖의 번호(새로 올린 줄) — 위의 딱 맞는 주소가 먼저 걸리고, 남은 것만 여기로 온다
  새자산마킹(mock, uploads);

  // 걸어 두지 않은 주소는 콘솔에 남기고 404 로 돌려준다 — 빠진 응답을 찾기 쉽게
  mock.onAny().reply((config) => {
    console.warn('[시연판] 가짜 응답이 없는 요청', config.method, config.url);
    return fail(404, null, 'NOT_FOUND');
  });

  registerHostTokenHandoff(demoGateway);
}
