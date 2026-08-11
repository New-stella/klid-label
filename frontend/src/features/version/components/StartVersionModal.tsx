// SCREEN-010 / SCREEN-005 §로드 버전 선택 모달 — 「시작 버전 선택」.
//
// 라벨링 화면에 들어올 때 어느 산출 버전 상태에서 편집을 시작할지 고른다. 여기서 말하는 버전은
// 관제가 픽업하는 산출 폴더 번호(v1·v2)와 같은 것이며 영상 단위로 매겨진다.
//
// ★ D4 는 히스토리 패널의 **제거가 아니라 재배치**다. 헤더의 히스토리 진입점이 사라지면서
//   ①영상 산출 버전 목록 ②버전 간 diff ③작업본 diff ④롤백 네 기능이 진입점을 통째로 잃는다.
//   그래서 이 모달이 그 넷을 모두 품는다 — 위쪽은 영상 축(시작 버전), 아래쪽은 프레임 축
//   (버전 이력 · diff · 롤백)이다. 아래쪽은 기존 HistoryPanel 을 **그대로 옮겨 담아** 판정 로직을
//   복제하지 않는다(비교 축·렌더 분기·"변경 없음" 안내가 한 곳에만 있어야 갈리지 않는다).
//
// ★ 불러오기와 확정 저장은 **2단계**다 (2026-08-11 확정, 구속).
//   ① 불러오기(API-195) — 고른 회차를 화면에 올리기만 하고 **서버에는 아무것도 쓰지 않는다**.
//   ② 확정 저장(API-196) — 라벨링 화면의 저장을 눌렀을 때 영상 전체가 한 트랜잭션으로 확정된다.
//   그래서 "불러왔는데 아니네" 하고 저장하지 않고 떠나면 작업본이 그대로 남는다 — **되돌릴 창이
//   생기는 것**이 이 재설계의 핵심이다. 구 `PUT /videos/{rawSn}/start-version`(고르는 순간 즉시
//   서버 작업본 교체)은 폐기됐다.
//
// @design D4
// @design D5
// @req R6

import { useEffect, useMemo, useState } from 'react';
import { History, RotateCcw } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Modal } from '@/components/common/Modal';
import { Radio } from '@/components/common/Radio';
import { Spinner } from '@/components/common/Spinner';
import type { LabelHistoryItem } from '@/features/label/api';
import { HistoryPanel } from '@/features/version/components/HistoryPanel';
import { useLoadVersionLabels } from '@/features/version/hooks/useLoadVersionLabels';
import { useVideoVersions } from '@/features/version/hooks/useVideoVersions';
import { extractBeMessage } from '@/lib/api/extractBeMessage';
import { cn } from '@/lib/cn';

import type { VersionLabelsResponse, VideoVersion } from '../types';

export interface StartVersionModalProps {
  open: boolean;
  /** 영상 PK (LS_DATA_RAW.RAW_SN) — 산출 버전 축. */
  rawSn: number;
  /** 현재 프레임 PK (LS_DATA_SRC.SRC_SN) — 재배치된 프레임 버전 이력 축. */
  srcSn: number;
  /** 미저장 편집 여부. 있으면 불러오기 전에 확인을 받는다. */
  dirty: boolean;
  /** 닫기 — '현재 작업본으로 시작' 과 동일 경로(아무것도 불러오지 않는다). */
  onClose: () => void;
  /**
   * 불러오기 성공 — 화면 상위가 이 세트를 캔버스에 올리고 <b>저장 대기</b> 상태로 둔다.
   *
   * ⚠ 이 시점에 서버는 아무것도 바뀌지 않았다. 확정은 라벨링 화면의 저장(API-196)이 맡는다.
   */
  onLoaded: (loaded: VersionLabelsResponse) => void;
  /** 프레임 버전 이력의 "이 저장 되돌리기" 위임 — 미전달 시 버튼 미노출(기존 계약 그대로). */
  onRevert?: (item: LabelHistoryItem) => void;
}

function formatTime(iso: string | null | undefined): string {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function StartVersionModal({
  open,
  rawSn,
  srcSn,
  dirty,
  onClose,
  onLoaded,
  onRevert,
}: StartVersionModalProps) {
  const { data, isLoading, error } = useVideoVersions(open ? rawSn : undefined);
  const { mutateAsync: load, isPending } = useLoadVersionLabels(rawSn);

  const versions: VideoVersion[] = useMemo(
    // 서버가 내림차순으로 준다 — FE 가 다시 정렬하지 않는다(정렬 축이 갈리면 화면과 서버가 어긋난다).
    () => (Array.isArray(data) ? data : []),
    [data],
  );
  const latestVersionNo = versions[0]?.versionNo ?? null;

  const [selected, setSelected] = useState<number | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);
  const [result, setResult] = useState<VersionLabelsResponse | null>(null);
  // 프레임 축(버전 이력·diff·롤백)은 접어 둔다 — 주 동선은 시작 버전 선택이다.
  const [historyOpen, setHistoryOpen] = useState(false);

  // 기본 선택 = 가장 마지막 버전. 그대로 확정하면 이미 그 상태인 작업본에는 아무 일도 일어나지 않아
  // 안전한 기본값이다. 사용자가 고른 뒤에는 목록 재조회로 선택을 되돌리지 않는다.
  useEffect(() => {
    setSelected((prev) => (prev !== null ? prev : latestVersionNo));
  }, [latestVersionNo]);

  // 영상이 바뀌면 선택·결과를 초기화한다(이전 영상의 회차 번호가 잔존하지 않도록).
  useEffect(() => {
    setSelected(null);
    setApplyError(null);
    setResult(null);
    setConfirmOpen(false);
    setHistoryOpen(false);
  }, [rawSn]);

  // 해석하지 못한 프레임 수 — "전부 되돌렸다"는 거짓말을 막는 축(구 unresolvedFrames 와 같은 의미).
  const unresolvedCount = useMemo(
    () => (result?.frames ?? []).filter((f) => !f.resolved).length,
    [result],
  );

  const runLoad = async () => {
    if (selected === null) return;
    setApplyError(null);
    try {
      const res = await load(selected);
      setResult(res);
      onLoaded(res);
    } catch (e) {
      // ⚠ 실패해도 닫지 않는다 — 닫히면 사용자는 불러와졌다고 오인한다.
      setApplyError(extractBeMessage(e, '시작 버전을 불러오지 못했습니다.'));
    } finally {
      setConfirmOpen(false);
    }
  };

  const handleApplyClick = () => {
    // 미저장 편집은 불러온 내용으로 화면에서 덮인다 — 먼저 알리고 확인을 받는다.
    //   (서버는 바뀌지 않으므로 되돌릴 수 있지만, 화면의 편집은 사라진다.)
    if (dirty) {
      setConfirmOpen(true);
      return;
    }
    void runLoad();
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="xl"
      title="시작 버전 선택"
      description="검수 승인으로 만들어진 산출 버전 중 어느 상태에서 편집을 시작할지 고릅니다. 불러오기는 화면에만 올리고 서버에는 아무것도 쓰지 않으며, 저장을 눌러야 확정됩니다. 여기서 말하는 버전은 관제가 가져가는 산출 폴더 번호와 같습니다."
    >
      <div className="flex flex-col gap-4" data-testid="start-version-modal">
        {/* ① 영상 축 — 산출 버전 목록 */}
        <section aria-label="산출 버전 목록">
          {isLoading ? (
            <div className="flex justify-center py-8" data-testid="start-version-loading">
              <Spinner label="산출 버전 목록 로딩" />
            </div>
          ) : error ? (
            // 렌더 분기(로딩 → 에러 → 결과) — 순서가 어긋나면 조회 실패가 "고를 버전이 없음"으로 둔갑한다.
            <div data-testid="start-version-error">
              <ErrorState
                title="산출 버전 목록 조회 실패"
                message={(error as Error).message}
                className="py-8"
              />
            </div>
          ) : versions.length === 0 ? (
            <div data-testid="start-version-empty">
              <EmptyState
                title="고를 산출 버전이 없습니다"
                message="검수 승인으로 산출 버전이 만들어진 뒤에 고를 수 있습니다."
                className="py-8"
              />
            </div>
          ) : (
            <ul className="max-h-[32vh] divide-y divide-gray-100 overflow-y-auto rounded border border-gray-200">
              {versions.map((v) => {
                const isLatest = v.versionNo === latestVersionNo;
                const checked = selected === v.versionNo;
                return (
                  <li
                    key={v.versionNo}
                    data-testid={`start-version-row-${v.versionNo}`}
                    className={cn(
                      'flex items-center gap-3 px-4 py-2 transition-colors',
                      checked ? 'bg-primary-50' : 'hover:bg-gray-50',
                    )}
                  >
                    <Radio
                      name="start-version"
                      value={v.versionNo}
                      checked={checked}
                      onChange={() => setSelected(v.versionNo)}
                      data-testid={`start-version-radio-${v.versionNo}`}
                      aria-label={`산출 버전 v${v.versionNo}`}
                    />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <code className="rounded bg-primary-50 px-1.5 py-0.5 font-mono text-mono text-primary-700">
                          v{v.versionNo}
                        </code>
                        {/* 배지는 색이 아니라 글자로 뜻을 전한다(색 단독 구분 금지). */}
                        {isLatest && (
                          <span className="inline-flex items-center rounded-full bg-success/10 px-2 py-0.5 text-label font-medium text-success-700">
                            최신
                          </span>
                        )}
                      </div>
                      {/* 선택 행 배경(primary-50) 위에서도 AA 를 만족해야 한다 — 500 단은 4.01:1 로 미달. */}
                      <p className="mt-0.5 text-caption text-gray-600">
                        변경된 프레임 {v.snapshotCnt}건 · {formatTime(v.latestRegDt)}
                      </p>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </section>

        {/* 불러오기 결과 — 아직 확정이 아니라는 사실과, 해석하지 못한 프레임을 숨기지 않는다. */}
        {result && (
          <div
            role="status"
            data-testid="start-version-result"
            className="rounded border border-gray-200 bg-gray-50 px-4 py-3 text-body-md text-gray-700"
          >
            <p>
              v{result.version} 상태를 화면에 불러왔습니다 — 프레임 {result.frames.length}개.{' '}
              <b>아직 저장되지 않았습니다.</b> 저장을 눌러야 확정되고, 저장하지 않고 나가면 원래
              작업본이 그대로 남습니다.
            </p>
            {unresolvedCount > 0 && (
              <p data-testid="start-version-unresolved" className="mt-1 text-warning">
                이 회차 이하의 저장 기록이 없어 현재 작업본을 그대로 올린 프레임이 {unresolvedCount}개
                있습니다.
              </p>
            )}
          </div>
        )}

        {applyError && (
          <p
            role="alert"
            data-testid="start-version-apply-error"
            className="rounded border border-danger/30 bg-danger/10 px-4 py-2 text-body-md text-danger-700"
          >
            {applyError}
          </p>
        )}

        {/* ② 프레임 축 — 재배치된 버전 이력(버전 목록 · 버전 간 diff · 작업본 diff · 롤백) */}
        <section aria-label="이 프레임의 버전 이력">
          <button
            type="button"
            onClick={() => setHistoryOpen((v) => !v)}
            aria-expanded={historyOpen}
            aria-controls="start-version-frame-history"
            data-testid="start-version-history-toggle"
            className="inline-flex items-center gap-1.5 rounded border border-gray-300 px-3 py-1.5 text-label font-semibold text-gray-700 hover:bg-gray-50"
          >
            <History size={14} aria-hidden />
            이 프레임의 버전 이력
          </button>
          <p className="mt-1 text-caption text-gray-500">
            지금 보고 있는 프레임 하나를 기준으로 변경 내용을 비교하고, 필요하면 그 프레임만
            되돌립니다. 위 시작 버전 선택은 영상 전체에 적용됩니다.
          </p>
          {historyOpen && (
            <div
              id="start-version-frame-history"
              data-testid="start-version-frame-history"
              className="mt-2 h-[42vh] overflow-hidden rounded border border-gray-200"
            >
              <HistoryPanel srcSn={srcSn} defaultTab="versions" onRevert={onRevert} />
            </div>
          )}
        </section>
      </div>

      <div className="mt-6 flex items-center justify-end gap-2">
        <Button
          variant="secondary"
          onClick={onClose}
          data-testid="start-version-keep-working"
          disabled={isPending}
        >
          현재 작업본으로 시작
        </Button>
        <Button
          variant="primary"
          onClick={handleApplyClick}
          disabled={selected === null || isPending}
          loading={isPending}
          data-testid="start-version-apply"
        >
          <RotateCcw size={14} aria-hidden />
          이 버전으로 시작
        </Button>
      </div>

      {/* 미저장 편집 확인 — Modal 중첩 대신 인라인 확인으로 포커스 트랩이 겹치지 않게 한다. */}
      {confirmOpen && (
        <div
          role="alertdialog"
          aria-label="저장하지 않은 편집이 사라집니다"
          data-testid="start-version-dirty-confirm"
          className="mt-4 rounded border border-warning/40 bg-warning/10 px-4 py-3"
        >
          <p className="text-body-md text-gray-800">
            저장하지 않은 편집이 있습니다. 이 버전을 불러오면 화면의 그 편집은 v{selected} 시점 내용으로
            덮입니다. 불러오기만으로는 서버에 아무것도 저장되지 않으며, 저장을 눌러야 영상 전체가
            확정됩니다.
          </p>
          <div className="mt-2 flex justify-end gap-2">
            <Button variant="secondary" size="sm" onClick={() => setConfirmOpen(false)}>
              취소
            </Button>
            <Button variant="primary" size="sm" onClick={() => void runLoad()}>
              불러오기
            </Button>
          </div>
        </div>
      )}
    </Modal>
  );
}
