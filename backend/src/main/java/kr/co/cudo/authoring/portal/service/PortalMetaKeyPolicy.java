package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.meta.service.MetaService;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.video.service.VideoMetaService;

/**
 * 포털 채널이 메타 키를 <b>어느 목록으로 내려주고 무엇을 고칠 수 있게 할지</b>의 단일 판정 지점.
 *
 * <h3>★ 내부 원장의 판정을 재사용한다 — 새 목록을 만들지 않는다</h3>
 * <p>기술메타는 {@link VideoMetaService#isTechnicalKey}, 읽기 전용({@code vlm.*} 비편집)과 이관
 * 원문({@code import.*})은 {@link MetaService#isReadOnlyKey} · {@link MetaService#isImportedKey} 를
 * <b>그대로 부른다</b>. 접두·키 문자열을 여기에 복제하면 두 번째 진실원이 되어 소유자가 키를 늘리는
 * 날 포털만 조용히 뒤처진다.
 *
 * <h3>★★ 포털이 <b>더한</b> 축은 하나뿐이다 — 포털 파이프라인 내부 키</h3>
 * <p>{@code portal.*}({@link PortalUploadLedger#PORTAL_KEY_PREFIX})는 업로드 처리 상태·실패 사유를
 * 담는 <b>파이프라인 내부 값</b>이다. 내부 원장의 분류기에는 이 축이 없어(그 창구는 포털 자산을
 * 다루지 않는다) 그대로 두면 <b>여집합으로 떨어져 편집 가능 목록에 들어간다</b>. 그러면 사용자가
 * 업로드 처리 상태를 임의로 완료로 바꿔 상태 기계를 통째로 우회한다 — 오류 없이 통과하는 fail-open
 * 이다. 그래서 이 접두는 포털 채널에서 <b>읽기 전용</b>이고 저장 요청은 400 으로 거부한다.
 *
 * <p>이 축의 소유자는 {@link PortalUploadLedger} 이며 접두 문자열을 여기서 다시 선언하지 않는다.
 *
 * <h3>★ 컬럼 축 셋은 <b>명시 분기</b>로 편집 가능이다</h3>
 * <p>촬영환경({@code env.*})·개인정보 판정({@code privacy.*})·프레임 설명({@code frame.*})은 원장의
 * 컬럼에서 조달해 키/값으로 바꿔 내리는 축이며({@link PortalColumnMetaField}) <b>사용자가 고칠 수
 * 있는</b> 항목이다. 여집합으로 떨어뜨려도 지금은 결과가 같지만 그러면 <b>소유가 불분명</b>해져,
 * 다른 소유자가 접두를 늘리는 날 이 축이 조용히 표시 전용으로 넘어간다. 그래서 분기를 명시로 두고
 * <b>맨 앞</b>에 놓는다.
 *
 * <h3>목록 배분</h3>
 * <ul>
 *   <li>{@code technicalMeta} — 영상 기술 메타({@code video.*}). 사람이 고치는 값이 아니다.</li>
 *   <li>{@code readOnlyMeta} — 표시만 하고 고칠 수 없는 나머지: 자동 산출 읽기 전용 · 이관 원문 ·
 *       포털 파이프라인 내부 키.</li>
 *   <li>{@code items} — 확인·수정·추가할 수 있는 메타.</li>
 * </ul>
 * <p>⚠ 내부 창구는 이관 원문을 <b>네 번째 목록</b>({@code importedMeta})으로 따로 내려주지만 포털
 * 계약에는 그 목록이 없다. 버리면 정보가 사라지므로 「표시만 하고 고칠 수 없는」 목록으로 합친다 —
 * 편집 가능 여부는 두 축이 같으므로 <b>권한 판정에는 차이가 없다</b>.
 *
 * @design API-234
 * @design API-235
 */
public final class PortalMetaKeyPolicy {

    private PortalMetaKeyPolicy() {
    }

    /** 응답의 어느 목록으로 갈지. */
    public enum Bucket {
        /** 확인·수정·추가할 수 있는 메타. */
        EDITABLE,
        /** 표시만 하고 고칠 수 없는 메타. */
        READ_ONLY,
        /** 영상 기술 메타. */
        TECHNICAL
    }

    /**
     * 키의 목록 배분.
     *
     * <p><b>분기 순서를 지킨다</b> — 기존 두 판정(기술 → 자동 산출 읽기 전용)을 앞에 두고, 이관 원문과
     * 포털 내부 키를 그 뒤·여집합 앞에 넣는다. 현재 네 접두는 서로 겹치지 않아 순서를 바꿔도 결과가
     * 같지만, 소유자가 접두를 늘렸을 때 기존 판정이 먼저 걸리도록 순서로 못박아 둔다.
     */
    public static Bucket bucketOf(String metaKey) {
        // ★ 컬럼 축 셋(촬영환경·프레임 설명·개인정보 판정)은 <이 창구가 소유하는 축>이라 가장 먼저
        //   판정한다. 여집합으로 떨어뜨리면 결과는 같아 보여도 <소유가 불분명>해져, 다른 소유자가
        //   접두를 늘리는 날 사용자가 고칠 수 있어야 할 항목이 조용히 표시 전용으로 넘어간다.
        if (PortalColumnMetaField.isColumnKey(metaKey)) {
            return Bucket.EDITABLE;
        }
        if (VideoMetaService.isTechnicalKey(metaKey)) {
            return Bucket.TECHNICAL;
        }
        if (MetaService.isReadOnlyKey(metaKey)) {
            return Bucket.READ_ONLY;
        }
        if (MetaService.isImportedKey(metaKey)) {
            return Bucket.READ_ONLY;
        }
        if (isPortalPipelineKey(metaKey)) {
            return Bucket.READ_ONLY;
        }
        return Bucket.EDITABLE;
    }

    /** 저장 요청이 이 키를 고칠 수 있는가 — {@link Bucket#EDITABLE} 만 참(fail-closed). */
    public static boolean isEditable(String metaKey) {
        return bucketOf(metaKey) == Bucket.EDITABLE;
    }

    /**
     * 포털 파이프라인 내부 키 판정. 업로드 처리 상태·실패 사유가 여기 담긴다.
     * 접두는 {@link PortalUploadLedger#PORTAL_KEY_PREFIX} 소유이며 여기서 재선언하지 않는다.
     */
    private static boolean isPortalPipelineKey(String metaKey) {
        return metaKey != null && metaKey.startsWith(PortalUploadLedger.PORTAL_KEY_PREFIX);
    }
}
