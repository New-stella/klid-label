import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Modal } from '@/components/common/Modal';
import { Skeleton } from '@/components/common/Skeleton';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { useDeidentCandidates } from '../hooks/useDeidentReports';
import type { DeidentCandidate } from '../reportTypes';

/**
 * 비식별 신고 해소 — **재비식별 산출물 선택** 다이얼로그.
 *
 * ## 왜 고르게 하나
 * 외부 비식별 솔루션은 결과를 원본과 **다른 이름**으로 만든다(예: `001.mp4` → `001-mask.mp4`).
 * 구 동작은 시스템이 기록해 둔 경로 한 개만 보고 "그 파일이 바뀌었는가"로 판정해서, 다른 이름으로
 * 산출된 경우 그 신고는 **영원히 해소되지 않았다**(영상이 잠긴 채 고착). 그래서 서버가 산출 폴더를
 * 열거해 후보를 보여주고, 사람이 실제 재비식별 결과를 고른다.
 *
 * ## 화면 규칙
 * - **기본 선택 없음** — 아무것도 고르지 않은 상태로 열리고, 고르기 전에는 확인 버튼이 비활성이다.
 *   (서버도 선택값이 없으면 거부한다 — 어느 쪽도 대신 골라 주지 않는다.)
 * - 각 후보에 **파일명 + 크기 + 수정시각**을 보여준다 — 어느 것이 새 산출물인지 사람이 판단할 근거.
 * - 현재 사용 중인 산출물은 표시로 구분한다(제자리 교체한 경우 이것을 고르는 것이 정상이다).
 * - **사용할 수 없는 후보는 고를 수 없다** — 서버가 어차피 거부하므로 왕복 없이 사유를 알린다.
 * - 후보가 하나도 없으면 확인을 비활성하고 "외부 솔루션으로 비식별을 완료한 뒤 다시 시도" 안내를 띄운다.
 *
 * ## ★ 선택 가능 여부와 표시는 다른 축이다 (2026-09-09 · `@design SCREEN-032` v29 · `ADR-027`)
 * 서버의 자격 판정({@link DeidentCandidate.eligible})에서 **「신고 이후에 만들어졌는가」 조건이
 * 빠졌다.** 남는 기준은 **무결성 하나**(정상적으로 열리는 영상 파일인가)다. 그래서:
 * - 신고 이전부터 있던 산출물 — **지금 쓰고 있는 비식별 영상 포함** — 도 그대로 고를 수 있다.
 *   그 조건은 외부 솔루션이 신고 이후 새 산출물을 내놓아야만 충족되는데 그 산출물을 만드는 경로가
 *   저작도구 안에 없어, 신고된 영상이 작업락에 묶인 채 **해소할 문이 없었다**.
 * - 「현재 사용 중」은 **구분을 위한 표시일 뿐 선택을 막지 않는다.**
 * - 「신고 이후에 만들어졌는가」도 표시로만 남는다 — 자격이 그것을 더 이상 걸러 주지 않으므로,
 *   그 사실을 화면이 보여주지 않으면 **신고 대상이 된 바로 그 파일로 신고를 닫으면서도 사람이
 *   그것을 알 수 없다.** 판정은 {@link isPreReportArtifact} 한 곳이다.
 * - ⚠ **무결성 불통과 후보는 여전히 고를 수 없다** — 그 축까지 풀지 말 것(서버가 409 로 거부한다).
 *
 * ## 보안
 * - **내부 저장 경로를 표시하지 않는다** — 서버 응답에도 파일명만 담기며, 화면 역시 파일명만 쓴다.
 * - 파일명은 사용자가 만든 값이 아니라 서버 목록의 값이며 React 가 텍스트로 escape 해 렌더한다.
 */
export interface DeidentResolveDialogProps {
  /** 해소할 신고 PK. `null` 이면 닫힌 상태. */
  rprtSn: number | null;
  /** 신고 대상 영상 PK — 어떤 영상을 해소하는지 헤더에 표시. */
  rawSn?: number;
  /**
   * 신고 접수 일시(ISO-8601) — 후보가 **신고 이후에 만들어진 것인지** 구분해 보여주는 기준.
   *
   * 서버는 이 축의 전용 필드를 주지 않는다(응답은 `fileName·sizeBytes·modifiedAt·eligible·current`).
   * 화면이 후보의 `modifiedAt` 과 이 값을 대조해 판정한다 — `@design API-202` 가 `modifiedAt` 을
   * *"신고 이후에 만들어진 산출물인지 화면이 구분해 보여주는 근거"* 로 규정한 그대로다.
   * 값이 없으면(레거시 호출 등) 구분 표시를 **하지 않는다** — 모르는 것을 단정하지 않는다.
   */
  reportDt?: string;
  onClose: () => void;
  /** 선택한 산출물로 해소 실행. */
  onConfirm: (fileName: string) => void;
  submitting?: boolean;
}

/** 바이트 → 사람이 읽는 크기. 소수 1자리(1KB 미만은 정수 바이트). */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

/**
 * 후보를 고를 수 없는 이유 — 서버 `eligible=false` 를 사용자 언어로 옮긴다.
 *
 * ★ **거부 사유는 하나뿐이라 단정해도 된다** — 서버 판정이 무결성 하나로 좁혀졌다
 * (`DeidentArtifactCandidateFinder.isEligibleForResolve` → `DeidentArtifactIntegrity`:
 * 심링크 아닌 정규 파일 · 크기 하한 · 알려진 영상 컨테이너 시그니처). 사유가 둘 이상이면 서버가 보낸
 * 문장을 실어야 하지만, 이 축은 전수가 「정상적으로 열리는 영상 파일이 아니다」로 수렴한다.
 *
 * ⚠ 구 문구 폐기(2026-09-09) — *"신고 이후에 만들어진 정상 영상 파일이 아니라 …"*. 시간 조건이 서버
 * 판정에서 빠져 **거짓이 됐다**(무결성 불통과인데 시간 얘기를 한다). 되살리지 말 것 — `@design ADR-027`.
 */
const INELIGIBLE_HINT =
  '정상적으로 열리는 영상 파일이 아니라 선택할 수 없습니다. 외부 솔루션으로 비식별을 다시 수행해 주세요.';

/** 신고 접수보다 오래된 후보에 다는 안내 — 고를 수는 있으나 그것이 무엇인지 알려야 한다. */
const PRE_REPORT_HINT =
  '신고 접수보다 먼저 만들어진 파일입니다. 신고된 그 산출물일 수 있으니 확인 후 선택해 주세요.';

const NO_CANDIDATE_MESSAGE =
  '선택할 수 있는 재비식별 산출물이 없습니다. 외부 솔루션으로 비식별을 완료한 뒤 다시 시도해 주세요.';

/**
 * 후보가 **신고 접수 시점보다 오래된 파일**인가 — 표시 전용 판정(선택을 막지 않는다).
 *
 * 두 값 모두 서버가 타임존 없는 `LocalDateTime`(`2026-08-15T10:22:31`)으로 직렬화하므로 `Date.parse`
 * 가 **양쪽을 같은 규칙(로컬 시각)으로** 해석한다 — 한쪽만 오프셋을 달고 오면 어긋나므로, 계약이 바뀌면
 * 이 함수를 먼저 본다.
 *
 * 판정할 수 없으면(`reportDt` 미전달·파싱 실패) **false** 로 수렴해 표시를 생략한다 — 근거 없이
 * 「신고 이전」이라고 단정하는 쪽이 침묵보다 나쁘다.
 *
 * @design API-202
 */
export function isPreReportArtifact(
  modifiedAt: string,
  reportDt: string | undefined,
): boolean {
  if (!reportDt) return false;
  const modified = Date.parse(modifiedAt);
  const reported = Date.parse(reportDt);
  if (!Number.isFinite(modified) || !Number.isFinite(reported)) return false;
  // 같은 시각이면 「신고 이후에 새로 만들어졌다」고 말할 수 없으므로 이전으로 본다.
  return modified <= reported;
}

export function DeidentResolveDialog({
  rprtSn,
  rawSn,
  reportDt,
  onClose,
  onConfirm,
  submitting = false,
}: DeidentResolveDialogProps) {
  const [selected, setSelected] = useState<string | null>(null);
  const { data, isLoading, error } = useDeidentCandidates(rprtSn);

  // 다이얼로그를 다시 열 때 이전 선택이 남지 않게 한다 — 남으면 "기본 선택 없음"이 깨지고,
  // 다른 신고의 파일명이 그대로 전송될 수 있다.
  useEffect(() => {
    setSelected(null);
  }, [rprtSn]);

  const candidates: DeidentCandidate[] = data ?? [];
  const selectable = candidates.filter((c) => c.eligible);
  const canConfirm = selected !== null && !submitting;

  return (
    <Modal
      open={rprtSn !== null}
      onClose={onClose}
      size="lg"
      title="재비식별 산출물 선택"
      description={
        rawSn !== undefined
          ? `영상 #${rawSn} 의 비식별을 다시 수행한 결과 파일을 선택하면 신고가 해소됩니다.`
          : '비식별을 다시 수행한 결과 파일을 선택하면 신고가 해소됩니다.'
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={submitting}>
            취소
          </Button>
          <Button
            variant="primary"
            data-testid="deident-resolve-confirm"
            disabled={!canConfirm}
            loading={submitting}
            onClick={() => {
              if (selected !== null) onConfirm(selected);
            }}
          >
            해소 처리
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3" data-testid="deident-candidate-dialog">
        {isLoading && (
          <div className="space-y-2">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} height={48} />
            ))}
          </div>
        )}

        {error && <ErrorState title="산출물 목록을 불러올 수 없습니다" />}

        {data && candidates.length === 0 && (
          <EmptyState message={NO_CANDIDATE_MESSAGE} />
        )}

        {data && candidates.length > 0 && (
          <>
            {selectable.length === 0 && (
              <p
                className="rounded-md bg-warning/10 px-3 py-2 text-caption text-warning-700"
                data-testid="deident-no-selectable-notice"
              >
                {NO_CANDIDATE_MESSAGE}
              </p>
            )}
            <ul
              className="flex flex-col gap-2"
              role="radiogroup"
              aria-label="재비식별 산출물 후보"
            >
              {candidates.map((c) => {
                // ★ 잠그는 축은 **무결성 하나**다. 「신고 이전 파일」·「현재 사용 중」은 표시일 뿐
                //    disabled 에 넣지 않는다 — 넣으면 이 CO 가 없앤 그 잠금이 화면 쪽에 되살아난다.
                const disabled = !c.eligible || submitting;
                const preReport = isPreReportArtifact(c.modifiedAt, reportDt);
                return (
                  <li key={c.fileName}>
                    <label
                      data-testid={`deident-candidate-${c.fileName}`}
                      title={c.eligible ? undefined : INELIGIBLE_HINT}
                      className={[
                        'flex items-start gap-3 rounded-md border px-3 py-2',
                        disabled
                          ? 'cursor-not-allowed border-gray-200 bg-gray-50 opacity-60'
                          : 'cursor-pointer border-gray-200 bg-white hover:bg-gray-50',
                        selected === c.fileName ? 'border-primary-500 bg-primary-50' : '',
                      ].join(' ')}
                    >
                      <input
                        type="radio"
                        name="deident-candidate"
                        className={`mt-1 ${KRDS_FOCUS}`}
                        value={c.fileName}
                        checked={selected === c.fileName}
                        disabled={disabled}
                        onChange={() => setSelected(c.fileName)}
                      />
                      <span className="flex min-w-0 flex-col gap-0.5">
                        <span className="flex flex-wrap items-center gap-2">
                          <span className="truncate font-mono text-mono text-gray-800">
                            {c.fileName}
                          </span>
                          {/* 지금 쓰이고 있다는 **구분 표시**다 — 선택을 막지 않는다(SCREEN-032 v29). */}
                          {c.current && (
                            <span
                              data-testid={`deident-candidate-current-${c.fileName}`}
                              className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600"
                            >
                              현재 사용 중
                            </span>
                          )}
                          {/* 자격이 시간을 더 이상 걸러 주지 않으므로 이 표시가 유일한 단서다. */}
                          {preReport && (
                            <span
                              data-testid={`deident-candidate-prereport-${c.fileName}`}
                              title={PRE_REPORT_HINT}
                              className="inline-flex items-center rounded-full bg-secondary-50 px-2 py-0.5 text-label font-medium text-secondary-700"
                            >
                              신고 이전 파일
                            </span>
                          )}
                          {!c.eligible && (
                            <span className="inline-flex items-center rounded-full bg-warning/10 px-2 py-0.5 text-label font-medium text-warning-700">
                              선택 불가
                            </span>
                          )}
                        </span>
                        <span className="text-caption text-gray-600">
                          {formatBytes(c.sizeBytes)} · {new Date(c.modifiedAt).toLocaleString('ko-KR')}
                        </span>
                        {preReport && c.eligible && (
                          <span className="text-caption text-secondary-700">{PRE_REPORT_HINT}</span>
                        )}
                        {!c.eligible && (
                          <span className="text-caption text-warning-700">{INELIGIBLE_HINT}</span>
                        )}
                      </span>
                    </label>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </div>
    </Modal>
  );
}
