import { cn } from '@/lib/cn';
import { STAGE_BUNDLE_MEMBERS } from '@/features/video/types';
import type { BatchStageItem, BatchStageStatus, StageBundle } from '@/features/video/types';

export type { BatchStageItem, BatchStageStatus } from '@/features/video/types';

interface BatchStageIndicatorProps {
  stages: BatchStageItem[];
}

// BE canonical 단계 코드(BatchStage.name) → 사용자 한글 라벨.
// ⚠ 기술 모델명(YOLO/SAM2) 화면 노출 금지 → "AI 탐지"/"AI 분할" (코드/name 은 유지).
// BE 가 stages 배열의 순서/상태를 canonical 로 내려주므로 FE 는 순서를 가정하지 않고
// 배열을 그대로 렌더하며 name→라벨 매핑만 한다.
const STAGE_LABEL: Record<string, string> = {
  DEIDENTIFY: '비식별',
  MARKING: '마킹',
  VLM: 'VLM',
  FRAME_EXTRACT: '프레임추출',
  YOLO: 'AI 탐지',
  SAM2: 'AI 분할',
  INTERPOLATE: '보간',
};

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
 * 작업 묶음 → 사용자 노출명. **`stageLabel` 에서 파생**한다. [@design API-198] [@design SCREEN-009]
 *
 * ★ 묶음 전용 이름표를 따로 만들지 않는다 — 만들면 `AUTOLABEL`→'오토라벨' 같은 <b>두 번째 진실원</b>이
 * 생겨, 위 매핑에서 단계 이름을 바꿔도 묶음 쪽은 옛 이름으로 남는다. 대신 <b>묶음이 품는 단계들의
 * 노출명을 그대로 이어 붙인다</b>(멤버 목록의 진실원은 `STAGE_BUNDLE_MEMBERS` 하나다).
 *
 * 부수 효과가 오히려 사양에 맞는다 — 오토라벨 묶음이 화면에 "AI 탐지 · AI 분할 · 보간"으로 적혀
 * <b>보간이 이 묶음 안에 있다는 사실</b>이 이름만으로 드러난다(그것이 이번 변경의 핵심이다).
 */
export function bundleLabel(bundle: StageBundle): string {
  return (STAGE_BUNDLE_MEMBERS[bundle] as readonly string[]).map(stageLabel).join(' · ');
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

/**
 * 스크린리더에 읽힐 현재 상황 한 줄.
 *
 * 시각적으로는 점 색상 + 캡션으로 진행 단계가 보이지만, 그 변화는 보조기술에 전달되지
 * 않는다 — 캡션이 상태 문구를 함께 적게 된 뒤에도 마찬가지다. 라이브 리전 밖의 텍스트가
 * 바뀌는 것은 낭독 대상이 아니기 때문이다(캡션은 **보는** 축, 이 한 줄은 **듣는** 축이다).
 * 진행 중인 단계가 있으면 그 단계를, 없으면(전부 끝났거나 실패로 멈췄으면) 마지막으로
 * 의미 있는 단계를 알린다.
 */
export function liveStageMessage(stages: BatchStageItem[]): string {
  if (!stages || stages.length === 0) return '';
  const target =
    stages.find((s) => s.status === 'PROGRESS') ??
    stages.find((s) => s.status === 'FAIL') ??
    [...stages].reverse().find((s) => s.status === 'DONE') ??
    stages[0]!;
  return `${stageLabel(target.name)} ${STATUS_PHRASE[target.status]}`;
}

/** @design UI-018 */
export function BatchStageIndicator({ stages }: BatchStageIndicatorProps) {
  // BE stages 가 비면(배치 로그 없는 기존 영상) 아무것도 렌더 안 함 → 상위 배지 폴백(하위호환).
  if (!stages || stages.length === 0) return null;

  return (
    <div className="flex items-center gap-0" data-testid="batch-stage-indicator">
      {/* 화면에는 보이지 않는 라이브 리전 — 진행 단계 변화를 스크린리더에 안내한다. */}
      <span className="sr-only" aria-live="polite" data-testid="batch-stage-live">
        {liveStageMessage(stages)}
      </span>
      {stages.map((stage, idx) => {
        const isLast = idx === stages.length - 1;

        return (
          // ⚠ `items-start` + 연결선 `mt-4` — 연결선을 점 중심(위에서 16px = 32px 점의 절반)에
          //    고정한다. 구 구현은 `items-center` + `mb-4` 로 **캡션 높이에 의존**해 맞춰져 있어,
          //    캡션이 두 줄이 되면 연결선이 점에서 어긋난다.
          <div key={stage.name} className="flex items-start">
            <div
              className="flex flex-col items-center gap-1"
              data-testid={`batch-stage-item-${stage.name}`}
            >
              <StageDot status={stage.status} />
              {/* 캡션 — 단계명과 상태를 **두 줄**로 적는다.
                  한 줄(`비식별 완료`)로 이으면 7단계가 가로로 늘어선 이 스테퍼의 폭이 단계마다
                  1.8배가 된다(상위 화면은 `overflow-x-auto` 라 잘리진 않으나 스크롤이 상시화된다).
                  줄을 나누면 폭은 두 줄 중 긴 쪽(=기존 단계명)이라 **가로 폭이 늘지 않는다**.
                  ladder `caption`(14px) → ⚠ 인라인 `fontSize: '10px'` 이 최종적으로 이긴다. */}
              <span
                className="text-caption text-gray-600 text-center whitespace-nowrap leading-tight flex flex-col"
                style={{ fontSize: '10px' }}
              >
                <span data-testid={`batch-stage-name-${stage.name}`}>
                  {stageLabel(stage.name)}
                </span>
                <span data-testid={`batch-stage-status-${stage.name}`}>
                  {STATUS_PHRASE[stage.status]}
                </span>
              </span>
            </div>
            {!isLast && (
              <div className={cn('flex-1 h-0.5 w-6 mx-1 mt-4', connectorColor(stage.status))} />
            )}
          </div>
        );
      })}
    </div>
  );
}

export default BatchStageIndicator;
