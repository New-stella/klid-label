import { ReactNode, Suspense } from 'react';
import { Navigate, createBrowserRouter } from 'react-router-dom';
import type { RouteObject } from 'react-router-dom';

import { AppErrorPage } from '@/components/common/AppErrorPage';
import { ForbiddenPage } from '@/components/common/ForbiddenPage';
import { Spinner } from '@/components/common/Spinner';
import { AppLayout } from '@/components/layout/AppLayout';
import { PortalLayout } from '@/components/layout/PortalLayout';
import { SessionIngressPage } from '@/features/auth/SessionIngressPage';
import { Role } from '@/lib/api/types';
import { IS_PORTAL_CHANNEL_BUILD } from '@/lib/buildChannel';
import { isDevLoginEnabled } from '@/lib/devLogin';
import { isDevUploadEnabled } from '@/lib/devUpload';
import { resolveRouterBasename } from '@/lib/remoteMount';
import { allowFor } from '@/lib/routeAccess';

import { AdminSessionGuard } from './adminSessionGuard';
import { AuthenticatedGuard, ChannelGuard, RoleGuard } from './guards';
import { lazyWithRetry } from './lazyWithRetry';

// Phase 3 — 영상 도메인 + 대시보드 lazy 로드 (코드 스플리팅)
const VideoListPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/VideoListPage').then((m) => ({ default: m.VideoListPage })),
);
const VideoDetailPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/VideoDetailPage').then((m) => ({ default: m.VideoDetailPage })),
);
const DashboardPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
);

// Phase 4 — 작업 배정 + 사용자 관리 + 시스템 설정 lazy 로드
const TaskListPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/TaskListPage').then((m) => ({ default: m.TaskListPage })),
);
const UserManagePage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/manage/UserManagePage').then((m) => ({ default: m.UserManagePage })),
);

// 관리자 페이지 — 진입 게이트 + 관리 기능 화면. [@design SCREEN-040~043] [@design NAV-001]
const AdminGatePage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/admin/AdminGatePage').then((m) => ({ default: m.AdminGatePage })),
);
const AdminPasswordPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/admin/AdminPasswordPage').then((m) => ({ default: m.AdminPasswordPage })),
);
const AdminEndpointsPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/admin/AdminEndpointsPage').then((m) => ({ default: m.AdminEndpointsPage })),
);
const AdminMaintenancePage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/admin/AdminMaintenancePage').then((m) => ({
    default: m.AdminMaintenancePage,
  })),
);
const SystemSettingsPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/manage/SystemSettingsPage').then((m) => ({ default: m.SystemSettingsPage })),
);

// Phase 5 — 라벨링 캔버스 lazy 로드 (konva 분리)
const LabelingPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/label/LabelingPage').then((m) => ({ default: m.LabelingPage })),
);

// Phase 9 — 검수 워크플로우 lazy 로드
const ReviewListPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/ReviewListPage').then((m) => ({ default: m.ReviewListPage })),
);
const ReviewPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/ReviewPage').then((m) => ({ default: m.ReviewPage })),
);
// Phase 10 — 데이터 증강 lazy 로드
const AugmentRequestPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/AugmentRequestPage').then((m) => ({
    default: m.AugmentRequestPage,
  })),
);
const AugmentResultPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/AugmentResultPage').then((m) => ({
    default: m.AugmentResultPage,
  })),
);

// Phase 11 — 포털 채널 (데이터마트 영상 선택 + 간편 라벨링, ADR-013) lazy 로드
const PortalHomePage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/portal/PortalHomePage').then((m) => ({ default: m.PortalHomePage })),
);
const PortalLabelingPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/portal/PortalLabelingPage').then((m) => ({ default: m.PortalLabelingPage })),
);
// Phase 5 — 포털 업로드 화면 (이미지 다중 업로드 + 영상 TUS + 자산 목록/삭제) lazy 로드
const PortalUploadPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/portal/PortalUploadPage').then((m) => ({ default: m.PortalUploadPage })),
);
// Phase 6 — 포털 업로드 자산 라벨링 화면 (CanvasShell 조립 + export/원본 다운로드) lazy 로드
const PortalUploadLabelingPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/portal/PortalUploadLabelingPage').then((m) => ({
    default: m.PortalUploadLabelingPage,
  })),
);

// Phase 12 — 통계 + 프리셋 lazy 로드 (recharts 별도 청크)
const WorkerStatPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/WorkerStatPage').then((m) => ({ default: m.WorkerStatPage })),
);
const OverallStatPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/OverallStatPage').then((m) => ({ default: m.OverallStatPage })),
);
const PresetListPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/manage/PresetListPage').then((m) => ({ default: m.PresetListPage })),
);
// Phase 3 — 라벨 마스터(클래스) 관리 (REVIEWER 전용) lazy 로드
const LabelMasterManagePage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/manage/LabelMasterManagePage').then((m) => ({
    default: m.LabelMasterManagePage,
  })),
);
// 이벤트유형 관리 (REVIEWER 전용) lazy 로드 — 관제 인입 자동등록분의 표시명·수집여부 정정.
const EventTypeManagePage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/manage/EventTypeManagePage').then((m) => ({
    default: m.EventTypeManagePage,
  })),
);
// G-1 — 비식별 신고 관리 (REVIEWER 전용) lazy 로드
const DeidentReportListPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/manage/DeidentReportListPage').then((m) => ({
    default: m.DeidentReportListPage,
  })),
);
// 산출물 가져오기 (REVIEWER 전용) lazy 로드 — 검사·적재·분류 대응·이관 이력을 한 화면에서 밟는다.
const ImportPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/manage/ImportPage').then((m) => ({
    default: m.ImportPage,
  })),
);

// V2.0 Phase 7 — 마킹 화면 lazy 로드
const MarkingPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/MarkingPage').then((m) => ({ default: m.MarkingPage })),
);

// Phase 3 — 게시판(공지) 목록/상세 lazy 로드
const NoticeListPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/NoticeListPage').then((m) => ({ default: m.NoticeListPage })),
);
const NoticeDetailPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/NoticeDetailPage').then((m) => ({ default: m.NoticeDetailPage })),
);
// 공지 작성/수정 — 모달이 아니라 전용 화면이라 각각 직접 진입 가능한 URL 을 갖는다.
const NoticeCreatePage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/NoticeCreatePage').then((m) => ({ default: m.NoticeCreatePage })),
);
const NoticeEditPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/NoticeEditPage').then((m) => ({ default: m.NoticeEditPage })),
);

// Phase 2 — 권한 자가 부여 화면 (role 미부여 사용자 진입점) lazy 로드
const RoleClaimPage = /* @__PURE__ */ lazyWithRetry(() =>
  import('@/pages/RoleClaimPage').then((m) => ({ default: m.RoleClaimPage })),
);

function PageFallback() {
  return (
    <div className="flex h-full items-center justify-center py-10">
      <Spinner label="페이지 로딩" />
    </div>
  );
}

function withSuspense(node: ReactNode) {
  return <Suspense fallback={<PageFallback />}>{node}</Suspense>;
}

function PlaceholderPage({ title }: { title: string }) {
  return (
    <div className="rounded bg-white p-6 shadow-sm">
      <h1 className="text-page-title text-primary">{title}</h1>
      <p className="mt-2 text-body text-neutral">Phase 4+에서 구현 예정 화면입니다.</p>
    </div>
  );
}

// ★내부 채널의 허용 목록은 이 파일이 갖지 않는다 — `@/lib/routeAccess` 선언 한 곳에서
//   `allowFor(경로)` 로 가져온다. 좌측 메뉴도 같은 선언에서 파생하므로 두 축이 갈릴 수 없다.
//   구 구현은 여기 세 상수(internalAllRoles·internalReviewerOnly·internalAdminOnly)를 두고
//   메뉴가 항목마다 `allow` 를 따로 들어, 두 파일의 주석이 「같은 조건이어야 한다」고 서로를
//   향해 경고만 했다. 되살리지 말 것.
//   ⚠ 그 선언에는 「그 자리가 요구하는 역할」만 적는다 — 상위 역할은 `RoleGuard` 가 계층으로
//     통과시키므로 관리자를 검수자 자리에 덧붙이지 않는다. [@design ROLE-004] [@design ADR-055]
//
// 포털은 채널 자체가 달라 내부 선언의 대상이 아니다(여기 그대로 둔다).
const portalOnly = [Role.PORTAL_USER];

function InternalRoute({ allow, children }: { allow: readonly Role[]; children: ReactNode }) {
  return (
    <ChannelGuard channel="INTERNAL">
      <RoleGuard allow={allow}>{children}</RoleGuard>
    </ChannelGuard>
  );
}

function PortalRoute({ children }: { children: ReactNode }) {
  return (
    <ChannelGuard channel="PORTAL">
      <RoleGuard allow={portalOnly}>{children}</RoleGuard>
    </ChannelGuard>
  );
}

/**
 * 관리자 페이지 라우트 — 역할 가드 **안쪽**에 진입 게이트를 얹는다. [@design SCREEN-040]
 * [@design ROLE-004]
 *
 * ★순서가 계약이다. 유효창은 인가를 대체하지 않고 **가산**되므로 역할 가드가 바깥에 있어야
 * 관리자가 아닌 사용자는 패스워드를 알더라도 여기에 닿지 못한다. 뒤집으면 「패스워드를 아는
 * 사람이 관리자」가 되어 역할로 가른다는 이 구성의 전제가 무너진다.
 *
 * ★역할 조건을 자기 안에 박지 않고 **호출자가 `allowFor(경로)` 로 넘긴다**. 그래야 좌측 메뉴와
 *   같은 선언에서 나온 값인지 참조 동일성으로 확인할 수 있다 — 여기 상수를 다시 두면 그 확인이
 *   불가능해지고 「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 되살아난다.
 * ⚠ 진입 화면(`/admin`) 자신에게는 쓰지 않는다 — 유효창이 없을 때 자기 자신으로 무한히 되돌아간다.
 */
function AdminRoute({ allow, children }: { allow: readonly Role[]; children: ReactNode }) {
  return (
    <InternalRoute allow={allow}>
      <AdminSessionGuard>{children}</AdminSessionGuard>
    </InternalRoute>
  );
}

// [@design SCREEN-004]
// DEV 빌드 또는 런타임 설정 `VITE_DEV_LOGIN_ENABLED=true` 일 때만 `/dev/login` 라우트를 노출.
// 관제서버 미기동 폐쇄망 bring-up 시 운영 빌드에서도 켤 수 있다 (기본 OFF, fail-closed).
//
// ★★ **화면 코드는 산출물에 실려 나간다 — 접히지 않는다.** 구 주석은 *"플래그가 false/미설정인
//    prod 빌드에서는 if 블록 전체가 dead-code 로 제거되어 DevLoginPage 청크 자체가 산출물에
//    포함되지 않는다"* 라고 적고 있었는데 **사실이 아니다.** 네 형상(기본 · 토글 ON · control ·
//    portal) **전부에서 `DevLoginPage-*.js` 청크가 1건**으로 실측됐다. 그 주장을 아무도 산출물로
//    확인하지 않아 오래 살아남았다.
//
// ★ **왜 안 접히나 — 판정의 *형태* 때문이다.** `isDevLoginEnabled()` 는 런타임 설정
//   (`/etc/klid/frontend.env` → `klid-config.js`)까지 읽는 **함수 호출**이라 번들러가 정적으로
//   접을 수 없다. 산출 시점에 굳는 값(`import.meta.env.DEV` 같은)만 접힌다.
//   ⚠ **접히게 하려고 이 판정을 산출 시점 상수로 바꾸지 말 것.** 이 토글의 존재 이유가
//     「재빌드 없이 현장에서 끌 수 있어야 한다」이고, 상수로 바꾸면 그 요구가 통째로 사라진다
//     (근거 전문은 `lib/devLogin.ts` — *"명시적으로 껐다가 가장 강한 신호"*).
//     **접히지 않는 것은 그 요구의 대가이지 결함이 아니다.**
//
// ★ **그래서 실려 나가도 안전한 이유 — 도달이 3중으로 막혀 있다**(`SCREEN-004` 가 규정한 구조):
//     ① 프론트 — 이 판정이 거짓이면 아래 블록이 라우트를 **등록하지 않는다**(청크는 있어도
//        그 주소로 갈 수 없다).
//     ② 백엔드 — `authoring.dev.login.enabled` 기본값이 `false` 이고 `DevTokenController` 가
//        `@ConditionalOnProperty(havingValue = "true")` 라, 꺼져 있으면 `/v1/dev/tokens` 가 **404**.
//     ③ 운영 계열 — `DevToggleProfileGuard` 가 `prd`·`stg` 에서 그 토글이 켜져 있으면
//        `@PostConstruct` 에서 `IllegalStateException` 으로 **기동 자체를 거부**한다.
//
// ⚠ **`features/auth/devHostStub` 의 게이트와 혼동하지 말 것 — 그쪽은 실제로 접힌다.** 거기는
//   `import.meta.env.DEV`(산출 시점에 굳는 값)를 **먼저** 보므로 네 형상 모두 **0건**이다.
//   두 게이트가 다르게 동작하는 이유는 「dev 전용이냐」가 아니라 **판정이 무엇을 읽느냐**다.
const devOnlyRoutes: Array<{ path: string; element: ReactNode }> = [];
if (isDevLoginEnabled()) {
  const DevLoginPage = /* @__PURE__ */ lazyWithRetry(() =>
    import('@/features/auth/DevLoginPage').then((m) => ({ default: m.DevLoginPage })),
  );
  devOnlyRoutes.push({
    path: '/dev/login',
    element: withSuspense(<DevLoginPage />),
  });
}

// 파일 업로드 페이지(`/admin/uploads`) — 관리자 페이지 소속. [@design SCREEN-027] [@design NAV-001]
// DEV 빌드 또는 빌드타임 플래그 VITE_DEV_UPLOAD_ENABLED=true 일 때만 라우트를 노출 (isDevLoginEnabled 와 대칭).
// ★★ **여기도 마찬가지로 화면 코드는 실려 나간다.** 구 주석은 *"플래그 false/미설정인 prod
//    빌드에서는 if 블록 전체가 dead-code 로 제거되어 DevAutolabelTestPage 청크 자체가 산출물에
//    포함되지 않는다"* 라고 적고 있었으나 **사실이 아니다** — 기본 · 토글 ON · control 세 형상
//    모두에서 청크가 **1건**으로 실측됐다. 이유는 위 dev 로그인 블록과 같다
//    (`isDevUploadEnabled()` 도 런타임 설정을 읽는 함수라 접히지 않는다).
// ⚠ **포털 산출물에서만 0건인데, 그것은 이 플래그 덕이 아니다** — 아래 조건 **앞**에 놓인
//    채널 축(`IS_PORTAL_CHANNEL_BUILD`, 산출 시점에 굳는 값)이 접히기 때문이다. 그 축을 빼면
//    관리자 화면 청크가 포털 산출물에 그대로 실린다(실측으로 확인한 실제 누출이다).
// 실제 게이팅은 BE DEV_UPLOAD_ENABLED 런타임 토글이 결정(라우트만 존재,
// BE off 면 /v1/dev/upload 호출 시 차단). UI 노출은 REVIEWER + 관리자 유효창으로 제한한다.
//
// ⚠ **화면 주소만 옮겼고 BE API 경로(`/v1/dev/upload`)는 그대로다** — 이 변경의 축은 화면 배치이지
//    창구 개명이 아니다.
const devUploadRoutes: Array<{ path: string; element: ReactNode }> = [];
// ★ 채널 축을 **앞에** 둔다 — 이 화면은 관리자 페이지(내부 채널) 소속이라 포털 채널 산출물에
//   들어갈 이유가 없다. `isDevUploadEnabled()` 는 실행 중에 판정하는 함수라 접히지 않으므로,
//   산출 시점에 접히는 `IS_PORTAL_CHANNEL_BUILD` 를 앞에 두어야 포털 빌드에서 이 블록이 통째로
//   사라진다(안 그러면 화면 청크가 반대 채널 산출물에 그대로 실린다 — 실측으로 확인했다).
if (!IS_PORTAL_CHANNEL_BUILD && isDevUploadEnabled()) {
  const DevAutolabelTestPage = /* @__PURE__ */ lazyWithRetry(() =>
    import('@/pages/dev/DevAutolabelTestPage').then((m) => ({
      default: m.DevAutolabelTestPage,
    })),
  );
  devUploadRoutes.push({
    path: 'uploads',
    element: (
      <AdminRoute allow={allowFor('/admin/uploads')}>
        {withSuspense(<DevAutolabelTestPage />)}
      </AdminRoute>
    ),
  });
}

// [@design INT-013]
/**
 * 내부(관제) 채널 라우트 — **포털 채널 산출물에는 들어가지 않는다.**
 *
 * 설계가 「공통 소스는 한 벌로 두고 산출만 채널별로 가른다 … 반대 채널 화면이 산출물에 섞이지
 * 않는다」고 규정한 그 갈림이 여기다. 조건이 `IS_PORTAL_CHANNEL_BUILD`(산출 시점에 접히는 형태)
 * 인 것이 핵심이다 — 실행 중에 판정하는 함수(`isPortalEmbedChannel()`)를 쓰면 이동은 막히지만
 * 화면 코드는 번들에 그대로 남아 요구가 절반만 달성된다(근거 전문은 그 상수 주석).
 *
 * ⚠ `/role-claim` 이 여기 있는 근거: 그 화면으로 보내는 유일한 지점이 `router/guards.tsx` 의
 *   `if (!claims.role && claims.channel === 'INTERNAL')` 이고, 포털 채널 빌드는 그 자리에서
 *   이동 대신 제자리 안내를 그린다. 포털 사용자는 발급 시점에 역할이 있어 이 흐름을 타지 않는다.
 *
 * ⚠ 공용 진입 경로(`/ingress` · 개발용 로그인 · 오류 화면)는 여기 넣지 않는다 — 아래 `routes` 에
 *   그대로 남아 두 채널 모두에 실린다. 특히 `/ingress` 는 채널 클레임으로 갈라 보내는 자리라
 *   포털 채널에서도 반드시 있어야 한다.
 */
const internalRoutes: RouteObject[] = IS_PORTAL_CHANNEL_BUILD
  ? []
  : [
      // Phase 2 — 권한 자가 부여 화면. 인증만 통과하면 진입 가능 (role 무관, RoleGuard 미사용).
      {
        path: '/role-claim',
        element: <AuthenticatedGuard>{withSuspense(<RoleClaimPage />)}</AuthenticatedGuard>,
      },

      // 라벨링 화면은 풀스크린 다크 UI — AppLayout(LNB/GNB) 밖에서 직접 매칭
      // 인증 가드(InternalRoute)는 그대로 유지
      {
        path: '/label/:id',
        element: (
          <InternalRoute allow={allowFor('/label/:id')}>{withSuspense(<LabelingPage />)}</InternalRoute>
        ),
        errorElement: <AppErrorPage status={500} />,
      },

      // INTERNAL 채널 (저작도구 내부)
      {
        path: '/',
        element: <AppLayout />,
        errorElement: <AppErrorPage status={500} />,
        children: [
          // 루트 → /dashboard redirect (mock 정합)
          { index: true, element: <Navigate to="/dashboard" replace /> },
          {
            path: 'video',
            children: [
              // 부모 경로 직접 진입(북마크·주소 입력·뒤로가기) 시 빈 화면이 뜨지 않도록 대표 하위로 보낸다.
              // index 라우트가 없으면 pathless 부모가 leaf 로 매칭되어 <Outlet/> 이 null 을 그린다.
              { index: true, element: <Navigate to="/video/status" replace /> },
              // [@design NAV-001] [@design SCREEN-008] [@design SCREEN-009]
              // 영상 처리 현황·영상 상세는 재시도·건너뛰기·재수행 같은 **운영 조치**를 제공하는
              // 자리라 REVIEWER 전용이다(라벨 수정·검수 제출을 맡는 WORKER 의 역할 축이 아니다).
              // ★LNB 와 이 가드는 `@/lib/routeAccess` 의 **같은 선언**을 읽는다 — 두 곳에 조건을
              //   적어 두고 「같아야 한다」고 주석으로만 당부하던 구조를 없앴다.
              // ⚠ 같은 「영상」 개념에 속하는 마킹(`marking/:rawSn`)은 이 제한 대상이 **아니다** —
              //   WORKER 가 들어가는 화면이라 internalAllRoles 그대로 둔다.
              {
                path: 'status',
                element: (
                  <InternalRoute allow={allowFor('/video/status')}>
                    {withSuspense(<VideoListPage />)}
                  </InternalRoute>
                ),
              },
              {
                path: ':id',
                element: (
                  <InternalRoute allow={allowFor('/video/:id')}>
                    {withSuspense(<VideoDetailPage />)}
                  </InternalRoute>
                ),
              },
            ],
          },
          {
            path: 'dashboard',
            element: (
              <InternalRoute allow={allowFor('/dashboard')}>
                {withSuspense(<DashboardPage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'task',
            children: [
              {
                index: true,
                element: (
                  <InternalRoute allow={allowFor('/task')}>
                    {withSuspense(<TaskListPage />)}
                  </InternalRoute>
                ),
              },
            ],
          },
          {
            path: 'marking/:rawSn',
            element: (
              <InternalRoute allow={allowFor('/marking/:rawSn')}>
                {withSuspense(<MarkingPage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'review',
            children: [
              {
                index: true,
                element: (
                  <InternalRoute allow={allowFor('/review')}>
                    {withSuspense(<ReviewListPage />)}
                  </InternalRoute>
                ),
              },
              {
                // mock 정합 alias — /review/pending == /review
                path: 'pending',
                element: (
                  <InternalRoute allow={allowFor('/review/pending')}>
                    {withSuspense(<ReviewListPage />)}
                  </InternalRoute>
                ),
              },
              {
                path: ':id',
                element: (
                  <InternalRoute allow={allowFor('/review/:id')}>
                    {withSuspense(<ReviewPage />)}
                  </InternalRoute>
                ),
              },
            ],
          },
          {
            path: 'stat',
            children: [
              {
                index: true,
                element: (
                  <InternalRoute allow={allowFor('/stat')}>
                    {withSuspense(<WorkerStatPage />)}
                  </InternalRoute>
                ),
              },
              {
                // mock 정합 alias — /stat/worker == /stat
                path: 'worker',
                element: (
                  <InternalRoute allow={allowFor('/stat/worker')}>
                    {withSuspense(<WorkerStatPage />)}
                  </InternalRoute>
                ),
              },
              {
                path: 'overall',
                element: (
                  <InternalRoute allow={allowFor('/stat/overall')}>
                    {withSuspense(<OverallStatPage />)}
                  </InternalRoute>
                ),
              },
            ],
          },
          {
            path: 'augment',
            children: [
              {
                index: true,
                element: (
                  <InternalRoute allow={allowFor('/augment')}>
                    {withSuspense(<AugmentRequestPage />)}
                  </InternalRoute>
                ),
              },
              {
                // mock 정합 alias — /augment/request == /augment
                path: 'request',
                element: (
                  <InternalRoute allow={allowFor('/augment/request')}>
                    {withSuspense(<AugmentRequestPage />)}
                  </InternalRoute>
                ),
              },
              {
                path: 'result/:rawSn',
                element: (
                  <InternalRoute allow={allowFor('/augment/result/:rawSn')}>
                    {withSuspense(<AugmentResultPage />)}
                  </InternalRoute>
                ),
              },
            ],
          },
          {
            path: 'manage',
            children: [
              // `path: '*'` 는 남은 경로가 빈 문자열일 때 매칭되지 않아 `/manage` 를 못 받는다
              // (실측: leaf 가 pathless 'manage' 로 잡혀 빈 화면). LNB 첫 항목으로 보낸다.
              // ⚠ 첫 항목이 「사용자 관리」에서 「시스템 설정」으로 바뀌었다 — 사용자 관리는 관리자
              //   페이지(`/admin/users`)로 옮겨갔고, 여기로 보내면 곧바로 진입 게이트로 튄다.
              { index: true, element: <Navigate to="/manage/settings" replace /> },
              {
                path: 'settings',
                element: (
                  <InternalRoute allow={allowFor('/manage/settings')}>
                    {withSuspense(<SystemSettingsPage />)}
                  </InternalRoute>
                ),
              },
              {
                path: 'presets',
                element: (
                  <InternalRoute allow={allowFor('/manage/presets')}>
                    {withSuspense(<PresetListPage />)}
                  </InternalRoute>
                ),
              },
              {
                path: 'labels',
                element: (
                  <InternalRoute allow={allowFor('/manage/labels')}>
                    {withSuspense(<LabelMasterManagePage />)}
                  </InternalRoute>
                ),
              },
              {
                path: 'event-types',
                element: (
                  <InternalRoute allow={allowFor('/manage/event-types')}>
                    {withSuspense(<EventTypeManagePage />)}
                  </InternalRoute>
                ),
              },
              {
                path: 'deident-reports',
                element: (
                  <InternalRoute allow={allowFor('/manage/deident-reports')}>
                    {withSuspense(<DeidentReportListPage />)}
                  </InternalRoute>
                ),
              },
              {
                // 구 주소 — 북마크·공유 링크를 새 자리로 잇는다. 화면은 「관리자」 소속
                // (`/admin/imports`)으로 옮겨갔고 여기서 그리지 않는다. [@design SCREEN-039]
                // ⚠ 여기에 역할 가드를 걸지 않는다 — 이동만 하는 자리이고, 인가 판정은 목적지
                //   라우트가 한다. 여기서 막으면 검수자가 「접근 거부」 대신 빈 화면을 본다.
                path: 'imports',
                element: <Navigate to="/admin/imports" replace />,
              },
              {
                path: '*',
                element: (
                  <InternalRoute allow={allowFor('/manage/*')}>
                    <PlaceholderPage title="관리" />
                  </InternalRoute>
                ),
              },
            ],
          },
          {
            path: 'notice',
            children: [
              {
                index: true,
                element: (
                  <InternalRoute allow={allowFor('/notice')}>
                    {withSuspense(<NoticeListPage />)}
                  </InternalRoute>
                ),
              },
              // 작성/수정은 REVIEWER 전용이다. ':id' 보다 먼저 두어 'new' 가 게시글 id 로
              // 해석되지 않게 한다(정적 세그먼트 우선 매칭에 더해 선언 순서로도 못 박는다).
              {
                path: 'new',
                element: (
                  <InternalRoute allow={allowFor('/notice/new')}>
                    {withSuspense(<NoticeCreatePage />)}
                  </InternalRoute>
                ),
              },
              {
                path: ':id',
                element: (
                  <InternalRoute allow={allowFor('/notice/:id')}>
                    {withSuspense(<NoticeDetailPage />)}
                  </InternalRoute>
                ),
              },
              {
                path: ':id/edit',
                element: (
                  <InternalRoute allow={allowFor('/notice/:id/edit')}>
                    {withSuspense(<NoticeEditPage />)}
                  </InternalRoute>
                ),
              },
            ],
          },
          {
            // 관리자 페이지 — 관리자 역할에게만 열리고, 그 위에 관리자 패스워드로 연 단기 유효창을
            // 함께 요구한다. [@design NAV-001] [@design ADR-046] [@design ROLE-004]
            path: 'admin',
            children: [
              {
                // 진입(게이트) 화면. ★여기에는 AdminSessionGuard 를 걸지 않는다 — 유효창이 없을 때
                //   자기 자신으로 무한히 되돌아간다. 역할 가드는 그대로 필요하다.
                index: true,
                element: (
                  <InternalRoute allow={allowFor('/admin')}>
                    {withSuspense(<AdminGatePage />)}
                  </InternalRoute>
                ),
              },
              {
                path: 'users',
                element: (
                  <AdminRoute allow={allowFor('/admin/users')}>
                    {withSuspense(<UserManagePage />)}
                  </AdminRoute>
                ),
              },
              {
                path: 'endpoints',
                element: (
                  <AdminRoute allow={allowFor('/admin/endpoints')}>
                    {withSuspense(<AdminEndpointsPage />)}
                  </AdminRoute>
                ),
              },
              {
                // 산출물 가져오기 — 구 주소 `/manage/imports` 에서 옮겨왔다. [@design SCREEN-039]
                // 서버가 적재 실행(`POST /v1/imports`)과 대응 저장·삭제(`/v1/import-mappings`)를
                // 관리자 전용으로 좁혔는데 화면이 검수자에게 열려 있어, 폴더 탐색·검사까지 정상
                // 진행한 뒤 **마지막 단계에서만 403** 을 받는 상태였다(그 화면에는 역할 참조가
                // 한 줄도 없어 사유 안내조차 없었다). 진입 자체를 관리자로 좁혀 해소한다.
                // ★좌측 메뉴와 이 가드는 `@/lib/routeAccess` 의 같은 선언을 읽는다.
                path: 'imports',
                element: (
                  <AdminRoute allow={allowFor('/admin/imports')}>
                    {withSuspense(<ImportPage />)}
                  </AdminRoute>
                ),
              },
              {
                path: 'password',
                element: (
                  <AdminRoute allow={allowFor('/admin/password')}>
                    {withSuspense(<AdminPasswordPage />)}
                  </AdminRoute>
                ),
              },
              {
                path: 'maintenance',
                element: (
                  <AdminRoute allow={allowFor('/admin/maintenance')}>
                    {withSuspense(<AdminMaintenancePage />)}
                  </AdminRoute>
                ),
              },
              // 파일 업로드(`/admin/uploads`) — DEV_UPLOAD_ENABLED 토글로 빌드 포함 여부가 갈린다.
              ...devUploadRoutes,
            ],
          },

          { path: '*', element: <AppErrorPage status={404} /> },
        ],
      },
    ];

// [@design INT-013]
/**
 * 포털 채널 라우트 — **관제 채널 산출물에는 들어가지 않는다.**
 *
 * 위 내부 라우트와 대칭이며 같은 상수로 갈린다.
 */
const portalRoutes: RouteObject[] = IS_PORTAL_CHANNEL_BUILD
  ? [

      // PORTAL 채널 — 별도 PortalLayout (LNB 없음, 모바일 친화)
      {
        path: '/portal',
        element: <PortalLayout />,
        errorElement: <AppErrorPage status={500} />,
        children: [
          {
            index: true,
            element: <PortalRoute>{withSuspense(<PortalHomePage />)}</PortalRoute>,
          },
          {
            path: 'label/:id',
            element: <PortalRoute>{withSuspense(<PortalLabelingPage />)}</PortalRoute>,
          },
          {
            path: 'uploads',
            element: <PortalRoute>{withSuspense(<PortalUploadPage />)}</PortalRoute>,
          },
          {
            path: 'uploads/:uldSn/label',
            element: <PortalRoute>{withSuspense(<PortalUploadLabelingPage />)}</PortalRoute>,
          },
        ],
      },
      // 마운트 경로의 뿌리(`/`) — Host 는 우리를 이 자리에 얹으므로 포털 홈으로 보낸다.
      // 관제 채널의 뿌리는 대시보드로 보내는 내부 라우트가 갖고 있고, 그 트리가 이 산출물에는
      // 없으므로 여기서 대신 받는다(없으면 뿌리 진입이 어느 라우트에도 걸리지 않는다).
      { path: '/', element: <Navigate to="/portal" replace /> },
      // 못 찾은 주소 — 관제 채널은 내부 트리 안의 `*` 가 받지만 그 트리가 없으므로 여기 둔다.
      { path: '*', element: <AppErrorPage status={404} /> },
    ]
  : [];

// 라우트 트리 — 아래 `createBrowserRouter` 의 유일한 소비처다.
// 별도 상수로 뽑은 것은 basename 옵션을 붙이면서 **배열 본문을 한 글자도 건드리지 않기** 위해서다.
const routes: RouteObject[] = [
  // 진입/공통 — Layout 없이 직접 매칭
  { path: '/ingress', element: <SessionIngressPage /> },
  { path: '/forbidden', element: <ForbiddenPage /> },
  ...devOnlyRoutes,
  // 채널별 라우트 — 둘 중 한쪽만 이 산출물에 실린다(위 두 상수).
  ...internalRoutes,
  ...portalRoutes,
];

// [@design INT-013]
// basename 은 빌드 채널이 정한다 — 포털 채널 산출물만 Host 마운트 경로 아래로 들어가고,
// 내부(관제) 채널은 `undefined` 라 지금 동작 그대로다. 값의 단일 지점은 `lib/remoteMount`.
export const router = createBrowserRouter(routes, { basename: resolveRouterBasename() });
