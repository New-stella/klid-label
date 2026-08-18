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
 * 검색 입력의 도움말 id — 도움말이 `Field` 밖(그리드 2행)에 있어 컨텍스트 자동 배선이 닿지
 * 않으므로 `aria-describedby` 를 명시로 잇는다. 화면에 하나뿐인 필터바라 고정 id 로 충분하다.
 */
const SEARCH_HELP_ID = 'user-search-help';

/** 값이 없는 날짜 셀의 표기 — 빈칸은 "값이 없다"와 "못 읽었다"가 구분되지 않는다. */
const EMPTY_DATE_TEXT = '-';

/**
 * 표의 날짜 셀 표기 — **등록일과 최신 로그인이 공유한다**.
 *
 * 같은 표에서 두 날짜가 다른 형식으로 보이면 비교가 불가능하므로 포매터를 하나로 둔다.
 * 값이 없으면(최신 로그인 미접속) 다른 날짜로 대체하지 않고 미접속 표기를 그린다.
 */
function formatDateCell(value?: string | null): string {
  if (!value) {
    return EMPTY_DATE_TEXT;
  }
  const parsed = new Date(value);
  // 파싱 실패(Invalid Date)를 'Invalid Date' 문자열로 흘리지 않는다.
  return Number.isNaN(parsed.getTime())
    ? EMPTY_DATE_TEXT
    : parsed.toLocaleDateString('ko-KR');
}

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
  /**
   * 역할 select 의 **확정 전 선택값** — 조회에 실린 값(`params.role`)과 별개 축이다.
   *
   * @design SCREEN-024 — "입력값은 Enter 또는 검색 실행으로 확정되어야 조회에 반영되며
   * (셀렉트를 바꾸는 것만으로 즉시 재조회되지 않음), 확정 시 1페이지로 초기화된다."
   *
   * ⚠ 구 동작은 `onValueChange` 에서 곧바로 `updateParams` 를 불러 **고르는 즉시 재조회**했다.
   * 검색어는 이미 확정 방식이었으므로 같은 필터바 안에서 두 컨트롤의 확정 시점이 갈렸고,
   * "검색어를 고쳐 두고 역할을 바꾸면 아직 확정하지 않은 검색어 없이 역할만 적용"되는
   * 어긋남이 있었다. 두 축 모두 `handleSearch` 한 곳에서 확정한다.
   */
  const [roleInput, setRoleInput] = useState<Role | ''>(
    (searchParams.get('role') as Role | null) ?? '',
  );
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

  /**
   * 필터 확정 — 검색어·역할 **두 축을 함께** 조회 파라미터에 싣고 1페이지로 되돌린다.
   * 진입점은 검색 버튼과 검색 입력의 Enter 두 곳이며 둘의 결과는 같다(@design SCREEN-024).
   */
  const handleSearch = () => {
    updateParams({
      keyword: keywordInput.trim() || undefined,
      role: roleInput || undefined,
      page: 0,
    });
  };

  /** 필터 초기화 — 확정 전 선택값과 이미 조회에 실린 값을 **함께** 비운다. */
  const handleFilterReset = () => {
    setKeywordInput('');
    setRoleInput('');
    updateParams({ keyword: undefined, role: undefined, page: 0 });
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

  /**
   * '필터 초기화' 를 누를 수 있는 조건 — @design SCREEN-024 note
   * "검색어 또는 역할 필터 중 하나라도 활성일 때만 눌림 가능."
   *
   * 확정된 값(`params.*`)뿐 아니라 **확정 전 입력값도 함께** 본다. 확정값만 보면 사용자가
   * 검색어를 타이핑하거나 역할을 골라 둔(아직 확정 전) 상태에서 버튼이 잠겨, 방금 넣은
   * 값을 되돌릴 수단이 사라진다 — 초기화가 그 둘을 실제로 비우므로 판정 축도 같아야 한다.
   */
  const isFilterActive =
    !!params.keyword || !!params.role || keywordInput.trim().length > 0 || roleInput !== '';

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
    // ── 등록일 · 최신 로그인은 **각각 별도 컬럼**이다 (사양 SCREEN-024) ──
    // 두 값은 용도가 다른 별개 축이다 — 등록일은 가입 이력, 최신 로그인은 휴면 계정 판단.
    // 구 구현은 컬럼이 하나뿐이었고 `lastLoginAt ?? createdAt` 으로 폴백해, 로그인 기록이
    // 존재하지 않던 동안 **등록일 값을 "최근 로그인" 헤더 아래 표시**했다(거짓 표기).
    // 폴백을 되살리지 말 것 — 값이 없으면 미접속으로 그린다.
    {
      id: 'createdAt',
      header: '등록일',
      cell: ({ row }) => (
        <span data-testid={`user-created-at-${row.original.id}`} className="text-body text-gray-700">
          {formatDateCell(row.original.createdAt)}
        </span>
      ),
    },
    {
      id: 'lastLoginAt',
      header: '최신 로그인',
      cell: ({ row }) => (
        <span data-testid={`user-last-login-${row.original.id}`} className="text-body text-gray-700">
          {formatDateCell(row.original.lastLoginAt)}
        </span>
      ),
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
          {/*
            @design SCREEN-024 필터 폼 — 확정 시안의 그리드를 그대로 옮긴다.

            트랙: [검색 minmax(0,420px)] [역할 180px] [액션 auto], 1행은 밑단 정렬(items-end).
            도움말만 **2행 1열**로 내린다 — 도움말을 검색 Field 안에 두면 그 높이가 Field
            높이에 더해져, 밑단 정렬이 검색 입력만 위로 들어올려 역할 select·버튼과 어긋난다
            (어긋남은 도움말 줄 수에 비례해 커진다). 행을 분리하면 줄 수와 무관하게 1행 정렬이
            유지된다. 좁은 화면(md 미만)은 1열 스택이라 DOM 순서가 곧 읽는 순서다.
          */}
          <div className="grid grid-cols-1 items-end gap-x-6 gap-y-2 md:grid-cols-[minmax(0,420px)_180px_auto]">
            {/* 그리드 아이템의 자동 최소 크기(min-width:auto)는 안의 input 고유폭을 바닥으로
                삼아 트랙을 밀어낸다 — min-w-0 으로 눌러 트랙이 정한 폭을 따르게 한다. */}
            <Field className="min-w-0 md:col-start-1 md:row-start-1">
              <FieldLabel>검색</FieldLabel>
              <Input
                placeholder="이름 / 이메일을 입력하세요."
                value={keywordInput}
                onChange={(e) => setKeywordInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleSearch();
                }}
                // 도움말이 Field 밖(2행)에 있어 컨텍스트 자동 배선이 닿지 않는다 — 명시 id 로
                // 잇는다(호출부 명시값이 컨텍스트보다 우선한다: fieldContext.resolveFieldControl).
                aria-describedby={SEARCH_HELP_ID}
              />
            </Field>
            <p id={SEARCH_HELP_ID} className="text-sub text-gray-500 md:col-start-1 md:row-start-2">
              이름 또는 이메일 부분일치로 검색합니다. Enter 또는 검색 버튼으로 확정됩니다.
            </p>
            <Field className="min-w-0 md:col-start-2 md:row-start-1">
              <FieldLabel>역할</FieldLabel>
              <Select
                value={roleInput}
                onValueChange={(v) => setRoleInput((v || '') as Role | '')}
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
            {/*
              검색·역할 필터와 그 실행 버튼은 한 덩어리로 읽혀야 한다 — 구 구현의 `ml-auto` 로
              카드 오른쪽 끝까지 밀지 않는다(확정 시안 `justify-self:start`).
              좁은 화면에서는 폭을 다 쓰고 그 안에서 오른쪽으로 붙는다.
            */}
            <div className="flex items-center justify-end gap-2 md:col-start-3 md:row-start-1 md:justify-self-start">
              {/*
                '필터 초기화' 는 **상시 노출**하고 누를 수 없을 때 비활성으로 둔다.
                구 구현은 활성 필터가 없으면 아예 렌더하지 않아 버튼이 나타났다 사라졌고,
                그때마다 옆의 '검색' 버튼 위치가 흔들렸다. 사양 note 는 "눌림 가능" 조건이지
                "존재" 조건이 아니다(@design SCREEN-024).
              */}
              <Button variant="ghost" disabled={!isFilterActive} onClick={handleFilterReset}>
                필터 초기화
              </Button>
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
