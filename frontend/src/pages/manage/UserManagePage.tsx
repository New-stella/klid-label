import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Users } from 'lucide-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import type { AxiosError } from 'axios';

import { Field, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { DataTable, DataTableSkeleton } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { Input } from '@/components/common/Input';
import { Modal } from '@/components/common/Modal';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination } from '@/components/common/Pagination';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { updateUser, type UserUpdatePayload } from '@/features/user/api';
import { useUsers } from '@/features/user/hooks/useUsers';
import type { User, UserListParams } from '@/features/user/types';
import { Role } from '@/lib/api/types';
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

/** 역할 select 옵션 표시 순서 — 필터·수정 모달 공용. */
const ROLE_OPTION_ORDER: Role[] = [Role.REVIEWER, Role.WORKER, Role.PORTAL_USER];

/**
 * 역할 미배정 사용자 판정 — BE `UserSummaryResponse.from` 은 LS_USER_ROLE 이 없으면
 * `role` 을 **null 로 내려보내고 기본값을 부여하지 않는다**(관제 인계 키에 역할 클레임이 없는
 * 자동등록 사용자).
 *
 * 공용 타입 `User.role` 이 `Role | null` 로 정정돼 캐스팅은 더 이상 필요 없다.
 * `?? null` 만 남긴 이유는 구 응답·목 데이터에 필드 자체가 없는(`undefined`) 경우를
 * 같은 축으로 좁히기 위해서다.
 */
function roleOf(u: User): Role | null {
  return u.role ?? null;
}

export function UserManagePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [keywordInput, setKeywordInput] = useState(searchParams.get('keyword') ?? '');
  const [editUser, setEditUser] = useState<User | null>(null);
  // 미배정 사용자는 초기 선택값이 없다('') — 임의 기본값(WORKER)을 채우면 사용자가 고르지 않은
  // 역할이 저장될 수 있다. 사양 SCREEN-024: '저장하려면 반드시 선택해야 한다'.
  const [editRole, setEditRole] = useState<Role | ''>('');
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
    // 역할 필터는 BE GET /v1/users?role= 로 서버 사이드 처리한다(사양 SCREEN-024) —
    // 상태(활성/비활성) 필터는 두지 않는다(관제서버 소유값이라 이 화면의 필터 축이 아니다).
    const role = (searchParams.get('role') as Role | null) ?? undefined;
    return {
      page: Number.isFinite(page) ? page : 0,
      size: Number.isFinite(size) ? size : 20,
      keyword: keyword || undefined,
      role: role || undefined,
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
    // 역할 미배정이면 '' 로 열어 선택 전까지 저장을 막는다.
    setEditRole(roleOf(u) ?? '');
  };

  const handleEditSave = () => {
    if (!editUser) return;
    if (editRole === '') return; // 미선택 — 저장 버튼이 이미 비활성이지만 이중 방어.
    // 변경된 필드만 payload 에 포함 (서버 측은 null 필드 무시).
    const payload: UserUpdatePayload = {};
    if (editRole !== roleOf(editUser)) {
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

  const rows = data?.content ?? [];

  const isFilterActive = !!params.keyword || !!params.role;

  const columns: ColumnDef<User, unknown>[] = [
    {
      id: 'name',
      header: '이름',
      cell: ({ row }) => {
        const u = row.original;
        return (
          <div className="flex items-center gap-2">
            <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-primary-100 text-sub font-bold text-primary-700">
              {u.name?.[0] ?? '?'}
            </div>
            <span className="text-body font-medium text-gray-800">{u.name}</span>
          </div>
        );
      },
    },
    {
      id: 'email',
      header: '이메일',
      cell: ({ row }) => (
        <span className="text-sub text-gray-500">{row.original.email ?? row.original.loginId}</span>
      ),
    },
    {
      id: 'role',
      header: '역할',
      cell: ({ row }) => {
        const role = roleOf(row.original);
        // 역할이 없으면 '미배정' 배지 — 빈 배지는 "역할이 없다"와 "값을 못 읽었다"가 구분되지 않는다.
        if (role === null) {
          return (
            <span
              data-testid={`user-role-unassigned-${row.original.id}`}
              className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-sub font-medium text-gray-500"
            >
              미배정
            </span>
          );
        }
        return (
          <span
            className={[
              'inline-flex items-center rounded-full px-2 py-0.5 text-sub font-medium',
              ROLE_BADGE_CLASS[role] ?? 'bg-gray-100 text-gray-700',
            ].join(' ')}
          >
            {ROLE_LABEL[role] ?? role}
          </span>
        );
      },
    },
    {
      id: 'active',
      header: '상태',
      cell: ({ row }) => {
        const u = row.original;
        return (
          <span
            className={
              u.active
                ? 'inline-flex items-center rounded-full bg-success/10 px-2 py-0.5 text-sub font-medium text-success-700'
                : 'inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-sub font-medium text-gray-500'
            }
          >
            {u.active ? '활성' : '비활성'}
          </span>
        );
      },
    },
    {
      id: 'lastLoginAt',
      header: '최근 로그인',
      cell: ({ row }) => {
        const ts = row.original.lastLoginAt ?? row.original.createdAt;
        return (
          <span className="text-sub text-gray-500">
            {ts ? new Date(ts).toLocaleDateString('ko-KR') : '-'}
          </span>
        );
      },
    },
    {
      id: 'actions',
      header: '관리',
      cell: ({ row }) => (
        <div className="flex items-center gap-1">
          <Button
            size="sm"
            variant="ghost"
            onClick={(e) => {
              e.stopPropagation();
              handleEditOpen(row.original);
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
        // 부제는 정적 텍스트다 — 전체 사용자 수 같은 동적 수치는 표시하지 않는다(사양 SCREEN-024).
        // 동적 카운트를 헤더에 두면 로딩 중 '전체 0명'이 사실처럼 읽힌다.
        description="시스템 사용자 계정을 관리합니다."
      />
      <div className="flex flex-wrap items-end gap-2 rounded-lg border border-gray-200 bg-white p-3">
        <div className="flex max-w-md flex-1 items-end gap-2">
          <Field className="flex-1">
            <FieldLabel>검색</FieldLabel>
            <Input
              placeholder="이름 또는 이메일 검색"
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleSearch();
              }}
            />
          </Field>
          <Button variant="primary" onClick={handleSearch}>
            검색
          </Button>
        </div>
        <Field>
          <FieldLabel>역할</FieldLabel>
          <Select
            value={params.role ?? ''}
            onValueChange={(v) => updateParams({ role: (v || undefined) as Role, page: 0 })}
          >
            <SelectTrigger id="user-role-filter" aria-label="역할 필터">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="">전체 역할</SelectItem>
              {ROLE_OPTION_ORDER.map((r) => (
                <SelectItem key={r} value={r}>
                  {ROLE_LABEL[r]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>
        {isFilterActive && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setKeywordInput('');
              updateParams({ keyword: undefined, role: undefined, page: 0 });
            }}
          >
            필터 초기화
          </Button>
        )}
      </div>
      {error && <ErrorState title="사용자 목록을 불러올 수 없습니다" />}
      <div className="flex flex-col gap-3">
        {/* 로딩 중에는 DataTable 을 렌더하지 않는다 — 표 영역 전체를 스켈레톤으로 대체한다(UI-007). */}
        {isLoading ? (
          <DataTableSkeleton columnCount={columns.length} />
        ) : (
          <DataTable<User>
            columns={columns}
            data={rows}
            getRowId={(u) => String(u.id ?? u.loginId ?? '_')}
            emptyMessage="조건에 맞는 사용자가 없습니다"
          />
        )}
        {/* 페이지네이션은 DataTable 아래에 호출부가 별도로 이어붙인다(UI-007). */}
        <Pagination
          page={params.page ?? 0}
          size={params.size ?? 20}
          totalElements={data?.totalElements ?? 0}
          onPageChange={(p) => updateParams({ page: p })}
        />
      </div>
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
              disabled={editRole === ''}
            >
              저장
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          {/* 역할 미배정 사용자 안내 — 왜 저장 버튼이 잠겨 있는지 알려준다(사양 SCREEN-024). */}
          {editUser && roleOf(editUser) === null && (
            <p
              data-testid="edit-user-unassigned-notice"
              className="rounded-md bg-gray-50 px-3 py-2 text-sub text-gray-600"
            >
              아직 역할이 배정되지 않은 사용자입니다
            </p>
          )}
          <Field>
            <FieldLabel>역할</FieldLabel>
            <Select value={editRole} onValueChange={(v) => setEditRole(v as Role)}>
              <SelectTrigger id="edit-user-role">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {ROLE_OPTION_ORDER.map((r) => (
                  <SelectItem key={r} value={r}>
                    {ROLE_LABEL[r]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
        </div>
      </Modal>
    </section>
  );
}
