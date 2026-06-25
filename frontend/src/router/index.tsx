import { lazy, ReactNode, Suspense } from 'react';
import { Navigate, createBrowserRouter } from 'react-router-dom';

import { AppErrorPage } from '@/components/common/AppErrorPage';
import { ForbiddenPage } from '@/components/common/ForbiddenPage';
import { Spinner } from '@/components/common/Spinner';
import { AppLayout } from '@/components/layout/AppLayout';
import { PortalLayout } from '@/components/layout/PortalLayout';
import { SessionIngressPage } from '@/features/auth/SessionIngressPage';
import { Role } from '@/lib/api/types';
import { isDevLoginEnabled } from '@/lib/devLogin';
import { isDevUploadEnabled } from '@/lib/devUpload';

import { AuthenticatedGuard, ChannelGuard, RoleGuard } from './guards';

// Phase 3 — 영상 도메인 + 대시보드 lazy 로드 (코드 스플리팅)
const VideoListPage = lazy(() =>
  import('@/pages/VideoListPage').then((m) => ({ default: m.VideoListPage })),
);
const VideoDetailPage = lazy(() =>
  import('@/pages/VideoDetailPage').then((m) => ({ default: m.VideoDetailPage })),
);
const DashboardPage = lazy(() =>
  import('@/pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
);

// Phase 4 — 작업 배정 + 사용자 관리 + 시스템 설정 lazy 로드
const TaskListPage = lazy(() =>
  import('@/pages/TaskListPage').then((m) => ({ default: m.TaskListPage })),
);
const UserManagePage = lazy(() =>
  import('@/pages/manage/UserManagePage').then((m) => ({ default: m.UserManagePage })),
);
const SystemSettingsPage = lazy(() =>
  import('@/pages/manage/SystemSettingsPage').then((m) => ({ default: m.SystemSettingsPage })),
);

// Phase 5 — 라벨링 캔버스 lazy 로드 (konva 분리)
const LabelingPage = lazy(() =>
  import('@/pages/label/LabelingPage').then((m) => ({ default: m.LabelingPage })),
);

// Phase 8 — 히스토리·버전관리 lazy 로드
const HistoryPage = lazy(() =>
  import('@/pages/HistoryPage').then((m) => ({ default: m.HistoryPage })),
);

// Phase 9 — 검수 워크플로우 lazy 로드
const ReviewListPage = lazy(() =>
  import('@/pages/ReviewListPage').then((m) => ({ default: m.ReviewListPage })),
);
const ReviewPage = lazy(() =>
  import('@/pages/ReviewPage').then((m) => ({ default: m.ReviewPage })),
);
// Phase 10 — 데이터 증강 lazy 로드
const AugmentRequestPage = lazy(() =>
  import('@/pages/AugmentRequestPage').then((m) => ({
    default: m.AugmentRequestPage,
  })),
);
const AugmentResultPage = lazy(() =>
  import('@/pages/AugmentResultPage').then((m) => ({
    default: m.AugmentResultPage,
  })),
);

// Phase 11 — 포털 채널 (데이터마트 영상 선택 + 간편 라벨링, ADR-013) lazy 로드
const PortalHomePage = lazy(() =>
  import('@/pages/portal/PortalHomePage').then((m) => ({ default: m.PortalHomePage })),
);
const PortalLabelingPage = lazy(() =>
  import('@/pages/portal/PortalLabelingPage').then((m) => ({ default: m.PortalLabelingPage })),
);

// Phase 12 — 통계 + 프리셋 lazy 로드 (recharts 별도 청크)
const WorkerStatPage = lazy(() =>
  import('@/pages/WorkerStatPage').then((m) => ({ default: m.WorkerStatPage })),
);
const OverallStatPage = lazy(() =>
  import('@/pages/OverallStatPage').then((m) => ({ default: m.OverallStatPage })),
);
const PresetListPage = lazy(() =>
  import('@/pages/manage/PresetListPage').then((m) => ({ default: m.PresetListPage })),
);
// G-1 — 비식별 신고 관리 (REVIEWER 전용) lazy 로드
const DeidentReportListPage = lazy(() =>
  import('@/pages/manage/DeidentReportListPage').then((m) => ({
    default: m.DeidentReportListPage,
  })),
);

// V2.0 Phase 7 — 마킹 화면 lazy 로드
const MarkingPage = lazy(() =>
  import('@/pages/MarkingPage').then((m) => ({ default: m.MarkingPage })),
);

// Phase 3 — 게시판(공지) 목록/상세 lazy 로드
const NoticeListPage = lazy(() =>
  import('@/pages/NoticeListPage').then((m) => ({ default: m.NoticeListPage })),
);
const NoticeDetailPage = lazy(() =>
  import('@/pages/NoticeDetailPage').then((m) => ({ default: m.NoticeDetailPage })),
);

// Phase 2 — 권한 자가 부여 화면 (role 미부여 사용자 진입점) lazy 로드
const RoleClaimPage = lazy(() =>
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

const internalAllRoles = [Role.REVIEWER, Role.WORKER];
const internalReviewerOnly = [Role.REVIEWER];
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

// DEV 빌드 또는 빌드타임 플래그 VITE_DEV_LOGIN_ENABLED=true 일 때만 `/dev/login` 라우트를 노출.
// 관제서버 미기동 폐쇄망 bring-up 시 prod 빌드에서도 켤 수 있다 (기본 OFF, fail-closed).
// 플래그가 false/미설정인 prod 빌드에서는 if 블록 전체가 dead-code 로 제거되어 DevLoginPage 청크
// 자체가 산출물에 포함되지 않는다.
const devOnlyRoutes: Array<{ path: string; element: ReactNode }> = [];
if (isDevLoginEnabled()) {
  const DevLoginPage = lazy(() =>
    import('@/features/auth/DevLoginPage').then((m) => ({ default: m.DevLoginPage })),
  );
  devOnlyRoutes.push({
    path: '/dev/login',
    element: withSuspense(<DevLoginPage />),
  });
}

// [개발/검수 전용] 오토라벨 테스트 페이지 — REVIEWER 만 진입.
// DEV 빌드 또는 빌드타임 플래그 VITE_DEV_UPLOAD_ENABLED=true 일 때만 라우트를 노출 (isDevLoginEnabled 와 대칭).
// 플래그 false/미설정인 prod 빌드에서는 if 블록 전체가 dead-code 로 제거되어 DevAutolabelTestPage 청크
// 자체가 산출물에 포함되지 않는다. 실제 게이팅은 BE DEV_UPLOAD_ENABLED 런타임 토글이 결정(라우트만 존재,
// BE off 면 /v1/dev/autolabel-test 호출 시 차단). UI 노출은 추가로 REVIEWER 로 제한.
const devUploadRoutes: Array<{ path: string; element: ReactNode }> = [];
if (isDevUploadEnabled()) {
  const DevAutolabelTestPage = lazy(() =>
    import('@/pages/dev/DevAutolabelTestPage').then((m) => ({
      default: m.DevAutolabelTestPage,
    })),
  );
  devUploadRoutes.push({
    path: 'dev/autolabel-test',
    element: (
      <InternalRoute allow={internalReviewerOnly}>
        {withSuspense(<DevAutolabelTestPage />)}
      </InternalRoute>
    ),
  });
}

export const router = createBrowserRouter([
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
          {
            path: 'completed',
            element: (
              <InternalRoute allow={internalAllRoles}>
                {withSuspense(<VideoListPage />)}
              </InternalRoute>
            ),
          },
          {
            path: ':id',
            element: (
              <InternalRoute allow={internalAllRoles}>
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
            path: 'result/:jobId',
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
          {
            path: 'users',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<UserManagePage />)}
              </InternalRoute>
            ),
          },
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
            path: 'deident-reports',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                {withSuspense(<DeidentReportListPage />)}
              </InternalRoute>
            ),
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
          {
            path: ':id',
            element: (
              <InternalRoute allow={internalAllRoles}>
                {withSuspense(<NoticeDetailPage />)}
              </InternalRoute>
            ),
          },
        ],
      },
      {
        path: 'history/:videoId',
        element: (
          <InternalRoute allow={internalAllRoles}>
            {withSuspense(<HistoryPage />)}
          </InternalRoute>
        ),
      },
      // [개발/검수 전용] 오토라벨 테스트 — REVIEWER 만 진입 (DEV_UPLOAD_ENABLED 토글로 빌드 포함 결정).
      ...devUploadRoutes,

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
    ],
  },
]);
