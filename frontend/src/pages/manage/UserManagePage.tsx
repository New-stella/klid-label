import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Users } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { Input } from '@/components/common/Input';
import { PageHeader } from '@/components/common/PageHeader';
import { useUsers } from '@/features/user/hooks/useUsers';
import type { User, UserListParams } from '@/features/user/types';
import { Role } from '@/lib/api/types';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-MANAGE-USERS 사용자 관리 (V1.x mock 시각 정합).
 *
 * 진행 범위: 조회 + 검색/필터 + 비활성화 placeholder.
 * 추가/수정 모달은 후속 Phase에서 연결.
 *
 * 보안:
 * - REVIEWER만 진입 (RoleGuard)
 * - 검색어는 axios params로만 (XSS/Injection 방지)
 * - 비활성화는 ConfirmDialog 한 단계 거치고, 현재는 placeholder (실수 방지)
 */
const ROLE_LABEL: Record<Role, string> = {
  [Role.REVIEWER]: '검수자',
  [Role.WORKER]: '작업자',
  [Role.PORTAL_USER]: '포털',
};

const ROLE_FILTER_OPTIONS: { value: '' | Role; label: string }[] = [
  { value: '', label: '전체 역할' },
  { value: Role.REVIEWER, label: '검수자' },
  { value: Role.WORKER, label: '작업자' },
  { value: Role.PORTAL_USER, label: '포털' },
];

const STATUS_FILTER_OPTIONS: { value: '' | 'active' | 'inactive'; label: string }[] = [
  { value: '', label: '전체 상태' },
  { value: 'active', label: '활성' },
  { value: 'inactive', label: '비활성' },
];

export function UserManagePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [keywordInput, setKeywordInput] = useState(searchParams.get('keyword') ?? '');
  const [roleFilter, setRoleFilter] = useState<'' | Role>('');
  const [statusFilter, setStatusFilter] = useState<'' | 'active' | 'inactive'>('');
  const [pendingDisable, setPendingDisable] = useState<User | null>(null);
  const pushToast = useUiStore((s) => s.pushToast);

  const params = useMemo<UserListParams>(() => {
    const page = Number(searchParams.get('page') ?? '0');
    const size = Number(searchParams.get('size') ?? '20');
    const keyword = searchParams.get('keyword') ?? undefined;
    return {
      page: Number.isFinite(page) ? page : 0,
      size: Number.isFinite(size) ? size : 20,
      keyword: keyword || undefined,
    };
  }, [searchParams]);

  const { data, isLoading, error } = useUsers(params);

  const updateParams = (next: Partial<UserListParams>) => {
    const sp = new URLSearchParams(searchParams);
    const merged = { ...params, ...next };
    Object.entries(merged).forEach(([k, v]) => {
      if (v === undefined || v === null || v === '') sp.delete(k);
      else sp.set(k, String(v));
    });
    setSearchParams(sp, { replace: false });
  };

  const handleSearch = () => {
    updateParams({ keyword: keywordInput.trim() || undefined, page: 0 });
  };

  const handleConfirmDisable = () => {
    if (!pendingDisable) return;
    // placeholder — 실제 API는 후속 Phase에서.
    pushToast({
      variant: 'info',
      message: `${pendingDisable.name} 비활성화 (placeholder — 후속 Phase 구현 예정)`,
    });
    setPendingDisable(null);
  };

  // 클라이언트 사이드 추가 필터 (role + active)
  const allRows = data?.content ?? [];
  const filteredRows = useMemo(() => {
    return allRows.filter((u) => {
      if (roleFilter && u.role !== roleFilter) return false;
      if (statusFilter === 'active' && !u.active) return false;
      if (statusFilter === 'inactive' && u.active) return false;
      return true;
    });
  }, [allRows, roleFilter, statusFilter]);

  const isFilterActive =
    !!params.keyword || roleFilter !== '' || statusFilter !== '';

  const columns: DataTableColumn<User>[] = [
    {
      key: 'name',
      header: '이름',
      render: (u) => (
        <div className="flex items-center gap-2">
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-100 text-sub font-bold text-primary-700">
            {u.name?.[0] ?? '?'}
          </div>
          <span className="text-body font-medium text-gray-800">{u.name}</span>
        </div>
      ),
    },
    { key: 'loginId', header: '로그인 ID', render: (u) => u.loginId },
    {
      key: 'role',
      header: '역할',
      render: (u) => (
        <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-sub font-medium text-gray-700">
          {ROLE_LABEL[u.role] ?? u.role}
        </span>
      ),
    },
    {
      key: 'active',
      header: '상태',
      render: (u) => (
        <span
          className={
            u.active
              ? 'inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-sub font-medium text-green-700'
              : 'inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-sub font-medium text-gray-500'
          }
        >
          {u.active ? '활성' : '비활성'}
        </span>
      ),
    },
    {
      key: 'createdAt',
      header: '가입일',
      render: (u) => new Date(u.createdAt).toLocaleDateString('ko-KR'),
    },
    {
      key: 'actions',
      header: '액션',
      render: (u) => (
        <Button
          size="sm"
          variant="ghost"
          disabled={!u.active}
          onClick={() => setPendingDisable(u)}
        >
          비활성화
        </Button>
      ),
    },
  ];

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="사용자 관리"
        description={
          data
            ? `REVIEWER/WORKER 사용자 조회 및 상태 관리 — 전체 ${data.totalElements.toLocaleString('ko-KR')}명`
            : 'REVIEWER/WORKER 사용자 조회 및 상태 관리'
        }
        actions={
          <div className="flex items-center justify-center rounded-lg bg-gray-100 p-2">
            <Users className="h-5 w-5 text-gray-600" aria-hidden />
          </div>
        }
      />
      <div className="flex flex-wrap items-end gap-2 rounded-lg border border-gray-200 bg-white p-3">
        <div className="max-w-sm flex-1">
          <Input
            label="검색"
            placeholder="이름·로그인ID 검색"
            value={keywordInput}
            onChange={(e) => setKeywordInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleSearch();
            }}
          />
        </div>
        <div>
          <label
            htmlFor="user-role-filter"
            className="mb-1 block text-sub font-medium text-gray-700"
          >
            역할
          </label>
          <select
            id="user-role-filter"
            value={roleFilter}
            onChange={(e) => setRoleFilter(e.target.value as '' | Role)}
            className="h-10 rounded-md border border-gray-300 bg-white px-3 text-body focus-visible:ring-2 focus-visible:ring-primary-500"
            aria-label="역할 필터"
          >
            {ROLE_FILTER_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label
            htmlFor="user-status-filter"
            className="mb-1 block text-sub font-medium text-gray-700"
          >
            상태
          </label>
          <select
            id="user-status-filter"
            value={statusFilter}
            onChange={(e) =>
              setStatusFilter(e.target.value as '' | 'active' | 'inactive')
            }
            className="h-10 rounded-md border border-gray-300 bg-white px-3 text-body focus-visible:ring-2 focus-visible:ring-primary-500"
            aria-label="상태 필터"
          >
            {STATUS_FILTER_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        <Button variant="primary" onClick={handleSearch}>
          검색
        </Button>
        {isFilterActive && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setKeywordInput('');
              setRoleFilter('');
              setStatusFilter('');
              updateParams({ keyword: undefined, page: 0 });
            }}
          >
            필터 초기화
          </Button>
        )}
      </div>
      {error && <ErrorState title="사용자 목록을 불러올 수 없습니다" />}
      <DataTable<User>
        columns={columns}
        rows={filteredRows}
        totalElements={data?.totalElements ?? 0}
        page={params.page ?? 0}
        size={params.size ?? 20}
        loading={isLoading}
        emptyMessage="조건에 맞는 사용자가 없습니다"
        rowKey={(u) => u.id ?? u.loginId ?? '_'}
        onPageChange={(p) => updateParams({ page: p })}
      />
      <ConfirmDialog
        open={!!pendingDisable}
        title="사용자 비활성화"
        description={
          pendingDisable
            ? `${pendingDisable.name}(${pendingDisable.loginId})을(를) 비활성화하시겠습니까?`
            : ''
        }
        variant="danger"
        confirmLabel="비활성화"
        onConfirm={handleConfirmDisable}
        onCancel={() => setPendingDisable(null)}
      />
    </section>
  );
}
