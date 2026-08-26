import { useEffect, useState } from 'react';
import { ChevronUp, FileVideo, Folder } from 'lucide-react';

import { Alert } from '@/components/common/Alert';
import { Breadcrumb } from '@/components/common/Breadcrumb';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Spinner } from '@/components/common/Spinner';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { useImportFolderBrowse, useImportVideoFileBrowse } from '../hooks/useImportBrowse';

/** 고를 대상 — 폴더 축(산출물 폴더 경로)과 파일 축(원본 영상 경로). */
export type ImportPathPickerMode = 'folder' | 'file';

const ROOT_LABEL = '허용 저장소';

export interface ImportPathPickerModalProps {
  open: boolean;
  mode: ImportPathPickerMode;
  onClose: () => void;
  /** 고른 위치. 서버가 돌려준 실제 위치(`path`)를 그대로 넘긴다. */
  onSelect: (path: string) => void;
}

/** 서버 오류에서 사용자에게 보여줄 문구를 꺼낸다 — 서버 메시지를 그대로 쓴다. */
function messageOf(e: unknown, fallback: string): string {
  return e instanceof Error && e.message ? e.message : fallback;
}

/**
 * 경로를 사람이 읽는 조각으로 나눈다 — 표시 전용이다.
 *
 * ⚠ 이 조각으로 상위 경로를 만들어 쓰지 않는다. 「상위로」의 대상은 언제나 서버가 돌려준
 *   `parent` 이며, 문자열을 잘라 부모를 유추하면 허용 범위 판정을 화면이 흉내내게 된다.
 */
function segmentsOf(path: string | null): string[] {
  if (!path) return [];
  return path.split('/').filter((s) => s.length > 0);
}

/**
 * 경로 선택 — 허용 저장소 범위 안을 한 단계씩 훑어 위치를 골라 입력칸에 넣는다.
 *
 * <p>이 창은 <b>직접 입력을 대신하지 않는다</b>. 경로를 아는 사용자는 붙여넣기로 끝내는 편이
 * 빠르고, 저장소 마운트가 풀려 탐색이 죽어도 입력이 막히면 안 되기 때문이다. 그래서 조회가
 * 거부돼도 창을 닫지 않고 이 자리에서 사유를 보여줄 뿐이며, 폼 전체를 오류로 만들지 않는다.
 *
 * <p>허용 저장소 범위 판정은 <b>서버가 소유</b>한다. 화면은 응답의 {@code parent} 와 지금
 * 루트 목록에 있는지 여부 둘로만 「상위로」를 정하고, 경로 문자열을 잘라 부모를 유추하지 않는다.
 * {@code parent} 가 비어 있는 것은 「올라갈 곳이 없다」가 아니라 「지금 자리가 허용 저장소 루트」
 * 라는 뜻이므로 <b>루트 목록으로 돌아간다</b> — 그 값으로 「상위로」를 잠그면 루트 바로 아래에서
 * 갇힌다. 다만 그 값을 <b>아직 모르는 구간</b>(조회 중·조회 실패)에서는 함께 잠근다 — 자세한
 * 사유는 아래 {@code parentUnknown} 주석 참조.
 *
 * <p>고른 값으로는 응답의 {@code path}(=서버가 판정에 쓴 실제 위치)를 쓴다 — 사용자가 누른
 * 표기를 그대로 되쓰면 바로가기가 섞였을 때 검사 창구와 왕복이 어긋난다.
 *
 * <p>파일 축에서는 폴더와 영상 파일을 함께 보여준다(폴더는 눌러 들어가고 파일은 눌러 고른다).
 * 루트 목록에는 기준 위치가 없어 파일 창구를 아예 부르지 않는다 — 그 창구는 위치가 필수라
 * 빈 값을 실어 보내면 근거 없는 400 이 화면에 뜬다.
 *
 * @design SCREEN-039
 * @design API-221
 * @design API-222
 */
export function ImportPathPickerModal({
  open,
  mode,
  onClose,
  onSelect,
}: ImportPathPickerModalProps) {
  /** 지금 보고 있는 위치. null 이면 허용 저장소 루트 목록이다. */
  const [cursor, setCursor] = useState<string | null>(null);

  // 창을 열 때마다 루트에서 시작한다 — 앞 회차의 위치가 남으면 다른 축의 경로를 이어받는다.
  useEffect(() => {
    if (open) setCursor(null);
  }, [open, mode]);

  const folders = useImportFolderBrowse(cursor, open);
  const files = useImportVideoFileBrowse(cursor, open && mode === 'file');

  const folderData = folders.data ?? null;
  const fileData = mode === 'file' ? (files.data ?? null) : null;

  /** 서버가 판정에 사용한 실제 위치. 루트 목록에서는 null 이다. */
  const resolvedPath = folderData?.path ?? null;

  /**
   * 한 단계 위 자리. **비어 있는 것은 「올라갈 곳이 없다」가 아니라 「지금 자리가 허용 저장소
   * 루트」라는 뜻**이며, 그때 화면은 허용 저장소 루트 목록으로 돌아간다(API-221).
   *
   * ⚠ 이 값이 null 이라고 「상위로」를 잠그면 <b>루트 바로 아래에서 갇힌다</b> — 한 단계만
   *   내려가도 되돌아갈 길이 없어 창을 닫았다 다시 여는 수밖에 없다.
   */
  const parent = folderData?.parent ?? null;

  /**
   * 「상위로」가 잠기는 경우는 둘뿐이다.
   *
   * <ul>
   *   <li><b>루트 목록에 있을 때</b> — 거기서는 실제로 더 올라갈 곳이 없다.</li>
   *   <li><b>지금 자리의 `parent` 를 아직 모를 때</b>(조회 중이거나 실패) — 아래 참조.</li>
   * </ul>
   *
   * 판정에 쓰는 것은 응답의 `parent` 와 지금 루트 목록에 있는지 여부 둘뿐이다 — 경로 문자열을
   * 잘라 부모를 유추하지 않는다(허용 범위 판정을 화면이 흉내내게 된다).
   *
   * ★<b>모르는 구간을 함께 잠그는 이유</b>: 조회가 끝나기 전에는 `parent` 가 없어 null 로
   * 읽히는데, 그 상태로 누르면 「한 단계 위」가 아니라 <b>곧바로 루트 목록으로 튄다</b>. 깊이 2
   * 이상에서 로딩 중 연타하면 단계를 건너뛰어 「한 단계씩 올라간다」(AC-120)가 깨진다.
   * 값이 도착할 때까지 잠가 두면 이 건너뜀이 원천적으로 생기지 않는다.
   *
   * ⚠ 이전 목록을 남겨 두는 방식(`keepPreviousData`)으로 메우지 말 것 — 「상위로」는 멀쩡해지지만
   *   <b>목록까지 이전 자리의 것이 남아</b> 전이 중에 엉뚱한 폴더를 고를 수 있게 된다. 잠그는
   *   쪽이 잃는 것이 적다.
   *
   * ⚠ 조회 실패도 같은 이유로 잠긴다. 대신 오류 안내에 「허용 저장소 루트에서 다시 시작」을 두어
   *   막다른 자리가 되지 않게 한다 — 잠그되 되돌아갈 길은 반드시 남긴다.
   */
  const atRootList = cursor === null;
  const parentUnknown = folderData === null;
  const goUp = () => setCursor(parent);
  const truncated = Boolean(folderData?.truncated) || Boolean(fileData?.truncated);
  const loading = folders.isFetching || (mode === 'file' && files.isFetching);

  // 폴더 축이 먼저다 — 그 조회가 실패하면 지금 위치 자체를 열 수 없다는 뜻이라 파일 축의
  // 사유를 겹쳐 보여줄 필요가 없다.
  const error = folders.error ?? (mode === 'file' ? files.error : null);
  const errorMessage = error ? messageOf(error, '경로를 열 수 없습니다.') : null;

  const folderEntries = folderData?.entries ?? [];
  const fileEntries = fileData?.entries ?? [];
  const empty = !loading && !errorMessage && folderEntries.length === 0 && fileEntries.length === 0;

  const crumbs = [ROOT_LABEL, ...segmentsOf(resolvedPath ?? cursor)].map((label) => ({ label }));

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title={mode === 'folder' ? '산출물 폴더 선택' : '원본 영상 파일 선택'}
      description={
        mode === 'folder'
          ? '허용 저장소 범위 안을 훑어 폴더를 고릅니다. 경로를 직접 입력해도 됩니다.'
          : '폴더를 따라 내려가 그 안의 영상 파일을 고릅니다. 경로를 직접 입력해도 됩니다.'
      }
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            취소
          </Button>
          {mode === 'folder' && (
            <Button
              variant="primary"
              data-testid="path-picker-select-folder"
              disabled={resolvedPath === null}
              onClick={() => {
                if (resolvedPath !== null) onSelect(resolvedPath);
              }}
            >
              이 폴더 선택
            </Button>
          )}
        </>
      }
    >
      <div className="flex flex-col gap-3" data-testid="import-path-picker">
        <div className="flex items-center justify-between gap-3">
          <Breadcrumb items={crumbs} />
          <Button
            variant="secondary"
            size="sm"
            leftIcon={ChevronUp}
            data-testid="path-picker-up"
            disabled={atRootList || parentUnknown}
            onClick={goUp}
          >
            상위로
          </Button>
        </div>

        {truncated && (
          <Alert variant="info" title="일부만 표시됩니다" data-testid="path-picker-truncated">
            목록이 많아 일부만 보여주고 있습니다. 찾는 것이 없으면 경로를 직접 입력해 주세요.
          </Alert>
        )}

        {errorMessage && (
          <Alert variant="error" title="경로를 열 수 없습니다" data-testid="path-picker-error">
            {errorMessage}
            {cursor !== null && (
              <span className="mt-2 block">
                <Button variant="outline" size="sm" onClick={() => setCursor(null)}>
                  허용 저장소 루트에서 다시 시작
                </Button>
              </span>
            )}
          </Alert>
        )}

        <ul
          className="max-h-80 divide-y divide-gray-100 overflow-y-auto rounded-md border border-gray-200"
          data-testid="path-picker-list"
        >
          {folderEntries.map((entry) => (
            <li key={`d:${entry.path}`}>
              <button
                type="button"
                className={cn(
                  'flex w-full items-center gap-2 px-3 py-2 text-left text-sub text-gray-800 hover:bg-gray-50',
                  KRDS_FOCUS,
                )}
                onClick={() => setCursor(entry.path)}
              >
                <Folder className="h-4 w-4 shrink-0 text-gray-400" aria-hidden="true" />
                <span className="truncate">{entry.name}</span>
              </button>
            </li>
          ))}

          {fileEntries.map((entry) => (
            <li key={`f:${entry.path}`}>
              <button
                type="button"
                className={cn(
                  'flex w-full items-center gap-2 px-3 py-2 text-left text-sub text-gray-800 hover:bg-gray-50',
                  KRDS_FOCUS,
                )}
                onClick={() => onSelect(entry.path)}
              >
                <FileVideo className="h-4 w-4 shrink-0 text-gray-400" aria-hidden="true" />
                <span className="truncate">{entry.name}</span>
              </button>
            </li>
          ))}

          {loading && (
            <li className="flex items-center justify-center gap-2 px-3 py-6 text-sub text-gray-500">
              <Spinner size="sm" />
              불러오는 중입니다
            </li>
          )}

          {empty && (
            <li className="px-3 py-6 text-center text-sub text-gray-500" role="status">
              {mode === 'folder'
                ? '이 자리에 하위 폴더가 없습니다. 이 폴더를 그대로 고르거나 경로를 직접 입력해 주세요.'
                : '이 자리에 하위 폴더도 영상 파일도 없습니다. 상위로 올라가거나 경로를 직접 입력해 주세요.'}
            </li>
          )}
        </ul>
      </div>
    </Modal>
  );
}
