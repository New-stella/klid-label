import { lazy, ReactNode, Suspense } from 'react';
import { Navigate, createBrowserRouter } from 'react-router-dom';

import { AppErrorPage } from '@/components/common/AppErrorPage';
import { ForbiddenPage } from '@/components/common/ForbiddenPage';
import { Spinner } from '@/components/common/Spinner';
import { AppLayout } from '@/components/layout/AppLayout';
import { PortalLayout } from '@/components/layout/PortalLayout';
import { SessionIngressPage } from '@/features/auth/SessionIngressPage';
import { Role } from '@/lib/api/types';

import { ChannelGuard, RoleGuard } from './guards';

// Phase 3 — 영상 도메인 + 대시보드 lazy 로드 (코드 스플리팅)
const VideoListPage = lazy(() =>
  import('@/pages/VideoListPage').then((m) => ({ default: m.VideoListPage })),
);
const VideoStatusPage = lazy(() =>
  import('@/pages/VideoStatusPage').then((m) => ({ default: m.VideoStatusPage })),
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

// Phase 7 — 오토라벨 결과 + 시계열 메타 + 비식별화 결과 lazy 로드
const AutoLabelSummaryPage = lazy(() =>
  import('@/pages/AutoLabelSummaryPage').then((m) => ({ default: m.AutoLabelSummaryPage })),
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
const MetaReviewPage = lazy(() =>
  import('@/pages/MetaReviewPage').then((m) => ({ default: m.MetaReviewPage })),
);
const DeidentListPage = lazy(() =>
  import('@/pages/DeidentListPage').then((m) => ({ default: m.DeidentListPage })),
);
const DeidentDetailPage = lazy(() =>
  import('@/pages/DeidentDetailPage').then((m) => ({ default: m.DeidentDetailPage })),
);

// Phase 10 — 데이터 증강 + 내보내기 + 배경영상 요청 결과 lazy 로드
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
const ExportPage = lazy(() =>
  import('@/pages/ExportPage').then((m) => ({ default: m.ExportPage })),
);
const GenerateResultPage = lazy(() =>
  import('@/pages/GenerateResultPage').then((m) => ({
    default: m.GenerateResultPage,
  })),
);

// Phase 11 — 포털 채널 (TUS 업로드 + 간편 라벨링) lazy 로드
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

export const router = createBrowserRouter([
  // 진입/공통 — Layout 없이 직접 매칭
  { path: '/ingress', element: <SessionIngressPage /> },
  { path: '/forbidden', element: <ForbiddenPage /> },

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
            path: 'status',
            element: (
              <InternalRoute allow={internalAllRoles}>
                {withSuspense(<VideoStatusPage />)}
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
          {
            path: 'assign',
            element: (
              <InternalRoute allow={internalReviewerOnly}>
                <PlaceholderPage title="작업 배정" />
              </InternalRoute>
            ),
          },
        ],
      },
      {
        path: 'auto/:videoId',
        element: (
          <InternalRoute allow={internalAllRoles}>
            {withSuspense(<AutoLabelSummaryPage />)}
          </InternalRoute>
        ),
      },
      {
        path: 'auto/:videoId/meta',
        element: (
          <InternalRoute allow={internalAllRoles}>
            {withSuspense(<MetaReviewPage />)}
          </InternalRoute>
        ),
      },
      {
        path: 'deident',
        element: (
          <InternalRoute allow={internalAllRoles}>
            {withSuspense(<DeidentListPage />)}
          </InternalRoute>
        ),
      },
      {
        path: 'deident/:videoId',
        element: (
          <InternalRoute allow={internalAllRoles}>
            {withSuspense(<DeidentDetailPage />)}
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
        path: 'export',
        element: (
          <InternalRoute allow={internalReviewerOnly}>
            {withSuspense(<ExportPage />)}
          </InternalRoute>
        ),
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
        path: 'history/:videoId',
        element: (
          <InternalRoute allow={internalAllRoles}>
            {withSuspense(<HistoryPage />)}
          </InternalRoute>
        ),
      },
      {
        path: 'generate/result/:jobId',
        element: (
          <InternalRoute allow={internalReviewerOnly}>
            {withSuspense(<GenerateResultPage />)}
          </InternalRoute>
        ),
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
    ],
  },
]);
