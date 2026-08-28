import { Role } from '@/lib/api/types';

/**
 * 내부 채널 「경로 → 허용 역할」 선언 — **좌측 메뉴 노출과 라우트 인가의 단일 진실원**.
 *
 * <h3>왜 한 곳인가</h3>
 * 예전에는 같은 조건이 두 곳에 적혀 있었다 — 좌측 메뉴가 항목마다 허용 역할을 들고, 라우터가
 * 따로 허용 목록을 걸었다. 두 파일의 주석이 「같은 조건이어야 한다」고 서로를 향해 경고만 하고
 * 있었을 뿐 그것을 지키게 하는 장치는 없었고, 실제로 한쪽만 갱신돼 「메뉴는 없는데 주소로는
 * 들어가진다」(또는 그 반대)가 반복해서 생겼다. 셸 사양도 같은 것을 금지한다 — 메뉴 구성은
 * 셸이 정하지 않고 내비게이션 정의를 그대로 따른다.
 *
 * 그래서 역할 축은 이 파일에만 적는다. 메뉴는 {@link buildMenuGroups} 로, 라우터는
 * {@link allowFor} 로 **둘 다 여기서 파생**시킨다. 어느 쪽도 자기 역할 목록을 따로 갖지 않는다.
 *
 * <h3>메뉴에 없는 경로도 여기 담는다</h3>
 * 메뉴에서 파생만으로는 「메뉴에 없지만 주소로 도달하는 화면」(마킹·영상 상세·라벨링 캔버스·
 * 검수 상세·증강 결과·공지 상세/작성/수정)의 인가가 통째로 빠진다. 그 자리들은 `menu` 없이
 * 선언한다 — 인가는 있고 메뉴에는 없는 상태가 사양이다.
 *
 * <h3>여기 적지 않는 것</h3>
 * - **관리자 단기 유효창**: 역할과 별개 축이다. 노출·인가를 가르는 축은 역할이며 유효창은 그
 *   위에 가산된다(라우트 안쪽 게이트). 유효창을 노출 조건으로 쓰면 들어갈 길 자체가 사라진다.
 * - **채널**: 내부/포털 격리는 `ChannelGuard` 가 별도로 판정한다. 이 표는 내부 채널 전용이라
 *   포털 역할이 등장하지 않는다.
 * - **역할 포함 관계**: 관리자가 검수자 자리에 들어가는 것은 `@/lib/authz` 가 판정한다. 여기
 *   `allow` 에는 「그 자리가 요구하는 역할」만 적고 상위 역할을 덧붙이지 않는다.
 *
 * @design NAV-001
 * @design SHELL-001
 * @design ROLE-004
 * @design ADR-055
 */

/** 그 자리가 요구하는 역할이 없는 경로의 판정값 — 빈 목록은 「아무에게도 열려 있지 않다」다. */
const DENY_ALL: readonly Role[] = Object.freeze([]);

/** 내부 채널에 로그인한 누구나(검수자·작업자). 관리자는 계층으로 검수자를 물려받아 함께 통과한다. */
const INTERNAL_ALL: readonly Role[] = Object.freeze([Role.REVIEWER, Role.WORKER]);
/** 검수자 자리 — 관리자는 계층으로 통과한다(여기에 관리자를 덧붙이지 않는다). */
const REVIEWER_ONLY: readonly Role[] = Object.freeze([Role.REVIEWER]);
/** 관리자 전용 — 검수자는 계층의 아래쪽이라 여기에 닿지 않는다. [@design ROLE-004] */
const ADMIN_ONLY: readonly Role[] = Object.freeze([Role.ADMIN]);

/** 메뉴 항목이 놓이는 자리. 없으면 「메뉴에 없지만 주소로 도달하는 경로」다. */
export interface RouteMenuPlacement {
  /** 그룹 헤더 라벨. {@link MENU_GROUP_ORDER} 에 있는 값이어야 한다. */
  group: string;
  /** 메뉴에 보이는 항목 라벨. */
  label: string;
}

export interface RouteAccessEntry {
  /** 절대 경로 — 메뉴와 라우터가 공유하는 **결합 키**다. 라우트 파라미터는 그대로 적는다. */
  path: string;
  /** 그 자리가 요구하는 역할. */
  allow: readonly Role[];
  /** 메뉴에 노출되는 자리면 지정. */
  menu?: RouteMenuPlacement;
  /**
   * 빌드 설정으로 노출·등록이 갈리는 자리.
   *
   * 그 자리(메뉴)와 주소(라우트)가 **같은 판정**을 쓰게 하려고 표식만 두고, 실제 판정값은
   * 소비처가 넘긴다({@link buildMenuGroups} 의 `devUploadEnabled`). 이 파일이 토글을 직접
   * 읽으면 모듈 최상단 부수효과가 되어 시험이 회차마다 다른 값을 보게 된다.
   */
  toggle?: 'devUpload';
}

/** 메뉴 그룹 순서 — 내비게이션 정의가 정한 순서다. [@design NAV-001] */
export const MENU_GROUP_ORDER: readonly string[] = Object.freeze([
  '대시보드',
  '영상',
  '작업',
  '데이터',
  '통계',
  '게시판',
  '관리',
  '관리자',
]);

/**
 * 내부 채널 전 경로의 접근 선언.
 *
 * 순서가 곧 그룹 안의 항목 순서다 — 내비게이션 정의가 정한 차례대로 적는다.
 */
export const INTERNAL_ROUTE_ACCESS: readonly RouteAccessEntry[] = Object.freeze([
  // ── 대시보드 ────────────────────────────────────────────────────────────
  { path: '/dashboard', allow: INTERNAL_ALL, menu: { group: '대시보드', label: '대시보드' } },

  // ── 영상 ────────────────────────────────────────────────────────────────
  // 영상 처리 현황과 그 하위 영상 상세는 배치 상태를 보는 데 그치지 않고 재시도·건너뛰기·
  // 재수행 같은 **운영 조치**를 제공하는 자리라 검수자 전용이다. 파이프라인을 다시 돌리는 것은
  // 라벨 수정·검수 제출을 맡는 작업자의 역할 축이 아니다.
  { path: '/video/status', allow: REVIEWER_ONLY, menu: { group: '영상', label: '영상 처리 현황' } },
  // 메뉴 미노출 — 목록 행 클릭으로 진입한다.
  { path: '/video/:id', allow: REVIEWER_ONLY },
  // 메뉴 미노출 — 같은 「영상」 개념이지만 작업자가 들어가는 화면이라 검수자 전용이 아니다.
  { path: '/marking/:rawSn', allow: INTERNAL_ALL },

  // ── 작업 ────────────────────────────────────────────────────────────────
  { path: '/task', allow: INTERNAL_ALL, menu: { group: '작업', label: '작업 목록' } },
  { path: '/review/pending', allow: REVIEWER_ONLY, menu: { group: '작업', label: '검수 목록' } },
  // 메뉴 미노출 — `/review/pending` 과 같은 화면의 별칭 주소다.
  { path: '/review', allow: REVIEWER_ONLY },
  { path: '/review/:id', allow: REVIEWER_ONLY },
  // 메뉴 미노출 — 작업 진입 후 열리는 풀스크린 캔버스(셸 밖에서 직접 매칭된다).
  { path: '/label/:id', allow: INTERNAL_ALL },

  // ── 데이터 ──────────────────────────────────────────────────────────────
  { path: '/augment/request', allow: REVIEWER_ONLY, menu: { group: '데이터', label: '증강 요청' } },
  // 메뉴 미노출 — `/augment/request` 별칭 + 증강 작업 카드 클릭 진입.
  { path: '/augment', allow: REVIEWER_ONLY },
  { path: '/augment/result/:rawSn', allow: REVIEWER_ONLY },

  // ── 통계 ────────────────────────────────────────────────────────────────
  { path: '/stat/worker', allow: INTERNAL_ALL, menu: { group: '통계', label: '작업자 통계' } },
  { path: '/stat/overall', allow: REVIEWER_ONLY, menu: { group: '통계', label: '전체 구축 현황' } },
  // 메뉴 미노출 — `/stat/worker` 별칭 주소다.
  { path: '/stat', allow: INTERNAL_ALL },

  // ── 게시판 ──────────────────────────────────────────────────────────────
  { path: '/notice', allow: INTERNAL_ALL, menu: { group: '게시판', label: '게시판' } },
  // 메뉴 미노출 — 작성·수정은 검수자만 진입한다(목록·상세는 작업자도 본다).
  { path: '/notice/new', allow: REVIEWER_ONLY },
  { path: '/notice/:id', allow: INTERNAL_ALL },
  { path: '/notice/:id/edit', allow: REVIEWER_ONLY },

  // ── 관리 ────────────────────────────────────────────────────────────────
  { path: '/manage/settings', allow: REVIEWER_ONLY, menu: { group: '관리', label: '시스템 설정' } },
  { path: '/manage/labels', allow: REVIEWER_ONLY, menu: { group: '관리', label: '라벨 관리' } },
  { path: '/manage/presets', allow: REVIEWER_ONLY, menu: { group: '관리', label: '프리셋 관리' } },
  {
    path: '/manage/deident-reports',
    allow: REVIEWER_ONLY,
    menu: { group: '관리', label: '비식별 신고' },
  },
  {
    path: '/manage/event-types',
    allow: REVIEWER_ONLY,
    menu: { group: '관리', label: '이벤트유형 관리' },
  },
  // 메뉴 미노출 — 「관리」 하위의 미구현 자리를 받는 자리표시 경로다.
  { path: '/manage/*', allow: REVIEWER_ONLY },

  // ── 관리자 ──────────────────────────────────────────────────────────────
  // 관리자 역할 **위에** 관리자 패스워드로 연 단기 유효창이 가산되는 화면들이다. 유효창은
  // 여기 적지 않는다 — 노출을 가르는 축은 역할이다.
  { path: '/admin/users', allow: ADMIN_ONLY, menu: { group: '관리자', label: '사용자 관리' } },
  {
    path: '/admin/endpoints',
    allow: ADMIN_ONLY,
    menu: { group: '관리자', label: '연동 서버 주소' },
  },
  {
    path: '/admin/imports',
    allow: ADMIN_ONLY,
    menu: { group: '관리자', label: '산출물 가져오기' },
  },
  // 빌드 설정으로 노출이 갈린다 — 그 자리(메뉴)와 주소(라우트)가 같은 판정을 쓴다.
  {
    path: '/admin/uploads',
    allow: ADMIN_ONLY,
    menu: { group: '관리자', label: '파일 업로드' },
    toggle: 'devUpload',
  },
  { path: '/admin/password', allow: ADMIN_ONLY, menu: { group: '관리자', label: '패스워드 교체' } },
  {
    path: '/admin/maintenance',
    allow: ADMIN_ONLY,
    menu: { group: '관리자', label: '위험 액션' },
  },
  // 메뉴 미노출 — 진입(게이트) 화면이다. 눌러서 가는 곳이 아니라 유효창이 없을 때 대신 열리는
  // 자리라 메뉴에 두면 확인을 마친 사용자가 아무 일도 일어나지 않는 화면으로 되돌아간다.
  { path: '/admin', allow: ADMIN_ONLY },
]);

const BY_PATH: ReadonlyMap<string, RouteAccessEntry> = new Map(
  INTERNAL_ROUTE_ACCESS.map((e) => [e.path, e]),
);

/**
 * 그 경로가 요구하는 역할. 라우터·메뉴가 공통으로 부르는 **유일한 판정 창구**다.
 *
 * 선언에 없는 경로는 {@link DENY_ALL}(빈 목록)을 돌려준다 — 오타 하나가 전면 개방으로
 * 이어지지 않게 fail-closed 로 둔다. 다만 조용히 잠기면 그것대로 발견이 늦으므로, 선언 누락은
 * 정적 가드(`menuRouteAccessSingleSource`)가 별도로 잡는다.
 *
 * 반환값은 **선언에 있는 그 객체 자체**다(매번 새 배열을 만들지 않는다). 라우트가 이 함수를
 * 실제로 통해 값을 얻었는지 참조 동일성으로 확인할 수 있어야 하기 때문이다.
 */
export function allowFor(path: string): readonly Role[] {
  return BY_PATH.get(path)?.allow ?? DENY_ALL;
}

/** 선언에 그 경로가 있는가. 정적 가드가 「라우터가 쓰는 경로가 전부 선언돼 있는가」를 볼 때 쓴다. */
export function isDeclaredPath(path: string): boolean {
  return BY_PATH.has(path);
}

export interface MenuItem {
  label: string;
  path: string;
  allow: readonly Role[];
}

export interface MenuGroup {
  group: string;
  items: MenuItem[];
}

export interface BuildMenuOptions {
  /** 빌드 설정으로 갈리는 항목(`toggle: 'devUpload'`)을 포함할지. 라우트와 같은 판정값을 넘긴다. */
  devUploadEnabled: boolean;
}

/**
 * 선언에서 좌측 메뉴 트리를 만든다. **호출마다 새 배열을 만든다** — 모듈 스코프 가변 배열에
 * 항목을 밀어 넣던 구조가 아니라, 같은 입력이면 같은 결과가 나오는 순수 파생이다.
 *
 * 항목이 하나도 없는 그룹은 아예 만들지 않는다(그룹 헤더만 남는 자리를 만들지 않기 위해서다).
 * 역할에 따른 가림은 여기서 하지 않는다 — 렌더 시점에 `roleSatisfiesAny` 로 판정한다.
 */
export function buildMenuGroups({ devUploadEnabled }: BuildMenuOptions): MenuGroup[] {
  const groups = new Map<string, MenuGroup>();
  for (const entry of INTERNAL_ROUTE_ACCESS) {
    if (!entry.menu) continue;
    if (entry.toggle === 'devUpload' && !devUploadEnabled) continue;
    const existing = groups.get(entry.menu.group);
    const group = existing ?? { group: entry.menu.group, items: [] };
    if (!existing) groups.set(group.group, group);
    group.items.push({ label: entry.menu.label, path: entry.path, allow: entry.allow });
  }
  return MENU_GROUP_ORDER.map((name) => groups.get(name)).filter(
    (g): g is MenuGroup => g !== undefined,
  );
}
