import { useMemo, useState } from 'react';
import { Layers, Plus } from 'lucide-react';

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
import { PresetCodeChip } from '@/features/preset/components/PresetCodeChip';
import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import { usePresetActions } from '@/features/preset/hooks/usePresetActions';
import { usePresets } from '@/features/preset/hooks/usePresets';
import { type Preset, type PresetForm } from '@/features/preset/types';
import { formatEventTypeDisplay } from '@/features/preset/utils/eventTypeDisplay';
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
 * 3의 배수인 것이 핵심이다 — 그리드가 3열일 때 9면 마지막 줄이 정확히 차고, 10이면
 * 마지막 카드 하나만 남은 줄이 생겨 리듬이 깨진다. 2열 구간에서 마지막 줄이 한 칸 비는 것은
 * 확정 디자인도 같다(디자인의 2열 브레이크포인트에서도 9건이므로) — 값을 열 수에 맞춰
 * 바꾸지 않는다(사양이 9건으로 못박은 값이다).
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
 * 카드 배지 2종 — <b>서로 다른 상태</b>라 한 카드에 함께 붙지 않는다(사양 SCREEN-026).
 *
 * - 「오토라벨 미적용」 : 라벨은 담았는데 전부 AI 검출 클래스에 매핑돼 있지 않아 <b>적용되지
 *   않는</b> 상태. 운영자는 기준을 걸었다고 믿는데 실제로는 걸리지 않은 <b>사고</b>다.
 * - 「오토라벨 제외」   : 라벨을 하나도 담지 않아 그 이벤트유형을 오토라벨링 대상에서 <b>뺀</b>
 *   상태. 사람이 그렇게 선언한 것이라 사고가 아니다.
 *
 * ★두 배지의 판정 축이 다르다 — 앞은 <b>서버가 내려준 실효 여부</b>, 뒤는 <b>라벨 건수</b>다.
 *   ⚠ 실효 여부만으로 가르면 안 된다: 라벨 0건 프리셋도 서버는 실효하지 않는다고 내려주므로
 *   두 배지가 같은 카드에 함께 붙는다. 그래서 <b>라벨 건수를 먼저</b> 보고 배타로 가른다.
 * ★보조 안내 문구는 사양 확정값이다 — 임의로 다듬지 말 것.
 */
const BADGE_INEFFECTIVE = {
  label: '오토라벨 미적용',
  note: '이 프리셋은 오토라벨링에 적용되지 않습니다',
  // 사고 축 — danger-700/danger-50 8.01:1(AA 이상). 글자가 곧 뜻이라 색 단독 구분이 아니다.
  className: 'bg-danger-50 text-danger-700',
} as const;

const BADGE_EXCLUDED = {
  label: '오토라벨 제외',
  note: '이 이벤트유형은 오토라벨링을 하지 않도록 지정되어 있습니다',
  // 선언 축 — 사고가 아니므로 중립 톤(gray-800/gray-100 9.85:1).
  className: 'bg-gray-100 text-gray-800',
} as const;

/**
 * 라벨을 비운 채 저장했을 때의 안내 — 사양 SCREEN-026 확정 문구다.
 *
 * 「추가/수정」으로 갈리지 않고 한 문구인 것도 사양 그대로다 — 사용자가 알아야 하는 사실은
 * 무엇을 눌렀는지가 아니라 <b>그 유형이 오토라벨링 대상에서 빠졌다</b>는 것이다.
 */
const TOAST_SAVED_WITHOUT_LABELS =
  '프리셋을 저장했습니다. 이 이벤트유형은 오토라벨링 대상에서 빠집니다.';

/** 저장은 성공했지만 적용되지 않는다는 사실 — 편집 모달의 경고와 같은 사실을 저장 후에도 알린다. */
const TOAST_INEFFECTIVE_SUFFIX =
  ' 담긴 라벨이 모두 AI 검출 클래스에 매핑되어 있지 않아 이 프리셋은 오토라벨링에 적용되지 않습니다.';

/**
 * 저장 성공 안내 문구 — <b>서버가 돌려준 저장 결과</b>로 정한다(화면이 폼 입력으로 재유도하지 않는다).
 *
 * 판정 순서는 카드 배지와 같다 — 라벨 건수를 먼저 보고, 그다음 실효 여부를 본다.
 */
function saveSuccessMessage(saved: Preset, base: string): string {
  if (saved.codes.length === 0) return TOAST_SAVED_WITHOUT_LABELS;
  return saved.effective ? base : base + TOAST_INEFFECTIVE_SUFFIX;
}

/**
 * 카드 그리드 열 구성 — 좁은 화면 1열 / md(768px) 2열 / **1520px** 3열. [@design SCREEN-026]
 *
 * ★3열 임계는 뷰포트가 아니라 **카드 영역 폭**이 정한다. 확정 디자인의 `.psm-grid` 는
 *   `@media (max-width: 1280px) { 2열 }` 인데 그 캔버스에는 **셸(LNB)이 없어** 뷰포트가 곧
 *   카드 영역이다. 실제 화면은 고정 LNB 240px + 좌우 패딩 48px 을 뺀 나머지가 카드 영역이라,
 *   구 `xl:`(뷰포트 1280) 로는 카드 영역이 992px 밖에 안 되는데도 3열이 된다.
 *   1440 실측에서 카드가 368px 로 눌려 **제목 9/9 가 전부 말줄임**됐다(`교통사고 기...`).
 *   같은 조건을 카드 영역 기준으로 옮기면 1280 + 240 + 48 = **1520px** 이다.
 *
 * ⚠ 이 저장소의 Tailwind `theme.screens` 는 기본 브레이크포인트를 **대체**해 `md`·`xl`
 *   둘만 정의한다(`2xl:` 도 죽은 접두사다) — 그래서 임의 미디어 변형으로 적는다.
 *   `md`(2열) 축은 이번 변경 범위가 아니라 그대로 둔다.
 */
const GRID_CLASS = 'grid grid-cols-1 gap-6 md:grid-cols-2 min-[1520px]:grid-cols-3';

/**
 * SCR-MANAGE-PRESETS 라벨링 프리셋 관리 (SCREEN-026) — REVIEWER 전용.
 *
 * - 반응형 카드 그리드(1/2/3열) + 클라이언트 페이지네이션 (9건/페이지)
 * - 프리셋 추가 / 수정 / 삭제 (REVIEWER만)
 * - 라벨 항목 1~20종 제한 (zod presetSchema.labelIds min(1).max(20))
 *
 * ★프리셋은 이름을 갖지 않는다 — 식별 축이 이벤트유형 하나이므로 카드 제목이 곧
 *   `이벤트명 (유형코드)` 다.
 *
 * @design SCREEN-026
 * @design API-037
 * @design API-040
 * @design UC-032
 * @design AC-114
 * @design AC-119
 */
export function PresetListPage() {
  const { data, isLoading, error } = usePresets();
  const { create, update, remove } = usePresetActions();
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

  /**
   * 409 CONFLICT 응답이면 **서버 메시지를 그대로** 노출한다.
   *
   * 서버는 어느 이벤트가 이미 쓰이고 있는지 표시명과 코드로 밝혀 준다(예:
   * "이미 프리셋이 등록된 이벤트입니다: 배회(EV08000101)"). 화면에서 문구를 새로 만들면
   * 그 구체성이 사라진다 — 프리셋에 이름이 없어져 사용자가 중복을 눈으로 알아채기 어렵다.
   */
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
          onSuccess: (saved) => {
            pushToast({
              variant: 'success',
              message: saveSuccessMessage(saved, '프리셋을 수정했습니다'),
            });
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
        onSuccess: (saved) => {
          pushToast({
            variant: 'success',
            message: saveSuccessMessage(saved, '프리셋을 추가했습니다'),
          });
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
        description={`이벤트유형별 라벨 프리셋 관리 — 전체 ${totalElements.toLocaleString('ko-KR')}개`}
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
        // 스켈레톤은 실제 카드와 같은 그리드·같은 골격(제목 한 줄 + 칩)으로 둔다 —
        // 폭이 다르면 로딩이 끝나는 순간 레이아웃이 튄다.
        <div className={GRID_CLASS} aria-busy="true">
          {Array.from({ length: SKELETON_COUNT }).map((_, i) => (
            <Card key={i}>
              <CardHeader className="gap-2">
                {/* 실제 카드 헤더는 `이벤트명 (코드)` 제목 한 줄이다. */}
                <div className="flex min-w-0 flex-nowrap items-center gap-2">
                  <Skeleton width="72%" height="1.0625rem" />
                </div>
              </CardHeader>
              <CardContent className="flex flex-col gap-2">
                <div className="flex gap-1.5">
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
            message="이벤트유형별 라벨 프리셋을 추가해 오토라벨링을 표준화하세요."
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
            /**
             * 카드 제목 = `이벤트명 (유형코드)`.
             *
             * ★표시명은 **서버가 준 값**(`eventTypeNm`)이다. 화면이 이벤트 목록으로 코드를
             *   역해석하지 않는다 — 필터 옵션 목록은 제외 대분류를 감추므로, 그 목록으로
             *   역해석하면 해당 유형(예: 배회)이 코드로만 노출된다(실제 발생한 결함).
             */
            const title = formatEventTypeDisplay(preset.eventTypeNm, preset.eventTypeCd);
            /**
             * 배지는 <b>배타</b>다 — 라벨 건수를 먼저 보고, 담긴 게 있을 때만 실효 여부를 본다.
             * (라벨 0건 프리셋도 서버는 실효하지 않는다고 내려주므로 순서를 뒤집으면 둘이 함께 붙는다.)
             * 실효 여부는 <b>서버 판정값</b>을 그대로 쓰고 화면이 라벨 매핑을 다시 보지 않는다.
             */
            const badge =
              codes.length === 0
                ? BADGE_EXCLUDED
                : preset.effective
                  ? null
                  : BADGE_INEFFECTIVE;
            return (
              <Card
                key={preset.id}
                className="transition duration-200 ease-standard hover:border-gray-300 hover:shadow-md"
              >
                <CardHeader className="gap-2">
                  {/* @design SCREEN-026 — 카드 헤더는 `제목`(1fr) / `액션`(auto) 2열이고
                      제목 행은 **한 줄**이다(`.psm-card__title-row`). ★`flex-wrap` 을 쓰지 않는다 —
                      사이드바가 있어 카드가 좁은 실제 화면에서 매번 발동해 헤더가 2행이 된다. */}
                  <div className="flex min-w-0 flex-nowrap items-center gap-2">
                    {/* 카드 제목은 CardTitle(p) 이 아니라 h3 로 둔다 — 목록에서 헤딩 탐색이 되어야 한다.
                        `truncate` 라 긴 제목은 말줄임되므로 `title` 로 전문을 남긴다 — 없으면 카드에서
                        전체 이름을 확인할 수단이 아예 없다(수정일도 같은 방식). */}
                    <h3
                      className="min-w-0 truncate text-title-sm text-gray-900"
                      data-testid={`preset-title-${preset.id}`}
                      title={title}
                    >
                      {title}
                    </h3>
                    {/* 제목 옆 배지 — 등록해 두고도 적용되지 않는 프리셋과 일부러 뺀 프리셋을
                        목록에서 바로 알아보게 한다. 보조 안내는 툴팁(title)과 낭독용 문장으로
                        함께 싣는다 — 배지 글자만으로는 "그래서 어떻게 되는가"가 드러나지 않는다.
                        `shrink-0` 필수: 제목이 truncate 라 배지가 눌리면 글자가 잘린다. */}
                    {badge && (
                      <>
                        <span
                          data-testid={`preset-badge-${preset.id}`}
                          title={badge.note}
                          className={cn(
                            'shrink-0 rounded-full px-2 py-0.5 text-label font-semibold',
                            badge.className,
                          )}
                        >
                          {badge.label}
                        </span>
                        <span className="sr-only">{badge.note}</span>
                      </>
                    )}
                  </div>
                  {isReviewer && (
                    // 밀집 배치라 ghost + sm(36px) 예외를 쓴다 — 카드 우상단에 2개가 나란히 온다.
                    //
                    // ★아이콘을 붙이지 않는다(@design SCREEN-026) — 확정 디자인의
                    //   `.psm-card__actions` 는 **텍스트 전용 ghost 버튼**이고 컴포넌트 사양도
                    //   label 만 규정한다. 아이콘을 붙이면 버튼 하나마다 20px(아이콘 14 + gap 6)씩
                    //   액션 열로 가는데 그 폭은 전부 1fr 열(제목)에서 빠져나가 제목이 잘렸다.
                    // ★`px-2` 도 디자인 값(8px)이다 — Button `sm` 기본은 px-3(12px)이다.
                    <CardAction className="gap-0.5">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="px-2"
                        onClick={() => openEdit(preset)}
                        aria-label={`${title} 프리셋 수정`}
                      >
                        수정
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setPendingDelete(preset)}
                        aria-label={`${title} 프리셋 삭제`}
                        className="px-2 text-danger-700 hover:bg-danger-50"
                      >
                        삭제
                      </Button>
                    </CardAction>
                  )}
                </CardHeader>

                <CardContent className="flex flex-col gap-3">
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
        // ★어느 <b>이벤트</b>의 프리셋인지로 밝힌다 — 프리셋에는 이름이 없다.
        description={
          pendingDelete ? (
            <>
              <strong className="font-semibold text-gray-900">
                {formatEventTypeDisplay(pendingDelete.eventTypeNm, pendingDelete.eventTypeCd)}
              </strong>{' '}
              이벤트의 프리셋을 삭제합니다. 이 작업은 되돌릴 수 없습니다.
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
