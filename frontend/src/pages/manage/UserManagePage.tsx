import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Users } from 'lucide-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { AxiosError } from 'axios';

import { Button } from '@/components/common/Button';
import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { Input } from '@/components/common/Input';
import { Modal } from '@/components/common/Modal';
import { PageHeader } from '@/components/common/PageHeader';
import { updateUser, type UserUpdatePayload } from '@/features/user/api';
import { useUsers } from '@/features/user/hooks/useUsers';
import type { User, UserListParams } from '@/features/user/types';
import { Role } from '@/lib/api/types';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { USER_KEYS } from '@/lib/queryKeys';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-MANAGE-USERS 사용자 관리 (V1.x mock 시각 정합).
 *
 * 진행 범위: 조회 + 검색/필터 + 역할 수정 (PATCH /v1/users/{userNo}).
 * 활성/비활성(useYn)은 관제서버 책임으로 이관 — 저작도구는 상태를 읽기(배지)로만 표시한다.
 *
 * 보안:
 * - REVIEWER만 진입 (RoleGuard) / BE @PreAuthorize("hasRole('REVIEWER')") 이중 방어
 * - 검색어는 axios params로만 (XSS/Injection 방지)
 * - role 은 TypeScript 리터럴 유니온 + BE @Pattern 화이트리스트로 이중 검증
 * - 역할 변경은 Modal 한 단계 거쳐 실수 방지
 */
const ROLE_LABEL: Record<Role, string> = {
  [Role.REVIEWER]: '검수자',
  [Role.WORKER]: '작업자',
  [Role.PORTAL_USER]: '포털',
};

// 역할별 컬러 배지 — mock 시각 정합
// KRDS 예외: 범주 구분색(역할 구분, 데이터시각화 성격) — 토큰 획일화 제외(의도적 유지).
const ROLE_BADGE_CLASS: Record<Role, string> = {
  [Role.REVIEWER]: 'bg-amber-100 text-amber-700',
  [Role.WORKER]: 'bg-blue-100 text-blue-700',
  [Role.PORTAL_USER]: 'bg-gray-100 text-gray-600',
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
  const [editUser, setEditUser] = useState<User | null>(null);
  const [editRole, setEditRole] = useState<Role>(Role.WORKER);
  const pushToast = useUiStore((s) => s.pushToast);
  const queryClient = useQueryClient();

  // PATCH /v1/users/{userNo} — 역할 변경 mutation.
  const updateMutation = useMutation({
    mutationFn: ({ userNo, payload }: { userNo: number; payload: UserUpdatePayload }) =>
      updateUser(userNo, payload),
    onSuccess: () => {
      // 사용자 목록/단건 캐시 무효화 → 자동 재조회.
      queryClient.invalidateQueries({ queryKey: USER_KEYS.all });
      pushToast({ variant: 'success', message: '수정되었습니다.' });
    },
    onError: (err: unknown) => {
      const axiosErr = err as AxiosError<{ message?: string }>;
      const reason = axiosErr?.response?.data?.message ?? axiosErr?.message ?? '알 수 없는 오류';
      pushToast({ variant: 'error', message: `수정에 실패했습니다 — ${reason}` });
    },
  });

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

  const handleEditOpen = (u: User) => {
    setEditUser(u);
    setEditRole(u.role);
  };

  const handleEditSave = () => {
    if (!editUser) return;
    // 변경된 필드만 payload 에 포함 (서버 측은 null 필드 무시).
    const payload: UserUpdatePayload = {};
    if (editRole !== editUser.role) {
      payload.role = editRole as UserUpdatePayload['role'];
    }
    if (!payload.role) {
      // 변경 사항 없음 — 모달만 닫는다.
      setEditUser(null);
      return;
    }
    updateMutation.mutate(
      { userNo: editUser.id, payload },
      { onSettled: () => setEditUser(null) },
    );
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
    {
      key: 'email',
      header: '이메일',
      render: (u) => (
        <span className="text-sub text-gray-500">{u.email ?? u.loginId}</span>
      ),
    },
    {
      key: 'role',
      header: '역할',
      render: (u) => (
        <span
          className={[
            'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
            ROLE_BADGE_CLASS[u.role] ?? 'bg-gray-100 text-gray-700',
          ].join(' ')}
        >
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
              ? 'inline-flex items-center rounded-full bg-success/10 px-2 py-0.5 text-sub font-medium text-success'
              : 'inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-sub font-medium text-gray-500'
          }
        >
          {u.active ? '활성' : '비활성'}
        </span>
      ),
    },
    {
      key: 'lastLoginAt',
      header: '최근 로그인',
      render: (u) => {
        const ts = u.lastLoginAt ?? u.createdAt;
        return (
          <span className="text-sub text-gray-500">
            {ts ? new Date(ts).toLocaleDateString('ko-KR') : '-'}
          </span>
        );
      },
    },
    {
      key: 'actions',
      header: '관리',
      render: (u) => (
        <div className="flex items-center gap-1">
          <Button
            size="sm"
            variant="ghost"
            onClick={(e) => {
              e.stopPropagation();
              handleEditOpen(u);
            }}
          >
            수정
          </Button>
        </div>
      ),
    },
  ];

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title={
          <span className="inline-flex items-center gap-2">
            <span className="inline-flex items-center justify-center rounded-lg bg-gray-100 p-2">
              <Users className="h-5 w-5 text-gray-600" aria-hidden />
            </span>
            <span>사용자 관리</span>
          </span>
        }
        description={
          data
            ? `전체 ${data.totalElements.toLocaleString('ko-KR')}명`
            : '전체 0명'
        }
      />
      <div className="flex flex-wrap items-end gap-2 rounded-lg border border-gray-200 bg-white p-3">
        <div className="flex max-w-md flex-1 items-end gap-2">
          <div className="flex-1">
            <Input
              label="검색"
              placeholder="이름 또는 이메일 검색"
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleSearch();
              }}
            />
          </div>
          <Button variant="primary" onClick={handleSearch}>
            검색
          </Button>
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
            className={`h-10 rounded-md border border-gray-300 bg-white px-3 text-body ${KRDS_FOCUS}`}
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
            className={`h-10 rounded-md border border-gray-300 bg-white px-3 text-body ${KRDS_FOCUS}`}
            aria-label="상태 필터"
          >
            {STATUS_FILTER_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
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
      <Modal
        open={!!editUser}
        onClose={() => setEditUser(null)}
        title="사용자 정보 수정"
        description={editUser ? `${editUser.name}(${editUser.loginId})` : ''}
        size="sm"
        footer={
          <>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setEditUser(null)}
              disabled={updateMutation.isPending}
            >
              취소
            </Button>
            <Button
              variant="primary"
              size="sm"
              onClick={handleEditSave}
              loading={updateMutation.isPending}
            >
              저장
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <div>
            <label
              htmlFor="edit-user-role"
              className="mb-1 block text-sub font-medium text-gray-700"
            >
              역할
            </label>
            <select
              id="edit-user-role"
              value={editRole}
              onChange={(e) => setEditRole(e.target.value as Role)}
              className={`h-10 w-full rounded-md border border-gray-300 bg-white px-3 text-body ${KRDS_FOCUS}`}
            >
              <option value={Role.REVIEWER}>검수자</option>
              <option value={Role.WORKER}>작업자</option>
              <option value={Role.PORTAL_USER}>포털</option>
            </select>
          </div>
        </div>
      </Modal>
    </section>
  );
}
