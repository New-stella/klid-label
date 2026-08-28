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
import { isDevLoginEnabled } from '@/lib/devLogin';
import { isDevUploadEnabled } from '@/lib/devUpload';
import { resolveRouterBasename } from '@/lib/remoteMount';

import { AdminSessionGuard } from './adminSessionGuard';
import { AuthenticatedGuard, ChannelGuard, RoleGuard } from './guards';
import { lazyWithRetry } from './lazyWithRetry';

// Phase 3 — 영상 도메인 + 대시보드 lazy 로드 (코드 스플리팅)
const VideoListPage = lazyWithRetry(() =>
  import('@/pages/VideoListPage').then((m) => ({ default: m.VideoListPage })),
);
const VideoDetailPage = lazyWithRetry(() =>
  import('@/pages/VideoDetailPage').then((m) => ({ default: m.VideoDetailPage })),
);
const DashboardPage = lazyWithRetry(() =>
  import('@/pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
);

// Phase 4 — 작업 배정 + 사용자 관리 + 시스템 설정 lazy 로드
const TaskListPage = lazyWithRetry(() =>
  import('@/pages/TaskListPage').then((m) => ({ default: m.TaskListPage })),
);
const UserManagePage = lazyWithRetry(() =>
  import('@/pages/manage/UserManagePage').then((m) => ({ default: m.UserManagePage })),
);

// 관리자 페이지 — 진입 게이트 + 관리 기능 화면. [@design SCREEN-040~043] [@design NAV-001]
const AdminGatePage = lazyWithRetry(() =>
  import('@/pages/admin/AdminGatePage').then((m) => ({ default: m.AdminGatePage })),
);
const AdminPasswordPage = lazyWithRetry(() =>
  import('@/pages/admin/AdminPasswordPage').then((m) => ({ default: m.AdminPasswordPage })),
);
const AdminEndpointsPage = lazyWithRetry(() =>
  import('@/pages/admin/AdminEndpointsPage').then((m) => ({ default: m.AdminEndpointsPage })),
);
const AdminMaintenancePage = lazyWithRetry(() =>
  import('@/pages/admin/AdminMaintenancePage').then((m) => ({
    default: m.AdminMaintenancePage,
  })),
);
const SystemSettingsPage = lazyWithRetry(() =>
  import('@/pages/manage/SystemSettingsPage').then((m) => ({ default: m.SystemSettingsPage })),
);

// Phase 5 — 라벨링 캔버스 lazy 로드 (konva 분리)
const LabelingPage = lazyWithRetry(() =>
  import('@/pages/label/LabelingPage').then((m) => ({ default: m.LabelingPage })),
);

// Phase 9 — 검수 워크플로우 lazy 로드
const ReviewListPage = lazyWithRetry(() =>
  import('@/pages/ReviewListPage').then((m) => ({ default: m.ReviewListPage })),
);
const ReviewPage = lazyWithRetry(() =>
  import('@/pages/ReviewPage').then((m) => ({ default: m.ReviewPage })),
);
// Phase 10 — 데이터 증강 lazy 로드
const AugmentRequestPage = lazyWithRetry(() =>
  import('@/pages/AugmentRequestPage').then((m) => ({
    default: m.AugmentRequestPage,
  })),
);
const AugmentResultPage = lazyWithRetry(() =>
  import('@/pages/AugmentResultPage').then((m) => ({
    default: m.AugmentResultPage,
  })),
);

// Phase 11 — 포털 채널 (데이터마트 영상 선택 + 간편 라벨링, ADR-013) lazy 로드
const PortalHomePage = lazyWithRetry(() =>
  import('@/pages/portal/PortalHomePage').then((m) => ({ default: m.PortalHomePage })),
);
const PortalLabelingPage = lazyWithRetry(() =>
  import('@/pages/portal/PortalLabelingPage').then((m) => ({ default: m.PortalLabelingPage })),
);
// Phase 5 — 포털 업로드 화면 (이미지 다중 업로드 + 영상 TUS + 자산 목록/삭제) lazy 로드
const PortalUploadPage = lazyWithRetry(() =>
  import('@/pages/portal/PortalUploadPage').then((m) => ({ default: m.PortalUploadPage })),
);
// Phase 6 — 포털 업로드 자산 라벨링 화면 (CanvasShell 조립 + export/원본 다운로드) lazy 로드
const PortalUploadLabelingPage = lazyWithRetry(() =>
  import('@/pages/portal/PortalUploadLabelingPage').then((m) => ({
    default: m.PortalUploadLabelingPage,
  })),
);

// Phase 12 — 통계 + 프리셋 lazy 로드 (recharts 별도 청크)
const WorkerStatPage = lazyWithRetry(() =>
  import('@/pages/WorkerStatPage').then((m) => ({ default: m.WorkerStatPage })),
);
const OverallStatPage = lazyWithRetry(() =>
  import('@/pages/OverallStatPage').then((m) => ({ default: m.OverallStatPage })),
);
const PresetListPage = lazyWithRetry(() =>
  import('@/pages/manage/PresetListPage').then((m) => ({ default: m.PresetListPage })),
);
// Phase 3 — 라벨 마스터(클래스) 관리 (REVIEWER 전용) lazy 로드
const LabelMasterManagePage = lazyWithRetry(() =>
  import('@/pages/manage/LabelMasterManagePage').then((m) => ({
    default: m.LabelMasterManagePage,
  })),
);
// 이벤트유형 관리 (REVIEWER 전용) lazy 로드 — 관제 인입 자동등록분의 표시명·수집여부 정정.
const EventTypeManagePage = lazyWithRetry(() =>
  import('@/pages/manage/EventTypeManagePage').then((m) => ({
    default: m.EventTypeManagePage,
  })),
);
// G-1 — 비식별 신고 관리 (REVIEWER 전용) lazy 로드
const DeidentReportListPage = lazyWithRetry(() =>
  import('@/pages/manage/DeidentReportListPage').then((m) => ({
    default: m.DeidentReportListPage,
  })),
);
// 산출물 가져오기 (REVIEWER 전용) lazy 로드 — 검사·적재·분류 대응·이관 이력을 한 화면에서 밟는다.
const ImportPage = lazyWithRetry(() =>
  import('@/pages/manage/ImportPage').then((m) => ({
    default: m.ImportPage,
  })),
);

// V2.0 Phase 7 — 마킹 화면 lazy 로드
const MarkingPage = lazyWithRetry(() =>
  import('@/pages/MarkingPage').then((m) => ({ default: m.MarkingPage })),
);

// Phase 3 — 게시판(공지) 목록/상세 lazy 로드
const NoticeListPage = lazyWithRetry(() =>
  import('@/pages/NoticeListPage').then((m) => ({ default: m.NoticeListPage })),
);
const NoticeDetailPage = lazyWithRetry(() =>
  import('@/pages/NoticeDetailPage').then((m) => ({ default: m.NoticeDetailPage })),
);
// 공지 작성/수정 — 모달이 아니라 전용 화면이라 각각 직접 진입 가능한 URL 을 갖는다.
const NoticeCreatePage = lazyWithRetry(() =>
  import('@/pages/NoticeCreatePage').then((m) => ({ default: m.NoticeCreatePage })),
);
const NoticeEditPage = lazyWithRetry(() =>
  import('@/pages/NoticeEditPage').then((m) => ({ default: m.NoticeEditPage })),
);

// Phase 2 — 권한 자가 부여 화면 (role 미부여 사용자 진입점) lazy 로드
const RoleClaimPage = lazyWithRetry(() =>
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

// 허용 목록은 「그 자리가 요구하는 역할」을 적는 것이지 「들어올 수 있는 역할 전부」를 열거하는
// 것이 아니다 — 상위 역할은 `RoleGuard` 가 계층으로 통과시킨다(관리자를 여기에 덧붙이지 말 것).
const internalAllRoles = [Role.REVIEWER, Role.WORKER];
const internalReviewerOnly = [Role.REVIEWER];
/** 관리자 전용 — 검수자는 계층의 아래쪽이라 여기에 닿지 않는다. [@design ROLE-004] */
const internalAdminOnly = [Role.ADMIN];
const portalOnly = [Role.PORTAL_USER];

function InternalRoute({ allow, children }: { allow: Role[]; children: ReactNode }) {
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
 * ⚠ 역할 조건은 좌측 메뉴(`components/layout/Lnb.tsx` 의 「관리자」 그룹 `allow`)와 <b>같아야</b>
 *   한다 — 갈리면 「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 된다.
 * ⚠ 진입 화면(`/admin`) 자신에게는 쓰지 않는다 — 유효창이 없을 때 자기 자신으로 무한히 되돌아간다.
 */
function AdminRoute({ children }: { children: ReactNode }) {
  return (
    <InternalRoute allow={internalAdminOnly}>
      <AdminSessionGuard>{children}</AdminSessionGuard>
    </InternalRoute>
  );
}

// DEV 빌드 또는 빌드타임 플래그 VITE_DEV_LOGIN_ENABLED=true 일 때만 `/dev/login` 라우트를 노출.
// 관제서버 미기동 폐쇄망 bring-up 시 prod 빌드에서도 켤 수 있다 (기본 OFF, fail-closed).
// 플래그가 false/미설정인 prod 빌드에서는 if 블록 전체가 dead-code 로 제거되어 DevLoginPage 청크
// 자체가 산출물에 포함되지 않는다.
const devOnlyRoutes: Array<{ path: string; element: ReactNode }> = [];
if (isDevLoginEnabled()) {
  const DevLoginPage = lazyWithRetry(() =>
    import('@/features/auth/DevLoginPage').then((m) => ({ default: m.DevLoginPage })),
  );
  devOnlyRoutes.push({
    path: '/dev/login',
    element: withSuspense(<DevLoginPage />),
  });
}

// 파일 업로드 페이지(`/admin/uploads`) — 관리자 페이지 소속. [@design SCREEN-027] [@design NAV-001]
// DEV 빌드 또는 빌드타임 플래그 VITE_DEV_UPLOAD_ENABLED=true 일 때만 라우트를 노출 (isDevLoginEnabled 와 대칭).
// 플래그 false/미설정인 prod 빌드에서는 if 블록 전체가 dead-code 로 제거되어 DevAutolabelTestPage 청크
// 자체가 산출물에 포함되지 않는다. 실제 게이팅은 BE DEV_UPLOAD_ENABLED 런타임 토글이 결정(라우트만 존재,
// BE off 면 /v1/dev/upload 호출 시 차단). UI 노출은 REVIEWER + 관리자 유효창으로 제한한다.
//
// ⚠ **화면 주소만 옮겼고 BE API 경로(`/v1/dev/upload`)는 그대로다** — 이 변경의 축은 화면 배치이지
//    창구 개명이 아니다.
const devUploadRoutes: Array<{ path: string; element: ReactNode }> = [];
if (isDevUploadEnabled()) {
  const DevAutolabelTestPage = lazyWithRetry(() =>
    import('@/pages/dev/DevAutolabelTestPage').then((m) => ({
      default: m.DevAutolabelTestPage,
    })),
  );
  devUploadRoutes.push({
    path: 'uploads',
    element: <AdminRoute>{withSuspense(<DevAutolabelTestPage />)}</AdminRoute>,
  });
}

// 라우트 트리 — 아래 `createBrowserRouter` 의 유일한 소비처다.
// 별도 상수로 뽑은 것은 basename 옵션을 붙이면서 **배열 본문을 한 글자도 건드리지 않기** 위해서다.
const routes: RouteObject[] = [
  // 진입/공통 — Layout 없이 직접 매칭
  { path: '/ingress', element: <SessionIngressPage /> },
  { path: '/forbidden', element: <ForbiddenPage /> },
  // Phase 2 — 권한 자가 부여 화면. 인증만 통과하면 진입 가능 (role 무관, RoleGuard 미사용).
  {
    path: '/role-claim',
    element: <AuthenticatedGuard>{withSuspense(<RoleClaimPage />)}</AuthenticatedGuard>,
  },
  ...devOnlyRoutes,

  // 라벨링 화면은 풀스크린 다크 UI — AppLayout(LNB/GNB) 밖에서 직접 매칭
  // 인증 가드(InternalRoute)는 그대로 유지
  {
    path: '/label/:id',
    element: (
      <InternalRoute allow={internalAllRoles}>{withSuspense(<LabelingPage />)}</InternalRoute>
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
          // ⚠ LNB(`components/layout/Lnb.tsx` 의 「영상」 그룹 `allow`)와 <b>같은 조건</b>이어야
          //   한다 — 갈리면 「메뉴는 없는데 주소로는 들어가진다」(또는 그 반대)가 된다.
          // ⚠ 같은 「영상」 개념에 속하는 마킹(`marking/:rawSn`)은 이 제한 대상이 **아니다** —
          //   WORKER 가 들어가는 화면이라 internalAllRoles 그대로 둔다.
          {
            path: 'status',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<VideoListPage />)}
              </InternalRoute>
            ),
          },
          {
            path: ':id',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<VideoDetailPage />)}
              </InternalRoute>
            ),
          },
        ],
      },
      {
        path: 'dashboard',
        element: (
          <InternalRoute allow={internalAllRoles}>
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
              <InternalRoute allow={internalAllRoles}>
                {withSuspense(<TaskListPage />)}
              </InternalRoute>
            ),
          },
        ],
      },
      {
        path: 'marking/:rawSn',
        element: (
          <InternalRoute allow={internalAllRoles}>
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
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<ReviewListPage />)}
              </InternalRoute>
            ),
          },
          {
            // mock 정합 alias — /review/pending == /review
            path: 'pending',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<ReviewListPage />)}
              </InternalRoute>
            ),
          },
          {
            path: ':id',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
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
              <InternalRoute allow={internalAllRoles}>
                {withSuspense(<WorkerStatPage />)}
              </InternalRoute>
            ),
          },
          {
            // mock 정합 alias — /stat/worker == /stat
            path: 'worker',
            element: (
              <InternalRoute allow={internalAllRoles}>
                {withSuspense(<WorkerStatPage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'overall',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
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
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<AugmentRequestPage />)}
              </InternalRoute>
            ),
          },
          {
            // mock 정합 alias — /augment/request == /augment
            path: 'request',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<AugmentRequestPage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'result/:rawSn',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
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
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<SystemSettingsPage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'presets',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<PresetListPage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'labels',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<LabelMasterManagePage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'event-types',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<EventTypeManagePage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'deident-reports',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
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
              <InternalRoute allow={internalReviewerOnly}>
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
              <InternalRoute allow={internalAllRoles}>
                {withSuspense(<NoticeListPage />)}
              </InternalRoute>
            ),
          },
          // 작성/수정은 REVIEWER 전용이다. ':id' 보다 먼저 두어 'new' 가 게시글 id 로
          // 해석되지 않게 한다(정적 세그먼트 우선 매칭에 더해 선언 순서로도 못 박는다).
          {
            path: 'new',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<NoticeCreatePage />)}
              </InternalRoute>
            ),
          },
          {
            path: ':id',
            element: (
              <InternalRoute allow={internalAllRoles}>
                {withSuspense(<NoticeDetailPage />)}
              </InternalRoute>
            ),
          },
          {
            path: ':id/edit',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
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
              <InternalRoute allow={internalAdminOnly}>
                {withSuspense(<AdminGatePage />)}
              </InternalRoute>
            ),
          },
          {
            path: 'users',
            element: <AdminRoute>{withSuspense(<UserManagePage />)}</AdminRoute>,
          },
          {
            path: 'endpoints',
            element: <AdminRoute>{withSuspense(<AdminEndpointsPage />)}</AdminRoute>,
          },
          {
            // 산출물 가져오기 — 구 주소 `/manage/imports` 에서 옮겨왔다. [@design SCREEN-039]
            // 서버가 적재 실행(`POST /v1/imports`)과 대응 저장·삭제(`/v1/import-mappings`)를
            // 관리자 전용으로 좁혔는데 화면이 검수자에게 열려 있어, 폴더 탐색·검사까지 정상
            // 진행한 뒤 **마지막 단계에서만 403** 을 받는 상태였다(그 화면에는 역할 참조가
            // 한 줄도 없어 사유 안내조차 없었다). 진입 자체를 관리자로 좁혀 해소한다.
            // ⚠ 좌측 메뉴(「관리자」 그룹)와 <b>같은 조건</b>이어야 한다.
            path: 'imports',
            element: <AdminRoute>{withSuspense(<ImportPage />)}</AdminRoute>,
          },
          {
            path: 'password',
            element: <AdminRoute>{withSuspense(<AdminPasswordPage />)}</AdminRoute>,
          },
          {
            path: 'maintenance',
            element: <AdminRoute>{withSuspense(<AdminMaintenancePage />)}</AdminRoute>,
          },
          // 파일 업로드(`/admin/uploads`) — DEV_UPLOAD_ENABLED 토글로 빌드 포함 여부가 갈린다.
          ...devUploadRoutes,
        ],
      },

      { path: '*', element: <AppErrorPage status={404} /> },
    ],
  },

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
];

// [@design INT-013]
// basename 은 빌드 채널이 정한다 — 포털 채널 산출물만 Host 마운트 경로 아래로 들어가고,
// 내부(관제) 채널은 `undefined` 라 지금 동작 그대로다. 값의 단일 지점은 `lib/remoteMount`.
export const router = createBrowserRouter(routes, { basename: resolveRouterBasename() });
