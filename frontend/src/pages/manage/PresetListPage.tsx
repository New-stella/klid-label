import { useMemo, useState } from 'react';
import { Copy, Layers, Pencil, Plus, Trash2 } from 'lucide-react';

import { Button } from '@/components/common/Button';
import {
  Card,
  CardAction,
  CardContent,
  CardFooter,
  CardHeader,
} from '@/components/common/Card';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination, pageCountOf } from '@/components/common/Pagination';
import {
  PRESET_LABEL_CHIP_LIMIT,
  PresetLabelOverflowChip,
} from '@/components/common/PresetLabelOverflowChip';
import { Skeleton } from '@/components/common/Skeleton';
import { useEventTypes } from '@/features/eventType/hooks';
import { PresetCodeChip } from '@/features/preset/components/PresetCodeChip';
import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import { usePresetActions } from '@/features/preset/hooks/usePresetActions';
import { usePresets } from '@/features/preset/hooks/usePresets';
import { type Preset, type PresetForm } from '@/features/preset/types';
import { ApiError } from '@/lib/api/errors';
import { Role } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 한 페이지에 보이는 프리셋 카드 수 — 사양 SCREEN-026 이 카드 그리드·페이지네이션 두 절에서
 * 모두 **9건/페이지**로 규정한다.
 *
 * 3의 배수인 것이 핵심이다 — 그리드가 xl 에서 3열이라 9면 마지막 줄이 정확히 차고, 10이면
 * 마지막 카드 하나만 남은 줄이 생겨 리듬이 깨진다.
 */
const PAGE_SIZE = 9;

/**
 * 카드에 그대로 노출하는 라벨 칩 최대 개수 — 초과분은 '+N' 칩으로 접는다(사양 SCREEN-026).
 * 편집 모달의 라벨 선택 상한(20)과는 **다른 축**이다 — 이건 표시용 축소일 뿐이다.
 *
 * 값의 진실원은 축약 칩 컴포넌트(UI-113)다 — 한도와 그 한도를 표시하는 칩이 갈리면
 * '+N' 의 N 이 실제 접힌 개수와 어긋난다.
 */
const VISIBLE_CHIP_COUNT = PRESET_LABEL_CHIP_LIMIT;

/** 로딩 중 자리를 채우는 스켈레톤 카드 수 — 그리드 3열 × 2행(사양 SCREEN-026 '스켈레톤 ×6'). */
const SKELETON_COUNT = 6;

/**
 * 카드 그리드 열 구성 — 좁은 화면 1열 / md(768px) 2열 / xl(1280px) 3열.
 *
 * ⚠ 이 저장소의 Tailwind `theme.screens` 는 기본 브레이크포인트를 **대체**해 `md`·`xl`
 *   둘만 정의한다. `sm:`/`lg:` 는 한 번도 적용되지 않는 죽은 접두사다.
 */
const GRID_CLASS = 'grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-3';

/**
 * SCR-MANAGE-PRESETS 라벨링 프리셋 관리 (SCREEN-026 고충실 디자인 정합) — REVIEWER 전용.
 *
 * - 반응형 카드 그리드(1/2/3열) + 클라이언트 페이지네이션 (9건/페이지)
 * - 프리셋 추가 / 수정 / 복사 / 삭제 (REVIEWER만)
 * - 라벨 항목 1~20종 제한 (zod presetSchema.labelIds min(1).max(20))
 */
export function PresetListPage() {
  const { data, isLoading, error } = usePresets();
  // 프리셋은 categoryKey 를 eventTypeCd 로 저장하므로 categoryKey→label 맵으로 해석한다
  // (EV-코드 맵 useEventTypeLabels 는 categoryKey 를 못 풀어 원문 노출되는 회귀가 있었음).
  const { data: eventTypeOptions } = useEventTypes();
  const catLabel = useMemo(
    () => new Map((eventTypeOptions ?? []).map((o) => [o.categoryKey, o.label])),
    [eventTypeOptions],
  );
  const { create, update, remove, clone } = usePresetActions();
  const pushToast = useUiStore((s) => s.pushToast);
  const role = useAuthStore((s) => s.claims?.role);
  const isReviewer = role === Role.REVIEWER;

  const [editing, setEditing] = useState<Preset | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<Preset | null>(null);
  const [page, setPage] = useState(0);
  /** 라벨 칩을 모두 펼친 프리셋 id 집합 — 기본은 접힘(앞 6개 + '+N'). */
  const [expandedChips, setExpandedChips] = useState<Set<number>>(new Set());

  const toggleChips = (presetId: number) =>
    setExpandedChips((prev) => {
      const next = new Set(prev);
      if (next.has(presetId)) next.delete(presetId);
      else next.add(presetId);
      return next;
    });

  const openCreate = () => {
    setEditing(null);
    setModalOpen(true);
  };
  const openEdit = (p: Preset) => {
    setEditing(p);
    setModalOpen(true);
  };

  /** 409 CONFLICT 응답이면 BE 메시지를 그대로 사용자에게 노출 (이벤트 중복 / 이름 중복 분기). */
  const resolveErrorMessage = (err: unknown, fallback: string): string => {
    if (err instanceof ApiError && err.status === 409) {
      return err.userMessage || fallback;
    }
    return fallback;
  };

  const handleSubmit = (form: PresetForm) => {
    if (editing) {
      update.mutate(
        { id: editing.id, form },
        {
          onSuccess: () => {
            pushToast({ variant: 'success', message: '프리셋을 수정했습니다' });
            setModalOpen(false);
          },
          onError: (err) =>
            pushToast({
              variant: 'error',
              message: resolveErrorMessage(err, '프리셋 수정에 실패했습니다'),
            }),
        },
      );
    } else {
      create.mutate(form, {
        onSuccess: () => {
          pushToast({ variant: 'success', message: '프리셋을 추가했습니다' });
          setModalOpen(false);
        },
        onError: (err) =>
          pushToast({
            variant: 'error',
            message: resolveErrorMessage(err, '프리셋 추가에 실패했습니다'),
          }),
      });
    }
  };

  /**
   * 복제 — 클릭 즉시 서버에 요청한다(확인 단계 없음, 사양 SCREEN-026).
   * 서버가 이름 끝에 ' (복사본)' 을 붙인 새 프리셋을 새 ID 로 발급하고 매핑 이벤트는 상속하지 않는다.
   *
   * ★API·훅(`usePresetActions().clone`)은 이전부터 있었으나 화면에 버튼이 없어 도달 불가였다.
   */
  const handleClone = (preset: Preset) => {
    clone.mutate(preset.id, {
      onSuccess: () =>
        pushToast({ variant: 'success', message: '프리셋을 복제했습니다' }),
      onError: (err) =>
        pushToast({
          variant: 'error',
          message: resolveErrorMessage(err, '프리셋 복제에 실패했습니다'),
        }),
    });
  };

  const handleConfirmDelete = () => {
    if (!pendingDelete) return;
    remove.mutate(pendingDelete.id, {
      onSuccess: () =>
        pushToast({ variant: 'success', message: '프리셋을 삭제했습니다' }),
      onError: () =>
        pushToast({ variant: 'error', message: '프리셋 삭제에 실패했습니다' }),
    });
    setPendingDelete(null);
  };

  const presets = useMemo(() => data ?? [], [data]);
  const totalElements = presets.length;
  // 서버 페이징이 아니라 전체 목록을 받아 화면에서 자른다(SCREEN-026) — 페이지 수 계산 규칙은
  // 페이저와 같은 곳(pageCountOf)에서 가져와 0건·나머지 처리가 갈리지 않게 한다.
  const totalPages = pageCountOf(totalElements, PAGE_SIZE);
  const safePage = Math.min(page, totalPages - 1);
  const pageItems = useMemo(
    () => presets.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE),
    [presets, safePage],
  );

  const submitting = create.isPending || update.isPending;

  const formatDate = (s?: string) => {
    if (!s) return '-';
    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? '-' : d.toLocaleDateString('ko-KR');
  };

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        // 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다.
        breadcrumb={[{ label: '관리' }, { label: '프리셋 관리' }]}
        title="프리셋 관리"
        description={`라벨 코드 프리셋 관리 — 전체 ${totalElements.toLocaleString('ko-KR')}개`}
        actions={
          isReviewer ? (
            <Button variant="primary" leftIcon={Plus} onClick={openCreate}>
              프리셋 추가
            </Button>
          ) : undefined
        }
      />

      {error && (
        <div className="rounded-lg border border-gray-200 bg-white shadow-sm">
          <ErrorState title="프리셋 목록을 불러올 수 없습니다" />
        </div>
      )}

      {isLoading ? (
        // 스켈레톤은 실제 카드와 같은 그리드·같은 골격(제목/배지/설명 2줄/칩 3개)으로 둔다 —
        // 폭이 다르면 로딩이 끝나는 순간 레이아웃이 튄다.
        <div className={GRID_CLASS} aria-busy="true">
          {Array.from({ length: SKELETON_COUNT }).map((_, i) => (
            <Card key={i}>
              <CardHeader className="gap-2">
                <div className="flex min-w-0 flex-col gap-2">
                  <Skeleton width="62%" height="1.0625rem" />
                  <Skeleton width="30%" height="1.5rem" className="rounded-full" />
                </div>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                <Skeleton height="0.9375rem" />
                <Skeleton width="70%" height="0.9375rem" />
                <div className="mt-1 flex gap-1.5">
                  {Array.from({ length: 3 }).map((__, k) => (
                    <Skeleton key={k} width="3.5rem" height="1.625rem" className="rounded-full" />
                  ))}
                </div>
              </CardContent>
              <CardFooter>
                <Skeleton width="40%" height="0.875rem" />
              </CardFooter>
            </Card>
          ))}
        </div>
      ) : totalElements === 0 ? (
        <div className="flex flex-col items-center rounded-lg border border-gray-200 bg-white shadow-sm">
          <EmptyState
            icon={<Layers className="h-7 w-7 text-gray-400" aria-hidden />}
            title="등록된 프리셋이 없습니다"
            message="라벨 코드 프리셋을 추가해 라벨링 작업을 표준화하세요."
            className={isReviewer ? 'pb-4' : undefined}
          />
          {/* 1차 액션이라 EmptyState 의 보조(outline) 액션 슬롯이 아니라 primary 버튼으로 둔다. */}
          {isReviewer && (
            <Button variant="primary" leftIcon={Plus} className="mb-12" onClick={openCreate}>
              새 프리셋 만들기
            </Button>
          )}
        </div>
      ) : (
        <div className={GRID_CLASS}>
          {pageItems.map((preset) => {
            const codes = preset.codes;
            const expanded = expandedChips.has(preset.id);
            return (
              <Card
                key={preset.id}
                className="transition duration-200 ease-standard hover:border-gray-300 hover:shadow-md"
              >
                <CardHeader className="gap-2">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    {/* 카드 제목은 CardTitle(p) 이 아니라 h3 로 둔다 — 목록에서 헤딩 탐색이 되어야 한다.
                        `truncate` 라 긴 이름은 말줄임되므로 `title` 로 전문을 남긴다 — 없으면 카드에서
                        전체 이름을 확인할 수단이 아예 없다(같은 카드의 이벤트 배지·수정일도 같은 방식). */}
                    <h3
                      className="min-w-0 truncate text-title-sm text-gray-900"
                      title={preset.name}
                    >
                      {preset.name}
                    </h3>
                    {preset.eventTypeCd ? (
                      <span
                        className="inline-flex shrink-0 items-center rounded-full bg-primary-50 px-2 py-0.5 text-label text-primary-700"
                        data-testid={`preset-event-${preset.id}`}
                        title={`매핑 이벤트: ${preset.eventTypeCd}`}
                      >
                        {catLabel.get(preset.eventTypeCd) ?? preset.eventTypeCd}
                      </span>
                    ) : (
                      <span
                        className="inline-flex shrink-0 items-center rounded-full bg-gray-100 px-2 py-0.5 text-label text-gray-700"
                        data-testid={`preset-event-${preset.id}`}
                      >
                        미매핑
                      </span>
                    )}
                  </div>
                  {isReviewer && (
                    // 밀집 배치라 ghost + sm(36px) 예외를 쓴다 — 카드 우상단에 3개가 나란히 온다.
                    <CardAction className="gap-0.5">
                      <Button
                        variant="ghost"
                        size="sm"
                        leftIcon={Pencil}
                        onClick={() => openEdit(preset)}
                        aria-label="수정"
                      >
                        수정
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        leftIcon={Copy}
                        onClick={() => handleClone(preset)}
                        disabled={clone.isPending}
                        aria-label={`${preset.name} 복제`}
                      >
                        복제
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        leftIcon={Trash2}
                        onClick={() => setPendingDelete(preset)}
                        aria-label="삭제"
                        className="text-danger-700 hover:bg-danger-50"
                      >
                        삭제
                      </Button>
                    </CardAction>
                  )}
                </CardHeader>

                <CardContent className="flex flex-col gap-3">
                  {/* 설명은 2줄까지 보이고 없으면 안내 문구로 자리를 지킨다(사양 SCREEN-026) —
                      비면 카드 높이가 제각각이 되어 그리드 리듬이 깨진다. */}
                  <p
                    className={cn(
                      'line-clamp-2 min-h-12 text-body-sm',
                      preset.description ? 'text-gray-600' : 'text-gray-500',
                    )}
                  >
                    {preset.description || '설명이 없습니다.'}
                  </p>

                  {/* Label codes — 마스터 라벨명·형태 기준 표시(미연결은 배지 구분).
                      ★앞 6개만 칩으로 보이고 나머지는 '+N' 으로 접힌다(사양 SCREEN-026).
                      '+N' 은 정보 표시용 배지가 아니라 **펼치기 토글**이라 접근 가능한 button 이다 —
                      접기만 하고 펼칠 수단이 없으면 나머지 라벨이 화면에서 도달 불가능해진다. */}
                  <div className="flex min-h-7 flex-wrap content-start items-start gap-1.5">
                    {(expanded ? codes : codes.slice(0, VISIBLE_CHIP_COUNT)).map((code) => (
                      <PresetCodeChip
                        key={code.labelId ?? code.code ?? code.labelName}
                        code={code}
                      />
                    ))}
                    {codes.length > VISIBLE_CHIP_COUNT && (
                      <button
                        type="button"
                        onClick={() => toggleChips(preset.id)}
                        aria-expanded={expanded}
                        data-testid={`preset-chip-toggle-${preset.id}`}
                        className={cn(
                          'inline-flex items-center rounded-full transition-colors duration-100 hover:brightness-95',
                          KRDS_FOCUS,
                        )}
                      >
                        {expanded ? (
                          <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-semibold text-gray-700">
                            접기
                          </span>
                        ) : (
                          <PresetLabelOverflowChip
                            count={codes.length - VISIBLE_CHIP_COUNT}
                            className="tabular-nums"
                          />
                        )}
                      </button>
                    )}
                  </div>
                </CardContent>

                {/* 푸터 = 라벨 개수 + 수정일(사양 SCREEN-026). 생성일은 화면에서 접히고
                    수정일 tooltip 으로만 남긴다 — 카드가 전달할 1차 정보가 아니다.
                    ★`mt-auto` 필수 — 그리드가 같은 행의 카드를 가장 높은 카드에 맞춰 늘리는데
                    (칩이 2줄로 넘치거나 '+N' 을 펼친 카드가 행을 밀어올린다) 푸터에 이게 없으면
                    본문 직후에 붙어 카드 바닥과 푸터 사이에 빈 흰 띠가 생긴다. 푸터는
                    `rounded-b-lg` 라 그 상태에서 둥근 아래 모서리가 카드 중간에 떠 보인다. */}
                <CardFooter className="mt-auto flex items-center justify-between gap-2">
                  <span>
                    <strong className="font-semibold text-gray-900 tabular-nums">
                      {codes.length}개
                    </strong>{' '}
                    라벨
                  </span>
                  <span className="tabular-nums" title={`생성 ${formatDate(preset.createdAt)}`}>
                    {formatDate(preset.updatedAt)} 수정
                  </span>
                </CardFooter>
              </Card>
            );
          })}
        </div>
      )}

      {totalElements > 0 && (
        <Pagination page={safePage} totalPages={totalPages} onChange={setPage} />
      )}

      <PresetEditModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        initial={editing ?? undefined}
        onSubmit={handleSubmit}
        submitting={submitting}
      />

      <ConfirmDialog
        open={!!pendingDelete}
        title="프리셋 삭제"
        // 되돌릴 수 없는 삭제라는 사실을 문구로 명시한다(사양 SCREEN-026 · UI-005 지침).
        description={
          pendingDelete ? (
            <>
              <strong className="font-semibold text-gray-900">
                {`'${pendingDelete.name}'`}
              </strong>{' '}
              프리셋을 삭제합니다. 이 작업은 되돌릴 수 없습니다.
            </>
          ) : undefined
        }
        variant="danger"
        confirmLabel="삭제"
        onConfirm={handleConfirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </section>
  );
}
