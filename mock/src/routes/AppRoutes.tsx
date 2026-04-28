import { Routes, Route } from 'react-router-dom';
import { AppShell } from '../layouts/AppShell';
import { PortalShell } from '../layouts/PortalShell';
import { HomeRedirect } from '../pages/Home';
import { NotFound } from '../pages/NotFound';
import { Placeholder } from '../pages/_Placeholder';
import { RoleGuard } from './RoleGuard';
import { APP_ROUTES } from './routes';

// Phase 3 real pages
import { Dashboard } from '../pages/Dashboard';
import { BatchCompletedList } from '../pages/batch/BatchCompletedList';
import { BatchMonitoring } from '../pages/batch/BatchMonitoring';
import { VideoDetail } from '../pages/batch/VideoDetail';

// Phase 4 real pages
import { TaskList } from '../pages/task/TaskList';

// Phase 5 real pages
import { LabelEditor } from '../pages/label/LabelEditor';

// Phase 6 real pages
import { ReviewPending } from '../pages/review/ReviewPending';
import { ReviewEditor } from '../pages/review/ReviewEditor';

// Phase 7 real pages
import { DeidentList } from '../pages/deident/DeidentList';
import { DeidentCompare } from '../pages/deident/DeidentCompare';
import { VersionHistory } from '../pages/history/VersionHistory';

// Phase 8 real pages
import { AugmentRequest } from '../pages/augment/AugmentRequest';
import { AugmentResult } from '../pages/augment/AugmentResult';
import { ExportPage } from '../pages/export/ExportPage';
import { DataMart } from '../pages/mart/DataMart';

// Phase 9: Portal pages
import { PortalMain } from '../pages/portal/PortalMain';
import { PortalLabelEditor } from '../pages/portal/PortalLabelEditor';

// Phase 10: Stats
import { WorkerStats } from '../pages/stat/WorkerStats';
import { OverallStats } from '../pages/stat/OverallStats';

// Phase 10: Admin
import { UserManagement } from '../pages/admin/UserManagement';
import { SystemSettings } from '../pages/admin/SystemSettings';

// Phase 10: Preset
import { PresetList } from '../pages/preset/PresetList';

// Phase 10: Generate (bonus)
import { GenerateResult } from '../pages/generate/GenerateResult';

// Paths that have been replaced with real components
const REAL_PATHS = new Set([
  '/dashboard',
  '/video/completed',
  '/video/monitoring',
  '/video/:id',
  '/task',
  '/label/:id',
  '/review/pending',
  '/review/:id',
  '/deident',
  '/deident/:id',
  '/history/:id',
  '/augment/request',
  '/augment/result/:id',
  '/export',
  '/mart',
  '/portal',
  '/portal/label/:id',
  '/stat/worker',
  '/stat/overall',
  '/admin/users',
  '/admin/settings',
  '/preset',
  '/generate/result/:id',
]);

export function AppRoutes() {
  return (
    <Routes>
      {/* All authenticated routes inside AppShell */}
      <Route element={<AppShell />}>
        {/* Root redirect */}
        <Route index element={<HomeRedirect />} />

        {/* Phase 3 real routes */}
        <Route
          path="/dashboard"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER', 'WORKER']}>
              <Dashboard />
            </RoleGuard>
          }
        />
        <Route
          path="/video/completed"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER', 'WORKER']}>
              <BatchCompletedList />
            </RoleGuard>
          }
        />
        <Route
          path="/video/monitoring"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER', 'WORKER']}>
              <BatchMonitoring />
            </RoleGuard>
          }
        />
        <Route
          path="/video/:id"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER', 'WORKER']}>
              <VideoDetail />
            </RoleGuard>
          }
        />
        <Route
          path="/task"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER', 'WORKER']}>
              <TaskList />
            </RoleGuard>
          }
        />

        {/* Phase 5: Label editor — full-screen, no AppShell chrome */}
        <Route
          path="/label/:id"
          element={
            <RoleGuard roles={['ADMIN', 'WORKER']}>
              <LabelEditor />
            </RoleGuard>
          }
        />

        {/* Phase 6: Review pages */}
        <Route
          path="/review/pending"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER']}>
              <ReviewPending />
            </RoleGuard>
          }
        />
        <Route
          path="/review/:id"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER']}>
              <ReviewEditor />
            </RoleGuard>
          }
        />

        {/* Phase 7: Deident pages */}
        <Route
          path="/deident"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER']}>
              <DeidentList />
            </RoleGuard>
          }
        />
        <Route
          path="/deident/:id"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER']}>
              <DeidentCompare />
            </RoleGuard>
          }
        />

        {/* Phase 7: Version history */}
        <Route
          path="/history/:id"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER', 'WORKER']}>
              <VersionHistory />
            </RoleGuard>
          }
        />

        {/* Phase 8: Augment */}
        <Route
          path="/augment/request"
          element={
            <RoleGuard roles={['ADMIN']}>
              <AugmentRequest />
            </RoleGuard>
          }
        />
        <Route
          path="/augment/result/:id"
          element={
            <RoleGuard roles={['ADMIN']}>
              <AugmentResult />
            </RoleGuard>
          }
        />

        {/* Phase 8: Export */}
        <Route
          path="/export"
          element={
            <RoleGuard roles={['ADMIN']}>
              <ExportPage />
            </RoleGuard>
          }
        />

        {/* Phase 8: DataMart */}
        <Route
          path="/mart"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER', 'PORTAL_USER']}>
              <DataMart />
            </RoleGuard>
          }
        />

        {/* Phase 10: Stats */}
        <Route
          path="/stat/worker"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER', 'WORKER']}>
              <WorkerStats />
            </RoleGuard>
          }
        />
        <Route
          path="/stat/overall"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER']}>
              <OverallStats />
            </RoleGuard>
          }
        />

        {/* Phase 10: Admin */}
        <Route
          path="/admin/users"
          element={
            <RoleGuard roles={['ADMIN']}>
              <UserManagement />
            </RoleGuard>
          }
        />
        <Route
          path="/admin/settings"
          element={
            <RoleGuard roles={['ADMIN']}>
              <SystemSettings />
            </RoleGuard>
          }
        />

        {/* Phase 10: Preset */}
        <Route
          path="/preset"
          element={
            <RoleGuard roles={['ADMIN', 'REVIEWER']}>
              <PresetList />
            </RoleGuard>
          }
        />

        {/* Phase 10: Generate (bonus) */}
        <Route
          path="/generate/result/:id"
          element={
            <RoleGuard roles={['ADMIN']}>
              <GenerateResult />
            </RoleGuard>
          }
        />

        {/* Remaining placeholder routes */}
        {APP_ROUTES.filter((meta) => !REAL_PATHS.has(meta.path)).map((meta) => (
          <Route
            key={meta.path}
            path={meta.path}
            element={
              <RoleGuard roles={meta.roles}>
                <Placeholder label={meta.label} allowedRoles={meta.roles} />
              </RoleGuard>
            }
          />
        ))}
      </Route>

      {/* Phase 9: Portal routes — use PortalShell (no AppShell LNB) */}
      <Route element={<PortalShell />}>
        <Route
          path="/portal"
          element={
            <RoleGuard roles={['PORTAL_USER']}>
              <PortalMain />
            </RoleGuard>
          }
        />
        <Route
          path="/portal/label/:id"
          element={
            <RoleGuard roles={['PORTAL_USER']}>
              <PortalLabelEditor />
            </RoleGuard>
          }
        />
      </Route>

      {/* 404 */}
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}

export default AppRoutes;
