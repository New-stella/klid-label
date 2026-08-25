import { cn } from '@/lib/cn';
import { STAGE_BUNDLE_MEMBERS } from '@/features/video/types';
import type { BatchStageItem, BatchStageStatus, StageBundle } from '@/features/video/types';

export type { BatchStageItem, BatchStageStatus } from '@/features/video/types';

interface BatchStageIndicatorProps {
  stages: BatchStageItem[];
  /**
   * 칸을 컨테이너 **전폭에 균등 분산**한다. [@design SCREEN-009]
   *
   * 확정 디자인의 `.stage-step` 은 `flex: 1 1 0` 이라 각 칸이 같은 폭을 갖고 점이 그 칸의
   * 중앙에 온다(연결선은 점 중심에서 다음 점 중심까지). 구 구현은 칸이 **내용 폭**이라
   * 전폭 밴드 안에서도 좌측 273px 에 뭉쳤고, 캡션을 두 줄로 접고 10px 로 줄여야 했다.
   *
   * ⚠ **기본값은 `false`(구 동작 유지)이지만 그 기본값으로 쓰는 화면은 지금 없다.** 기본값의
   * 근거였던 인라인 배치처(마킹 화면 헤더)가 사라졌고, 남은 사용처는 영상 상세 1곳인데 거기서는
   * 항상 `fill` 을 켠다. prop·기본값·비-fill 렌더 경로는 그대로 두되(정리는 별건), 그 경로를
   * 지금 어느 화면도 쓰지 않는다는 것을 알고 볼 것.
   */
  fill?: boolean;
}

// BE canonical 단계 코드(BatchStage.name) → 사용자 한글 라벨.
// ⚠ 기술 모델명(YOLO/SAM2/VLM) 화면 노출 금지 → "AI 탐지"/"AI 분할"/"시계열" (코드/name 은 유지).
// BE 가 stages 배열의 순서/상태를 canonical 로 내려주므로 FE 는 순서를 가정하지 않고
// 배열을 그대로 렌더하며 name→라벨 매핑만 한다.
//
// ★ 이 표는 단계 표시명의 **유일한 정의처**다 — 단계 배지(StageBadge)도 자기 표를 갖지 않고
//   `stageLabel`/`hasStageLabel` 로 여기에 위임한다. 표를 복제하면 한쪽만 갱신돼 같은 단계가
//   화면마다 다른 이름으로 불린다(실제로 DEIDENTIFY 가 '비식별'/'비식별화' 두 이름이었다).
const STAGE_LABEL: Record<string, string> = {
  DEIDENTIFY: '비식별',
  MARKING: '마킹',
  VLM: '시계열',
  FRAME_EXTRACT: '프레임추출',
  YOLO: 'AI 탐지',
  SAM2: 'AI 분할',
  INTERPOLATE: '보간',
};

/**
 * 이 표가 이름을 규정하는 단계 코드 전체. **회귀 가드가 전수 비교에 쓴다** — 코드를 손으로
 * 나열하면 표에 단계가 추가돼도 가드가 늘지 않아 사각이 생긴다.
 */
export const STAGE_LABEL_CODES: readonly string[] = Object.keys(STAGE_LABEL);

// 매핑에 없는 단계 코드(BE 가 향후 단계 추가 시)는 기술 코드명이 화면에 새지 않도록
// 한글 기본값으로 폴백한다(기술 코드명 노출 금지).
const STAGE_LABEL_FALLBACK = '처리중';

/**
 * 단계 코드 → 사용자 노출명. **이 매핑의 유일한 공개 창구**다.
 *
 * 배치 실패 패널(BatchFailurePanel)처럼 같은 단계를 부르는 다른 화면이 자기 매핑표를 갖지 않도록
 * 함수로 내보낸다 — 표를 복제하면 한쪽만 갱신돼 같은 단계가 화면마다 다른 이름으로 불린다
 * (이 저장소의 반복 결함 패턴).
 */
export function stageLabel(name: string): string {
  return STAGE_LABEL[name] ?? STAGE_LABEL_FALLBACK;
}

/**
 * 이 표가 그 단계 코드의 이름을 규정하는가.
 *
 * ⚠ 폴백 정책은 화면마다 **다른 것이 사양**이라 판정과 폴백을 분리해 내보낸다 —
 * 스테퍼는 매핑 밖 코드를 `처리중`으로 덮고(UI-018: 기술 코드명 노출 금지), 배지는 원문 코드를
 * 그대로 보여준다(UI-017). 두 정책을 "일관성"을 이유로 통일하지 말 것.
 */
export function hasStageLabel(name: string): boolean {
  return Object.prototype.hasOwnProperty.call(STAGE_LABEL, name);
}

/**
 * 작업 묶음 → 사용자 노출명. **묶음 표시명의 유일한 정의처**다. [@design API-198] [@design SCREEN-009]
 *
 * ★ 스테퍼 캡션과 조작 UI(건너뛰기·재수행 버튼·모달·토스트)가 <b>이 표 하나</b>를 공유한다.
 * 두 축이 각자 이름을 정하면 같은 묶음이 한 화면에서 두 이름으로 불린다 — 실제로 그랬다.
 *
 * ⚠ 구 동작 폐기: 묶음 이름을 <b>멤버 단계명의 조합</b>으로 만들어 오토라벨 묶음이
 * "AI 탐지 · AI 분할 · 보간"으로 적혔다. 사양(SCREEN-009)이 이 묶음을 부르는 어휘는 「오토라벨」이므로
 * 구현을 사양으로 되돌린 것이다(사양 되돌리기가 아니다).
 *
 * ⚠ <b>잃은 것을 알고 하는 선택이다</b> — 멤버 나열 이름은 이름만으로 <b>보간이 이 묶음 안에 있다는
 * 사실</b>을 알렸고, 오토라벨 재수행은 사람이 손댄 보간 라벨을 새로 계산해 덮어쓰는 파괴적 조작이다.
 * 그 고지는 이제 <b>재수행 경고 문단 + 확인 창 본문</b>이 전담한다(`BatchFailurePanel` — 둘 다 보간
 * 재계산을 명시한다). 그 문구를 지우면 이 이름 변경이 곧 고지 소실이 된다.
 */
const BUNDLE_LABEL: Record<StageBundle, string> = {
  // 멤버가 하나뿐이라 이름을 손으로 적지 않고 단계 표에서 파생한다(같은 이름이 두 곳에 생기지 않는다).
  VLM: stageLabel('VLM'),
  AUTOLABEL: '오토라벨링',
};

export function bundleLabel(bundle: StageBundle): string {
  return BUNDLE_LABEL[bundle];
}

// 단계 표식은 **원형 점 + 연결선 색**으로 통일한다(2026-08-10 확정).
// ★ 구 구현은 DONE=체크·PROGRESS=스피너·FAIL=X 아이콘이었고 PENDING 만 회색 점이라 표현이 갈렸다.
//   아이콘을 폐지하며 이미 있던 "회색 빈 점" 표현으로 4상태를 맞춘다. ⚠ 아이콘을 되살리지 말 것.
//
// ⚠ 점은 **4상태 모두 모양·크기가 같다** — 즉 이 점이 나르는 정보는 색뿐이다. 캡션이 단계명만
//   적으면 상태 축은 색 단독이 되고, grayscale 로 보면 완료(초록)와 실패(빨강)가 같아진다
//   (적록색약이 겪는 쌍). 그래서 캡션이 **단계명 + 상태 문구**를 함께 적는다 — 색을 대신하는
//   유일한 구분 수단이다(2026-08-10 브라우저 실검증에서 드러난 결함, UI-018 사양).
const DOT_TONE: Record<BatchStageStatus, { ring: string; dot: string }> = {
  DONE: { ring: 'bg-success/10 border-success', dot: 'bg-success' },
  PROGRESS: { ring: 'bg-info/10 border-info', dot: 'bg-info' },
  FAIL: { ring: 'bg-danger/10 border-danger', dot: 'bg-danger' },
  PENDING: { ring: 'bg-gray-200 border-gray-300', dot: 'bg-gray-400' },
};

function StageDot({ status }: { status: BatchStageStatus }) {
  const tone = DOT_TONE[status];
  return (
    <div
      className={cn(
        'w-8 h-8 rounded-full border-2 flex items-center justify-center',
        tone.ring,
      )}
    >
      <div className={cn('w-2 h-2 rounded-full', tone.dot)} />
    </div>
  );
}

function connectorColor(status: BatchStageStatus): string {
  if (status === 'DONE') return 'bg-success';
  return 'bg-gray-200';
}

// 상태 문구 — 캡션과 `aria-live` 안내가 **같은 상수**를 쓴다.
// 복제하면 한쪽만 갱신돼 화면과 낭독이 갈린다(이 저장소의 반복 결함 패턴).
// 어휘는 앱의 기존 표현을 그대로 따른다: 완료·실패·대기는 StatusBadge/StageBadge 라벨과 동일,
// '진행 중'은 이 컴포넌트가 이미 낭독에 쓰던 문구다(새 어휘 도입 0).
const STATUS_PHRASE: Record<BatchStageStatus, string> = {
  DONE: '완료',
  PROGRESS: '진행 중',
  FAIL: '실패',
  PENDING: '대기',
};

// ── 오토라벨 묶음 접기(표시 층) ──────────────────────────────────────────────
// @design UI-018 (v7)
//
// ★ 스테퍼는 7단계를 7칸으로 그리지 않는다 — 오토라벨 세 단계를 **한 칸으로 접어 5칸**으로
//   보여준다. 근거는 <b>표시 단위를 조작 단위에 맞추는 것</b>이다: 건너뛰기·해제·재수행이
//   오토라벨 묶음 단위로만 동작하는데 세 칸으로 나뉘어 보이면 각 칸을 따로 조작할 수 있다고 읽힌다.
//
// ⚠ 접는 것은 **표시 층뿐**이다 — props(`stages`)·BE 응답 계약·`STAGE_BUNDLE_MEMBERS` 는 무변경.

/** 접기 대상 묶음 — 멤버 목록의 진실원은 `STAGE_BUNDLE_MEMBERS` 하나다. */
const COLLAPSED_BUNDLE: StageBundle = 'AUTOLABEL';

/**
 * 접은 칸의 표시명 — **`bundleLabel` 에서 파생**한다(자기 이름을 따로 적지 않는다).
 * [@design UI-018] [@design SCREEN-009]
 *
 * ⚠ 구 동작 폐기: 이 상수가 이름을 직접 적고 조작 UI 는 멤버 나열을 쓰면서, <b>같은 묶음이 한
 * 화면에서 두 이름</b>으로 보였다. 스테퍼 캡션과 조작 UI 는 같은 것을 가리키므로 같은 이름이어야
 * 한다 — 여기서 문자열을 다시 적으면 그 상태로 되돌아간다.
 */
export const COLLAPSED_BUNDLE_LABEL = bundleLabel(COLLAPSED_BUNDLE);

/** 접힐 단계 코드 집합 — 멤버 표에서 파생(별도로 적지 않는다). */
const COLLAPSED_MEMBER_NAMES = STAGE_BUNDLE_MEMBERS[COLLAPSED_BUNDLE] as readonly string[];

/** 스테퍼가 실제로 그리는 한 칸 — 단계 1개이거나, 오토라벨 묶음처럼 여러 단계를 접은 것이다. */
export interface BatchStageCell {
  /** 렌더 key 이자 testid 접미사. 접은 칸은 묶음 코드(`AUTOLABEL`). */
  key: string;
  /** 캡션 첫 줄(단계명 또는 묶음명). */
  label: string;
  /** 캡션 둘째 줄의 근거가 되는 상태(접은 칸은 멤버들을 합쳐 판정). */
  status: BatchStageStatus;
  /** 보조 표기 — 접은 칸이 진행 중/실패일 때 어느 세부 단계인지. 없으면 null. */
  note: string | null;
  /** 이 칸이 품는 원본 단계들(접지 않은 칸은 1개). */
  members: BatchStageItem[];
}

/**
 * 접은 칸의 상태 — 멤버 상태를 **합쳐** 판정한다. `FAIL > PROGRESS > DONE > PENDING`.
 *
 * 사양이 규정한 것은 세 경우다 — 하나라도 실패면 실패, 실패가 없고 하나라도 진행 중이면
 * 진행 중, **셋 다 끝났으면** 완료. 나머지(전부 대기 / 일부만 완료)는 사양에 없으므로
 * <b>완료도 진행 중도 아닌 `PENDING`</b>으로 둔다 — 아직 아무 단계도 돌고 있지 않고 묶음이
 * 끝나지도 않은 상태이며, 이 방향이 과대 보고가 아닌 쪽이다(완료로 적으면 남은 단계가
 * 끝난 것처럼 읽힌다).
 */
export function collapsedStatus(members: BatchStageItem[]): BatchStageStatus {
  if (members.some((m) => m.status === 'FAIL')) return 'FAIL';
  if (members.some((m) => m.status === 'PROGRESS')) return 'PROGRESS';
  if (members.length > 0 && members.every((m) => m.status === 'DONE')) return 'DONE';
  return 'PENDING';
}

/**
 * 접은 칸의 보조 표기 — 진행 해상도를 잃지 않기 위한 **글자**다(백분율 진행률 바가 아니다).
 *
 * 완료·대기에는 두지 않는다 — 적을 대상(지금 도는 단계 / 실패한 단계)이 없다.
 * 여러 멤버가 동시에 실패·진행 중이면 **BE 가 내려준 배열 순서상 앞선 것**을 적는다:
 * 뒤 단계가 앞 단계의 산출물을 입력으로 받으므로 앞선 실패가 원인이고, 순서 기반이라
 * 같은 입력에 항상 같은 문구가 나온다(사양 미규정 구간의 결정).
 */
function collapsedNote(status: BatchStageStatus, members: BatchStageItem[]): string | null {
  if (status === 'FAIL') {
    const failed = members.find((m) => m.status === 'FAIL');
    return failed ? `${stageLabel(failed.name)}에서 ${STATUS_PHRASE.FAIL}` : null;
  }
  if (status === 'PROGRESS') {
    const running = members.find((m) => m.status === 'PROGRESS');
    return running ? `${stageLabel(running.name)} ${STATUS_PHRASE.PROGRESS}` : null;
  }
  return null;
}

/**
 * BE 단계 배열 → 화면이 그릴 칸 목록. **입력 배열을 변형하지 않는다**(표시 층 전용 파생).
 *
 * ⚠ `stages.map` 을 전제하지 않는다 — 서버가 오토라벨 세 단계를 <b>일부만</b> 내려주거나
 * <b>순서를 달리</b> 실어도 깨지지 않아야 한다. 첫 멤버가 나타난 자리에 접은 칸을 한 번만
 * 놓고, 흩어져 있는 나머지 멤버도 그 칸이 흡수한다. 멤버가 하나도 없으면 접은 칸을 만들지 않는다.
 */
export function collapseStages(stages: BatchStageItem[]): BatchStageCell[] {
  if (!stages || stages.length === 0) return [];

  const members = stages.filter((s) => COLLAPSED_MEMBER_NAMES.includes(s.name));
  const cells: BatchStageCell[] = [];
  let collapsedPlaced = false;

  for (const stage of stages) {
    if (COLLAPSED_MEMBER_NAMES.includes(stage.name)) {
      if (collapsedPlaced) continue; // 이미 접은 칸에 흡수됨
      collapsedPlaced = true;
      const status = collapsedStatus(members);
      cells.push({
        key: COLLAPSED_BUNDLE,
        label: COLLAPSED_BUNDLE_LABEL,
        status,
        note: collapsedNote(status, members),
        members,
      });
      continue;
    }
    cells.push({
      key: stage.name,
      label: stageLabel(stage.name),
      status: stage.status,
      note: null,
      members: [stage],
    });
  }

  return cells;
}

/**
 * 스크린리더에 읽힐 현재 상황 한 줄.
 *
 * 시각적으로는 점 색상 + 캡션으로 진행 단계가 보이지만, 그 변화는 보조기술에 전달되지
 * 않는다 — 캡션이 상태 문구를 함께 적게 된 뒤에도 마찬가지다. 라이브 리전 밖의 텍스트가
 * 바뀌는 것은 낭독 대상이 아니기 때문이다(캡션은 **보는** 축, 이 한 줄은 **듣는** 축이다).
 * 진행 중인 칸이 있으면 그 칸을, 없으면(전부 끝났거나 실패로 멈췄으면) 마지막으로
 * 의미 있는 칸을 알린다.
 *
 * ⚠ 판정 축은 **화면과 같은 5칸**이다 — 단계 배열로 판정하면 화면은 '오토라벨링 실패'인데
 * 낭독은 'AI 분할 실패'가 되어 보는 것과 듣는 것이 갈린다. 접은 칸이면 보조 표기까지 함께
 * 읽어 세부 단계 해상도를 잃지 않는다.
 */
export function liveStageMessage(stages: BatchStageItem[]): string {
  const cells = collapseStages(stages);
  if (cells.length === 0) return '';
  const target =
    cells.find((c) => c.status === 'PROGRESS') ??
    cells.find((c) => c.status === 'FAIL') ??
    [...cells].reverse().find((c) => c.status === 'DONE') ??
    cells[0]!;
  const head = `${target.label} ${STATUS_PHRASE[target.status]}`;
  return target.note ? `${head} — ${target.note}` : head;
}

/** @design UI-018 @design SCREEN-009 */
export function BatchStageIndicator({ stages, fill = false }: BatchStageIndicatorProps) {
  // BE stages 가 비면(배치 로그 없는 기존 영상) 아무것도 렌더 안 함 → 상위 배지 폴백(하위호환).
  if (!stages || stages.length === 0) return null;

  // @design UI-018 — 오토라벨 세 단계를 한 칸으로 접어 5칸으로 그린다(표시 층 전용 파생).
  const cells = collapseStages(stages);

  return (
    <div
      className={cn('flex gap-0', fill ? 'w-full items-start' : 'items-center')}
      data-testid="batch-stage-indicator"
    >
      {/* 화면에는 보이지 않는 라이브 리전 — 진행 단계 변화를 스크린리더에 안내한다. */}
      <span className="sr-only" aria-live="polite" data-testid="batch-stage-live">
        {liveStageMessage(stages)}
      </span>
      {cells.map((cell, idx) => {
        const isLast = idx === cells.length - 1;
        // 캡션의 두 조각 — 배치만 모드에 따라 달라지고 **testid·문구는 두 모드가 동일**하다.
        // 조각을 모드별로 복제하면 한쪽만 갱신돼 같은 캡션이 화면마다 달라진다.
        const nameSpan = <span data-testid={`batch-stage-name-${cell.key}`}>{cell.label}</span>;
        const statusSpan = (
          <span data-testid={`batch-stage-status-${cell.key}`}>{STATUS_PHRASE[cell.status]}</span>
        );

        return (
          // ⚠ `items-start` + 연결선 `mt-4` — 연결선을 점 중심(위에서 16px = 32px 점의 절반)에
          //    고정한다. 구 구현은 `items-center` + `mb-4` 로 **캡션 높이에 의존**해 맞춰져 있어,
          //    캡션이 두 줄이 되면 연결선이 점에서 어긋난다.
          // fill 모드는 칸이 `flex-1` 이라 점이 칸 중앙에 오고, 연결선은 흐름에서 빼내
          //    **점 중심 기준 절대배치**한다(칸 폭이 커져도 선이 점에서 떨어지지 않는다).
          <div
            key={cell.key}
            className={cn('flex items-start', fill && 'relative min-w-0 flex-1 justify-center')}
          >
            <div
              className="flex flex-col items-center gap-1"
              data-testid={`batch-stage-item-${cell.key}`}
            >
              <StageDot status={cell.status} />
              {/* 캡션 — 단계명(또는 묶음명)과 상태.
                  · fill: 확정 디자인대로 **한 줄**(`비식별 완료`) + 앱 캡션 크기(14px).
                    칸이 균등 분산돼 가로 여유가 있으므로 줄을 접거나 글자를 줄일 이유가 없다.
                  · 기본: **두 줄** + 10px. 한 줄로 이으면 칸 폭이 1.8배가 되는데, 이 모드는
                    내용 폭으로 인라인 배치되는 자리(마킹 화면 헤더)라 옆 요소를 밀어낸다.
                    ladder `caption`(14px) → ⚠ 인라인 `fontSize: '10px'` 이 최종적으로 이긴다. */}
              <span
                className="text-caption text-gray-600 text-center whitespace-nowrap leading-tight flex flex-col"
                style={fill ? undefined : { fontSize: '10px' }}
              >
                {fill ? (
                  <span>
                    {nameSpan} {statusSpan}
                  </span>
                ) : (
                  <>
                    {nameSpan}
                    {statusSpan}
                  </>
                )}
                {/* 보조 표기 — 접은 칸의 세부 단계(진행 중/실패일 때만). 색이 아니라 **글자**로
                    적어 색을 읽지 못해도 어느 세부 단계인지 알 수 있게 한다(UI-018 v7). */}
                {cell.note && (
                  <span className="text-gray-500" data-testid={`batch-stage-note-${cell.key}`}>
                    {cell.note}
                  </span>
                )}
              </span>
            </div>
            {!isLast &&
              (fill ? (
                // 점 중심(+18px = 점 반지름 16 + 여백 2)에서 다음 점 중심 직전까지. 점 위를
                // 지나가지 않으므로 점의 반투명 배경으로 선이 비쳐 보이지 않는다.
                <span
                  aria-hidden="true"
                  className={cn('absolute h-0.5', connectorColor(cell.status))}
                  style={{ top: 15, left: 'calc(50% + 18px)', right: 'calc(-50% + 18px)' }}
                />
              ) : (
                <div className={cn('flex-1 h-0.5 w-6 mx-1 mt-4', connectorColor(cell.status))} />
              ))}
          </div>
        );
      })}
    </div>
  );
}

export default BatchStageIndicator;
