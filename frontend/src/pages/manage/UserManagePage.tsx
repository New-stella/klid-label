import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { AlertTriangle } from 'lucide-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import type { AxiosError } from 'axios';

import { Avatar } from '@/components/common/Avatar';
import { Card, CardContent, CardHeader } from '@/components/common/Card';
import { Field, FieldDescription, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { DataTable, DataTableSkeleton } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { Input } from '@/components/common/Input';
import { Modal } from '@/components/common/Modal';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination } from '@/components/common/Pagination';
import { RoleBadge } from '@/components/common/RoleBadge';
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

/** 역할 select 옵션 표시 순서 — 필터·수정 모달 공용. */
const ROLE_OPTION_ORDER: Role[] = [Role.REVIEWER, Role.WORKER, Role.PORTAL_USER];

/**
 * 섹션 제목 — 카드마다 h2 로 둔다.
 *
 * `CardTitle`(UI-011)은 `<p>` 를 렌더해 헤딩 탐색에 잡히지 않으므로, 이 화면처럼 카드가
 * 곧 섹션인 경우엔 시맨틱 헤딩을 직접 쓰고 카드에 `aria-labelledby` 로 잇는다.
 */
const SECTION_TITLE_CLASS = 'text-title-md text-gray-900';

/**
 * 계정 상태 배지 (읽기 전용).
 *
 * 역할 배지(UI-110 RoleBadge)와 같은 pill 시각언어를 쓰되 색은 상태 축을 따른다.
 * 활성은 success-50 배경 + success-800 글자(9.5:1), 비활성은 gray-50 + gray-700(7.95:1)로
 * semantic base(4.12:1, AA 미달)를 피한다.
 *
 * ⚠ StatusBadge(UI-014)를 쓰지 않는 이유 — 그쪽 매핑은 작업·배치 워크플로 상태 16종 축이라
 *   '활성/비활성'이 미지 코드로 떨어져 두 값이 같은 회색 배지가 된다(활성의 초록을 잃는다).
 */
const STATUS_BADGE_CLASS =
  'inline-flex items-center rounded-full px-2 py-0.5 text-label font-semibold';

function AccountStatusBadge({ active }: { active?: boolean }) {
  return (
    <span
      className={[
        STATUS_BADGE_CLASS,
        active ? 'bg-success-50 text-success-800' : 'bg-gray-50 text-gray-700',
      ].join(' ')}
    >
      {active ? '활성' : '비활성'}
    </span>
  );
}

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

  /**
   * 저장을 잠그는 사유 — 없으면 null(저장 가능).
   *
   * 사양 SCREEN-024: "역할을 선택하지 않았거나 원래 값과 같으면 비활성화된다".
   * 두 사유를 하나의 불리언으로 합치지 않는 이유는 **왜 잠겼는지 화면이 말해야** 하기 때문이다 —
   * 이유 없이 잠긴 버튼은 고장으로 읽힌다.
   */
  const saveBlockedReason: '미선택' | '변경없음' | null = !editUser
    ? null
    : editRole === ''
      ? '미선택'
      : editRole === roleOf(editUser)
        ? '변경없음'
        : null;

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
            {/* 이니셜 아바타는 aria-hidden 이다 — 이름 텍스트가 항상 옆에 있어 식별을 담당한다(UI-109). */}
            <Avatar initial={u.name ?? '?'} />
            <span className="text-body font-medium text-gray-900">{u.name}</span>
          </div>
        );
      },
    },
    {
      id: 'email',
      header: '이메일',
      cell: ({ row }) => {
        const u = row.original;
        // 긴 주소는 잘라 보이되 `title` 로 전체 값을 남긴다 — 열 폭 때문에 값이 사라지지 않게.
        if (u.email) {
          return (
            <span title={u.email} className="block max-w-[260px] truncate text-body text-gray-700">
              {u.email}
            </span>
          );
        }
        // 이메일이 없는 사용자(관제 인계 시 미제공)는 loginId 로 대체하되 **출처를 밝힌다** —
        // 캡션이 없으면 loginId 가 이메일로 읽힌다.
        return (
          <span className="inline-flex max-w-[260px] items-baseline gap-1 text-body text-gray-600">
            <span title={u.loginId} className="truncate">
              {u.loginId}
            </span>
            <span className="shrink-0 text-caption text-gray-600">(로그인ID)</span>
          </span>
        );
      },
    },
    {
      id: 'role',
      header: '역할',
      cell: ({ row }) => {
        const u = row.original;
        const role = roleOf(u);
        // 역할이 없으면 '미배정' 배지 — 빈 배지는 "역할이 없다"와 "값을 못 읽었다"가 구분되지 않는다.
        // RoleBadge(UI-110)는 role=null 을 미배정(경고 아이콘 + 라벨)으로 렌더한다.
        //
        // testid 를 래퍼에 두는 이유: 공용 RoleBadge 에는 testid prop 이 없고, 회귀 가드를
        // 위해 공용 컴포넌트 계약을 넓히지 않는다(배지 텍스트는 이 래퍼 안에 그대로 있다).
        return (
          <span
            className="inline-flex"
            data-testid={role === null ? `user-role-unassigned-${u.id}` : undefined}
          >
            <RoleBadge role={role} label={role ? ROLE_LABEL[role] : undefined} />
          </span>
        );
      },
    },
    {
      id: 'active',
      header: '상태',
      cell: ({ row }) => <AccountStatusBadge active={row.original.active} />,
    },
    {
      id: 'lastLoginAt',
      header: '최근 로그인',
      cell: ({ row }) => {
        const ts = row.original.lastLoginAt ?? row.original.createdAt;
        return (
          <span className="text-body text-gray-700">
            {ts ? new Date(ts).toLocaleDateString('ko-KR') : '-'}
          </span>
        );
      },
    },
    {
      id: 'actions',
      header: '관리',
      // 행 액션 열은 오른쪽 정렬 — 표 왼쪽의 데이터 열과 액션 축을 시각적으로 분리한다.
      meta: { align: 'right' },
      cell: ({ row }) => (
        <div className="flex items-center justify-end gap-1">
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
    // 섹션 사이 여백은 40px 리듬(DS-001 whitespace_principle) — 카드 내부 24px 과 대비를 만들어
    // 헤더 → 필터 → 목록 순의 위계가 먼저 읽힌다.
    <section className="flex flex-col gap-10">
      <PageHeader
        title="사용자 관리"
        // 부제는 정적 텍스트다 — 전체 사용자 수 같은 동적 수치는 표시하지 않는다(사양 SCREEN-024).
        // 동적 카운트를 헤더에 두면 로딩 중 '전체 0명'이 사실처럼 읽힌다.
        description="시스템 사용자 계정을 관리합니다."
      />
      <Card aria-labelledby="user-filter-title">
        <CardHeader>
          <h2 id="user-filter-title" className={SECTION_TITLE_CLASS}>
            검색·역할 필터
          </h2>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap items-end gap-4">
            <Field className="min-w-[240px] flex-1 md:max-w-md">
              <FieldLabel>검색</FieldLabel>
              <Input
                placeholder="이름 / 이메일을 입력하세요."
                value={keywordInput}
                onChange={(e) => setKeywordInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleSearch();
                }}
              />
              <FieldDescription>
                이름 또는 이메일 부분일치로 검색합니다. Enter 또는 검색 버튼으로 확정됩니다.
              </FieldDescription>
            </Field>
            <Field className="min-w-[180px]">
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
            {/* 1차 액션(검색)과 보조 액션(초기화)을 오른쪽에 묶어 입력 영역과 분리한다. */}
            <div className="ml-auto flex items-center gap-2">
              {isFilterActive && (
                <Button
                  variant="ghost"
                  onClick={() => {
                    setKeywordInput('');
                    updateParams({ keyword: undefined, role: undefined, page: 0 });
                  }}
                >
                  필터 초기화
                </Button>
              )}
              <Button variant="primary" onClick={handleSearch}>
                검색
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>
      <Card aria-labelledby="user-list-title">
        <CardHeader>
          <h2 id="user-list-title" className={SECTION_TITLE_CLASS}>
            사용자 목록
          </h2>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          {error && <ErrorState title="사용자 목록을 불러올 수 없습니다" />}
          {/* 로딩 중에는 DataTable 을 렌더하지 않는다 — 표 영역 전체를 스켈레톤으로 대체한다(UI-007). */}
          {/* 표는 이미 카드 안이라 자체 테두리·그림자를 벗긴다 — 겹치면 이중 테두리가 된다. */}
          {isLoading ? (
            <DataTableSkeleton
              columnCount={columns.length}
              className="rounded-none border-0 shadow-none"
            />
          ) : (
            <DataTable<User>
              columns={columns}
              data={rows}
              getRowId={(u) => String(u.id ?? u.loginId ?? '_')}
              emptyMessage="조건에 맞는 사용자가 없습니다"
              className="rounded-none border-0 shadow-none"
            />
          )}
          {/* 페이지네이션은 DataTable 아래에 호출부가 별도로 이어붙인다(UI-007). */}
          <Pagination
            page={params.page ?? 0}
            totalPages={data?.totalPages ?? 0}
            onChange={(p) => updateParams({ page: p })}
          />
        </CardContent>
      </Card>
      <Modal
        open={!!editUser}
        onClose={() => setEditUser(null)}
        title="사용자 정보 수정"
        description={editUser ? `${editUser.name}(${editUser.loginId})` : ''}
        size="md"
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
              disabled={saveBlockedReason !== null}
            >
              저장
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          {/* 역할 미배정 사용자 안내 — 왜 저장 버튼이 잠겨 있는지 알려준다(사양 SCREEN-024).
              역할 배지의 미배정 톤(warn tint)과 같은 색축을 써서 목록에서 본 상태와 이어진다. */}
          {editUser && roleOf(editUser) === null && (
            <p
              data-testid="edit-user-unassigned-notice"
              className="flex items-start gap-2 rounded-md bg-warning-50 px-3 py-2 text-body-sm text-warning-700"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              아직 역할이 배정되지 않은 사용자입니다
            </p>
          )}
          {/*
            원래 값과 같아 잠긴 경우도 같은 방식으로 사유를 밝힌다 — 이유를 말하지 않으면
            잠긴 저장 버튼이 고장으로 읽힌다. 두 사유는 동시에 성립하지 않는다.
          */}
          {saveBlockedReason === '변경없음' && (
            <p
              data-testid="edit-user-unchanged-notice"
              className="rounded-md bg-gray-50 px-3 py-2 text-body-sm text-gray-700"
            >
              변경된 내용이 없습니다. 다른 역할을 선택하면 저장할 수 있습니다.
            </p>
          )}
          <Field>
            <FieldLabel>역할</FieldLabel>
            <Select value={editRole} onValueChange={(v) => setEditRole(v as Role)}>
              <SelectTrigger id="edit-user-role">
                {/* 이 select 에는 빈 값 옵션이 없어(=진짜 미선택) Radix 네이티브 placeholder 가 뜬다. */}
                <SelectValue placeholder="선택하세요" />
              </SelectTrigger>
              <SelectContent>
                {ROLE_OPTION_ORDER.map((r) => (
                  <SelectItem key={r} value={r}>
                    {ROLE_LABEL[r]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldDescription>
              저장하려면 반드시 선택해야 합니다. 서버가 허용값(검수자/작업자/포털)을 재검증합니다.
            </FieldDescription>
          </Field>
          {/* 계정 상태는 읽기 전용이다 — 관제서버 소유값이라 이 화면에 편집 컨트롤을 두지 않고,
              값만 보여주면서 "여기서 바꾸는 것이 아니다"를 문구로 밝힌다. */}
          {editUser && (
            <div className="flex flex-wrap items-center gap-2 border-t border-gray-100 pt-4">
              <span className="text-body font-medium text-gray-700">상태</span>
              <AccountStatusBadge active={editUser.active} />
              <span className="text-caption text-gray-600">
                계정 활성 여부는 이 화면에서 변경하지 않습니다.
              </span>
            </div>
          )}
        </div>
      </Modal>
    </section>
  );
}
