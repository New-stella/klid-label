import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ChevronDown, ChevronUp, FileVideo, Folder } from 'lucide-react';

import { Alert } from '@/components/common/Alert';
import { Breadcrumb } from '@/components/common/Breadcrumb';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';
import { Spinner } from '@/components/common/Spinner';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { IMPORT_KEYS } from '@/lib/queryKeys';

import { mergeBrowseEntries } from '../browsePages';
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
 * <p><b>목록은 한 번에 다 받지 않고 나눠서 이어 받는다</b>(API-221·API-222). 이어받을 자리가
 * 있을 때만 「더 보기」를 두고, 누르면 그 자리 뒤부터 받아 목록 아래에 <b>이어붙인다</b>.
 * ★<b>끝났는지는 담긴 개수가 아니라 이어받을 자리가 비었는지로 판정한다</b> — 담긴 것이 하나도
 * 없어도 이어받을 자리가 있으면 끝난 것이 아니다. 개수로 판정하면 <b>폴더가 있는데 「없음」으로
 * 보인다</b>. 이어붙일 때는 위치를 키로 합쳐 같은 항목이 두 번 쌓이지 않게 한다
 * ({@code browsePages.mergeBrowseEntries}).
 *
 * <p>「더 보기」를 눌렀는데 새로 담긴 것이 없을 수 있다 — 서버가 살펴보기 상한에 먼저 걸린
 * 경우다. 그때 목록도 「더 보기」도 <b>그대로 두고</b> 그 사실만 알린다. <b>자동으로 이어받지
 * 않는다</b> — 몇 번을 도는지 이 화면이 통제하지 못하기 때문이다.
 *
 * @design SCREEN-039
 * @design API-221
 * @design API-222
 * @design AC-120
 */
export function ImportPathPickerModal({
  open,
  mode,
  onClose,
  onSelect,
}: ImportPathPickerModalProps) {
  const queryClient = useQueryClient();

  /** 지금 보고 있는 위치. null 이면 허용 저장소 루트 목록이다. */
  const [location, setLocation] = useState<string | null>(null);

  /**
   * 「더 보기」를 눌렀는데 새로 담긴 것이 없었는지.
   *
   * 서버가 살펴보기 상한에 먼저 걸리면 담긴 것이 하나도 없이 이어받을 자리만 돌아온다
   * (API-221). 그것은 오류가 아니므로 목록도 「더 보기」도 그대로 두고 사실만 알린다.
   */
  const [noNewEntries, setNoNewEntries] = useState(false);

  const showFiles = mode === 'file';

  // 창을 열 때마다 루트에서 시작하고 **쌓인 쪽들을 버린다**(SCREEN-039).
  //
  // 자리를 옮기는 것은 쿼리 키가 갈려 저절로 처음부터 다시 받지만, 같은 자리에서 창만 다시
  // 여는 경우는 키가 그대로라 앞 회차에 쌓아 둔 쪽들이 남는다 — 버리지 않으면 창을 다시
  // 열었는데 이미 여러 번 이어받은 상태로 열린다.
  //
  // ★ 이 선언이 **아래 탐색 훅 호출보다 앞**에 있는 것은 의도다 — 뒤로 옮기지 말 것.
  //   effect 는 선언한 순서대로 돌고, 탐색 훅은 자기 effect 에서 조회를 시작한다. 이것을 훅
  //   뒤에 두면 **막 시작된 조회를 버리는** 순서가 되어 같은 자리에 요청이 두 번 나간다.
  useEffect(() => {
    if (open) {
      setLocation(null);
      setNoNewEntries(false);
      queryClient.removeQueries({ queryKey: IMPORT_KEYS.browses() });
    }
  }, [open, mode, queryClient]);

  /**
   * 자리를 옮긴다. **이어받기는 처음으로 돌아간다.**
   *
   * 새 자리는 쿼리 키가 달라 쌓인 쪽들이 함께 따라오지 않는다 — 따라오면 이전 자리의 항목이
   * 새 자리의 목록에 섞여 <b>없는 폴더를 고를 수 있게</b> 된다.
   */
  const moveTo = (next: string | null) => {
    setLocation(next);
    setNoNewEntries(false);
  };

  const folders = useImportFolderBrowse(location, open);
  const files = useImportVideoFileBrowse(location, open && showFiles);

  const folderPages = folders.data?.pages;
  const filePages = files.data?.pages;

  /** 자리·위 자리는 쪽마다 같은 값이라 첫 쪽에서 읽는다. */
  const folderHead = folderPages?.[0] ?? null;

  /** 서버가 판정에 사용한 실제 위치. 루트 목록에서는 null 이다. */
  const resolvedPath = folderHead?.path ?? null;

  /**
   * 한 단계 위 자리. **비어 있는 것은 「올라갈 곳이 없다」가 아니라 「지금 자리가 허용 저장소
   * 루트」라는 뜻**이며, 그때 화면은 허용 저장소 루트 목록으로 돌아간다(API-221).
   *
   * ⚠ 이 값이 null 이라고 「상위로」를 잠그면 <b>루트 바로 아래에서 갇힌다</b> — 한 단계만
   *   내려가도 되돌아갈 길이 없어 창을 닫았다 다시 여는 수밖에 없다.
   */
  const parent = folderHead?.parent ?? null;

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
  const atRootList = location === null;
  const parentUnknown = folderHead === null;
  const goUp = () => moveTo(parent);

  const loading = folders.isFetching || (showFiles && files.isFetching);

  // 폴더 축이 먼저다 — 그 조회가 실패하면 지금 위치 자체를 열 수 없다는 뜻이라 파일 축의
  // 사유를 겹쳐 보여줄 필요가 없다.
  const error = folders.error ?? (showFiles ? files.error : null);
  const errorMessage = error ? messageOf(error, '경로를 열 수 없습니다.') : null;

  // ★ 이어붙일 때 위치를 키로 합친다 — 계약이 중복을 주지 않더라도 재조회·연타로 같은 쪽을
  //   두 번 받는 길이 따로 있다(`mergeBrowseEntries` 주석 참조).
  const folderEntries = mergeBrowseEntries(folderPages);
  const fileEntries = showFiles ? mergeBrowseEntries(filePages) : [];
  const totalCount = folderEntries.length + fileEntries.length;

  /**
   * ★<b>더 받을 것이 남았는지는 이어받을 자리 하나가 정한다.</b>
   *
   * `hasNextPage` 는 훅의 `getNextPageParam` 이 응답의 `nextCursor` 를 읽어 세운 값이다 —
   * 담긴 개수를 보지 않는다. 담긴 것이 0건이어도 이어받을 자리가 있으면 아직 끝이 아니다.
   */
  const hasMore = folders.hasNextPage || (showFiles && files.hasNextPage);

  /**
   * 「더 보기」 — 이어받을 자리 뒤부터 받아 목록 아래에 이어붙인다.
   *
   * 새로 담긴 것이 있었는지는 <b>이번에 받아 온 쪽들로 다시 세어</b> 판정한다. 클릭 시점의
   * 값을 나중에 다시 읽으면 그 사이 재조회가 끼어들어 어긋난다.
   */
  const loadMore = () => {
    const before = totalCount;
    setNoNewEntries(false);

    const folderTask = folders.hasNextPage ? folders.fetchNextPage() : Promise.resolve(null);
    const fileTask =
      showFiles && files.hasNextPage ? files.fetchNextPage() : Promise.resolve(null);

    void Promise.all([folderTask, fileTask]).then(([f, v]) => {
      // 조회가 거부됐으면 그 사유가 오류 안내로 나간다 — 「담긴 것이 없다」로 덮지 않는다.
      if (f?.isError || v?.isError) return;
      const after =
        mergeBrowseEntries(f ? f.data?.pages : folderPages).length +
        (showFiles ? mergeBrowseEntries(v ? v.data?.pages : filePages).length : 0);
      if (after <= before) setNoNewEntries(true);
    });
  };

  /**
   * 「이 자리에 없다」고 말해도 되는 것은 <b>끝까지 다 본 뒤</b>다.
   *
   * ⚠ `hasMore` 를 빼면 살펴보기 상한에 걸린 첫 쪽에서 곧바로 「하위 폴더가 없습니다」가 떠
   *   <b>폴더가 있는데 없는 것으로 보인다</b>.
   */
  const empty = !loading && !errorMessage && totalCount === 0 && !hasMore;

  const crumbs = [ROOT_LABEL, ...segmentsOf(resolvedPath ?? location)].map((label) => ({ label }));

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

        {noNewEntries && (
          <Alert variant="info" title="더 볼 것이 남아 있습니다" data-testid="path-picker-no-new">
            {mode === 'folder'
              ? '살펴본 자리에는 폴더가 없었습니다. 계속 볼 수 있습니다.'
              : '살펴본 자리에는 폴더도 영상 파일도 없었습니다. 계속 볼 수 있습니다.'}
          </Alert>
        )}

        {errorMessage && (
          <Alert variant="error" title="경로를 열 수 없습니다" data-testid="path-picker-error">
            {errorMessage}
            {location !== null && (
              <span className="mt-2 block">
                <Button variant="outline" size="sm" onClick={() => moveTo(null)}>
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
                onClick={() => moveTo(entry.path)}
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

        {/*
          ★ 이어받을 자리가 있을 때만 둔다. 담긴 개수로 판정하지 않는다 — 담긴 것이 하나도 없어도
            이어받을 자리가 있으면 아직 다 보지 않은 것이다(API-221).
        */}
        {hasMore && (
          <Button
            variant="secondary"
            size="sm"
            leftIcon={ChevronDown}
            data-testid="path-picker-load-more"
            disabled={loading}
            onClick={loadMore}
          >
            더 보기
          </Button>
        )}
      </div>
    </Modal>
  );
}
