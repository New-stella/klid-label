package kr.co.cudo.authoring.controlnotify.event;

/**
 * Phase 2 — 라벨/메타 수정 도메인 이벤트.
 *
 * <p>검수 완료 후 라벨/메타가 수정될 때 발행되어 outbound TASK_MODIFIED 통지를 트리거한다.
 *
 * <h3>{@code exportRegenerated} — 통지의 changed_items 범위를 결정하는 축 (A-2)</h3>
 * 영상 단위 변경({@code srcSn=null}) 중 일부만 export 폴더를 재생성한다. 재생성 여부를
 * <b>발행처 클래스명으로 추정하지 않고 이벤트가 직접 싣는다</b> — 새 발행처가 생겨도 규칙이 유지되고,
 * 통지 조립부가 발행처를 알 필요가 없기 때문이다.
 * <ul>
 *   <li>{@code true} — 재생성 경로다. 이 이벤트는 {@code ControlNotifyDebouncer} 의 디바운스 윈도우에
 *       축적되고, 만료 flush({@code flushExpiredWindows})가 {@code AsyncDatasetExportRunner#runReExportThenNotify}
 *       로 위임해 <b>export 폴더를 새 버전으로 전량 재생성한 뒤</b> 통지 콜백을 실행한다(export→통지 직렬화).
 *       프레임 이미지·JSON 이 전량 재생성되므로 통지는 전 프레임을 changed_items 에 싣는다.
 *       (구 구현이 별도로 병행 발행하던 {@code DatasetReExportEvent} 는 이중 export/이중 통지를 유발해 제거됐다 —
 *       재산출 트리거 축은 이 {@code regen=true} 한 경로뿐이다.)</li>
 *   <li>{@code false}(기본) — 디스크 산출물은 그대로다. 통지는 changed_items 를 비운 채 발송되고,
 *       관제는 통지를 받은 뒤 {@code V_COMPLETED_META} 등 뷰로 메타를 다시 읽는다
 *       (CLAUDE.md "관제서버 조회 패턴"). 파일이 안 바뀌었는데 전 프레임을 실으면 관제가 수천 개
 *       파일을 헛 재픽업한다.</li>
 * </ul>
 * 프레임 단위 변경({@code srcSn != null})은 이 플래그와 무관하게 변경 프레임만 싣는다.
 *
 * <h3>{@code needsRecheck} — 검수 재검토 표시 축 (Phase 7a-1, 신규)</h3>
 * 이 변경이 <b>사람이 콘텐츠를 고치는 경로</b>인가를 이벤트가 직접 싣는다. {@code exportRegenerated}
 * 와 <b>같은 원칙</b>이다 — 발행처 클래스명으로 추정하지 않는다(새 발행처가 생겨도 규칙이 유지되고,
 * 소비부가 발행처를 알 필요가 없다). {@code true} 이면 {@code AFTER_COMMIT} 리스너가
 * {@code LS_RAW_DATA_STATUS.REVLT_YN='Y'} 로 표시한다(멱등). <b>이번 단계(7a-1)는 표시를 세우기만
 * 한다</b> — 그 표시를 읽어 통지·export 흐름을 바꾸는 것은 후속(7a-2)이다. 기본값은 {@code false}
 * (재검토 표시 없음)로, 이 축이 추가되기 전 발행처의 동작을 그대로 보존한다.
 *
 * @param rawSn             영상 단위 식별자 (LS_DATA_RAW.RAW_SN)
 * @param srcSn             변경 프레임 ID (LS_DATA_SRC.SRC_SN) — 영상 단위 변경이면 null
 * @param changeType        변경 종류 (LABEL_ADDED, LABEL_UPDATED, LABEL_DELETED, META_UPDATED)
 * @param modifierNo        수정자 번호
 * @param exportRegenerated 이 변경이 export 폴더 재생성을 동반하는가 (위 설명 참조)
 * @param needsRecheck      이 변경이 검수 재검토가 필요한 콘텐츠 수정인가 (위 설명 참조)
 */
public record TaskModifiedEvent(
        Long rawSn,
        Long srcSn,
        String changeType,
        Long modifierNo,
        boolean exportRegenerated,
        boolean needsRecheck
) {

    /**
     * {@code needsRecheck} 를 생략하는 5-arg 오버로드(기본 {@code false}) — Phase 7a-1 이전부터 있던
     * 발행처의 호출 시그니처를 그대로 유지하기 위한 하위호환 생성자.
     */
    public TaskModifiedEvent(Long rawSn, Long srcSn, String changeType, Long modifierNo,
                              boolean exportRegenerated) {
        this(rawSn, srcSn, changeType, modifierNo, exportRegenerated, false);
    }

    /**
     * export 재생성도 재검토 표시도 <b>동반하지 않는</b> 변경 (기본) — 라벨/메타 수정 등 디스크
     * 산출물이 그대로인 경로.
     *
     * <p>기본값을 {@code false} 로 둔 근거: 현재 발행처 대부분이 재생성을 하지 않으며, 잘못 {@code true}
     * 로 새면 관제가 안 바뀐 파일을 전량 재픽업한다(관측 불가한 낭비). 반대 방향의 누락은 관제가
     * 통지를 받고 뷰를 재조회하는 설계된 흐름으로 흡수된다.
     */
    public TaskModifiedEvent(Long rawSn, Long srcSn, String changeType, Long modifierNo) {
        this(rawSn, srcSn, changeType, modifierNo, false, false);
    }
}
