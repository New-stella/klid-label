import { useId } from 'react';
import { Clock, Shield } from 'lucide-react';

import { Badge } from '@/components/common/Badge';
import type { DeidentHistoryItem } from '@/features/video/types';
import { cn } from '@/lib/cn';

/**
 * 비식별 이력 패널 — 영상 상세 「기본 정보」 탭. [req: R14]
 *
 * 한 항목 = 비식별 위탁 1회차(BE `LS_DEIDENT_PROC_LOG` 1행). 최초 배치 비식별과 검수완료 후
 * 재비식별이 각각 한 행을 남기므로, 이 목록이 "이 영상을 언제 몇 번 비식별했고 무엇을 얼마나
 * 가렸는가" 를 그대로 보여준다.
 *
 * 표(`<table>`)를 쓰지 않는 이유: 이 화면의 다른 메타 항목과 같은 카드 나열이 자연스럽고,
 * 열 정렬·헤더가 필요할 만큼 열이 많지 않다.
 *
 * 표시 정책
 *  - 검출 집계가 없는 회차(구 데이터/리포트 조회 실패)는 집계 줄을 아예 감춘다 —
 *    0 으로 채워 보여주면 "0건 검출" 과 구분되지 않는다.
 *  - 파일 경로는 BE 가 내려주지 않으며 화면도 요구하지 않는다(개인정보 위치 정보).
 */

/** 처리 상태 코드 → 사용자 문구. 미지의 코드는 코드 그대로 노출하지 않고 '진행 중' 으로 둔다. */
const STATUS_LABEL: Record<string, string> = {
  SUCCEEDED: '완료',
  FAILED: '실패',
  REQUESTED: '진행 중',
};

/**
 * 상태별 배지 색 — KRDS 의미상태 토큰. 세 상태가 **한 규칙**을 따른다: `/10` 틴트 배경 + 700단 글자.
 *
 * [@design SD-004] '진행 중'(REQUESTED)은 **info** 계열이다(시안 `--i-0` 배경 / `--i-7` 글자).
 * 아직 아무 문제도 일어나지 않은 진행 상태에 warning(주황)을 쓰면 사용자가 조치가 필요한
 * 상태로 읽는다 — 되돌리지 말 것. 완료=success · 실패=danger 는 계열이 그대로다.
 *
 * ⚠ 글자가 DEFAULT 가 아니라 700 단인 이유: 각 색의 DEFAULT 단은 **자기 `/10` 틴트 위에서**
 * AA(4.5)에 못 미친다(success 4.03 · danger 3.95 · info 4.05). 700 단은 6.72~7.77 로 통과한다.
 * `src/test/contrastGuard.test.ts` 가 tailwind 토큰 실값으로 이 셋을 계산해 고정한다.
 */
const STATUS_CLASS: Record<string, string> = {
  SUCCEEDED: 'bg-success/10 text-success-700',
  FAILED: 'bg-danger/10 text-danger-700',
  REQUESTED: 'bg-info/10 text-info-700',
};

function statusLabel(code: string): string {
  return STATUS_LABEL[code] ?? '진행 중';
}

function statusClass(code: string): string {
  return STATUS_CLASS[code] ?? STATUS_CLASS.REQUESTED;
}

/** ISO 문자열 → 'YYYY-MM-DD HH:mm'. 값이 없으면 '-'. */
function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  return value.slice(0, 16).replace('T', ' ');
}

function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? '-' : value.toLocaleString('ko-KR');
}

/** 집계가 하나라도 있으면 집계 줄을 그린다(전부 없으면 감춘다). */
function hasDetectionCounts(item: DeidentHistoryItem): boolean {
  return item.faceDtctCnt !== null || item.noPltDtctCnt !== null || item.frmeCnt !== null;
}

/** 회차 종류 이름 — `REDEIDENT` 는 검수 완료 뒤 다시 위탁한 회차다. */
function kindLabel(reqKndCd: string | null | undefined): string {
  return reqKndCd === 'REDEIDENT' ? '재비식별' : '비식별';
}

/**
 * 회차 종류 부제 — 이름만으로는 그 회차가 어디서 생겼는지 읽히지 않는다. [@design SCREEN-009]
 *
 * 종류 축은 두 값(최초 배치분 · 재처리분)뿐이라 부제도 둘이다. 상태(성공·실패)와 섞지 않는다 —
 * 부제를 실패 색으로 붉히면 회차 '종류' 자체가 문제인 것처럼 읽힌다(시안 주석과 같은 이유).
 */
function kindSubLabel(reqKndCd: string | null | undefined): string {
  return reqKndCd === 'REDEIDENT' ? '검수 완료 후 재비식별' : '배치 비식별';
}

/**
 * 항목 좌측 강조선의 상태 축 — 실패·진행 중에만 세우고 완료는 평선이다. [@design SCREEN-009]
 *
 * 폴백이 진행 중인 것은 `statusLabel`/`statusClass` 와 **같은 축**이어야 하기 때문이다. 알 수 없는
 * 코드를 '진행 중' 이라 부르면서 강조선만 다른 상태로 그리면 한 항목이 두 상태를 말하게 된다.
 */
function itemState(code: string): 'fail' | 'progress' | undefined {
  if (code === 'SUCCEEDED') return undefined;
  return code === 'FAILED' ? 'fail' : 'progress';
}

/**
 * 항목 테두리 — **기본 회색까지 이 함수가 통째로 소유한다**.
 *
 * ⚠ 기본 클래스에 `border-gray-200` 을 두고 상태 클래스로 덮는 방식은 쓰지 않는다. 둘은 같은
 *   `border-color` 그룹이라 승자를 정하는 것은 JSX 의 나열 순서가 아니라 **생성된 CSS 의 순서**이고,
 *   실제 빌드에서 `.border-danger-200` 이 `.border-gray-200` 보다 먼저 나와 회색이 이긴다.
 */
const ITEM_STATE_CLASS: Record<'fail' | 'progress', string> = {
  fail: 'border-danger-200 border-l-4 border-l-danger',
  progress: 'border-primary-200 border-l-4 border-l-primary',
};

function itemStateClass(code: string): string {
  const state = itemState(code);
  return state ? ITEM_STATE_CLASS[state] : 'border-gray-200';
}

export function DeidentHistoryPanel({ history }: { history: DeidentHistoryItem[] }) {
  const titleId = useId();

  return (
    <section
      aria-labelledby={titleId}
      data-testid="deident-history-panel"
      className="flex flex-col gap-4 rounded-lg border border-l-4 border-gray-200 border-l-gray-400 bg-white p-4"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h3 id={titleId} className="text-title-sm text-gray-950">
          비식별 이력
        </h3>
        <span aria-hidden="true" className="flex-auto" />
        {/* 이 영역을 함께 보는 대상 — 바로 위 배치 실패 패널이 검수자 전용인 것과 구분된다. */}
        <Badge variant="neutral" label="검수자 · 라벨링 작업자" />
      </div>

      {history.length === 0 ? (
        <div
          role="status"
          className="flex flex-col items-center justify-center gap-2 p-6 text-center"
        >
          <Shield className="h-8 w-8 text-gray-400" aria-hidden="true" />
          <p className="text-body-sm font-semibold text-gray-800">비식별 이력이 없습니다.</p>
        </div>
      ) : (
        <>
          <ul className="flex flex-col gap-2">
            {history.map((item) => (
              <li
                key={item.procLogSn}
                data-state={itemState(item.procSttsCd)}
                className={cn(
                  'flex flex-col gap-2 rounded-md border bg-white px-4 py-2',
                  itemStateClass(item.procSttsCd),
                )}
              >
                <div className="flex flex-wrap items-center gap-2">
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="text-title-sm text-gray-950">{kindLabel(item.reqKndCd)}</span>
                    {/* 회차 종류의 부제 — '비식별' 만으로는 최초 배치분인지 재처리분인지 읽히지 않는다. */}
                    <span className="text-caption text-gray-600">
                      {kindSubLabel(item.reqKndCd)}
                    </span>
                  </div>
                  <span
                    className={`inline-flex items-center whitespace-nowrap rounded-full px-2.5 py-[3px] text-label ${statusClass(item.procSttsCd)}`}
                  >
                    {statusLabel(item.procSttsCd)}
                  </span>
                  <span aria-hidden="true" className="flex-auto" />
                  {/* 요청 일시는 우측 끝에 붙인다. 폭이 좁아지면 배지를 밀어내기 전에 줄바꿈되게 둔다. */}
                  <span className="whitespace-normal text-caption text-gray-600 xl:whitespace-nowrap">
                    요청 {formatDateTime(item.reqDt)}
                  </span>
                </div>

                {hasDetectionCounts(item) && (
                  <dl className="m-0 grid grid-cols-[repeat(3,minmax(0,240px))] gap-x-4 gap-y-2 border-t border-gray-100 pt-2">
                    <div className="flex min-w-0 flex-col gap-0.5">
                      <dt className="text-label text-gray-600">얼굴 검출</dt>
                      <dd className="m-0 font-mono text-body-sm tabular-nums text-gray-900">
                        {formatCount(item.faceDtctCnt)}
                      </dd>
                    </div>
                    <div className="flex min-w-0 flex-col gap-0.5">
                      <dt className="text-label text-gray-600">번호판 검출</dt>
                      <dd className="m-0 font-mono text-body-sm tabular-nums text-gray-900">
                        {formatCount(item.noPltDtctCnt)}
                      </dd>
                    </div>
                    <div className="flex min-w-0 flex-col gap-0.5">
                      <dt className="text-label text-gray-600">총 프레임</dt>
                      <dd className="m-0 font-mono text-body-sm tabular-nums text-gray-900">
                        {formatCount(item.frmeCnt)}
                      </dd>
                    </div>
                  </dl>
                )}

                {(item.prcsBgngDt || item.prcsEndDt) && (
                  <p className="flex items-center gap-1 text-caption text-gray-600">
                    <Clock className="h-4 w-4 shrink-0" aria-hidden="true" />
                    처리 {formatDateTime(item.prcsBgngDt)} ~ {formatDateTime(item.prcsEndDt)}
                  </p>
                )}
              </li>
            ))}
          </ul>

          {/* 목록 밖 안내 — 표시 범위와 '집계 줄이 없는 회차' 의 뜻을 사양 문구 그대로 적는다. */}
          <div className="border-t border-gray-200 pt-2">
            <p className="text-caption text-gray-600">
              한 항목이 위탁 1회차이며 최신 회차가 위입니다. 최근 20건까지 표시하고, 외부 처리
              결과를 받지 못한 회차는 검출 집계와 처리 구간 줄을 표시하지 않습니다.
            </p>
          </div>
        </>
      )}
    </section>
  );
}
