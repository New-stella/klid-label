import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { Input } from '@/components/common/Input';
import { PageHeader } from '@/components/common/PageHeader';
import { useUsers } from '@/features/user/hooks/useUsers';
import type { User, UserListParams } from '@/features/user/types';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-MANAGE-USERS 사용자 관리.
 *
 * Phase 4 범위: 조회 + 검색/필터 + 비활성화 placeholder (실제 API 호출은 Phase 12).
 * 추가/수정 모달은 Phase 12에서 연결.
 *
 * 보안:
 * - REVIEWER만 진입 (RoleGuard)
 * - 검색어는 axios params로만 (XSS/Injection 방지)
 * - 비활성화는 ConfirmDialog 한 단계 거치고, 현재는 placeholder (실수 방지)
 */
export function UserManagePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [keywordInput, setKeywordInput] = useState(searchParams.get('keyword') ?? '');
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
    // Phase 4는 placeholder — 실제 API는 Phase 12에서.
    pushToast({
      variant: 'info',
      message: `${pendingDisable.name} 비활성화 (placeholder — Phase 12 구현 예정)`,
    });
    setPendingDisable(null);
  };

  const columns: DataTableColumn<User>[] = [
    { key: 'loginId', header: '로그인 ID', render: (u) => u.loginId },
    { key: 'name', header: '이름', render: (u) => u.name },
    { key: 'role', header: '역할', render: (u) => u.role },
    {
      key: 'active',
      header: '상태',
      render: (u) => (u.active ? '활성' : '비활성'),
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
      <PageHeader title="사용자 관리" description="REVIEWER/WORKER 사용자 조회 및 상태 관리" />
      <div className="flex items-end gap-2">
        <div className="flex-1 max-w-sm">
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
        <Button variant="primary" onClick={handleSearch}>
          검색
        </Button>
      </div>
      {error && <ErrorState title="사용자 목록을 불러올 수 없습니다" />}
      <DataTable<User>
        columns={columns}
        rows={data?.content ?? []}
        totalElements={data?.totalElements ?? 0}
        page={params.page ?? 0}
        size={params.size ?? 20}
        loading={isLoading}
        emptyMessage="조건에 맞는 사용자가 없습니다"
        rowKey={(u) => u.id}
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
