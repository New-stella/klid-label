package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import kr.co.cudo.authoring.dataset.export.json.VlmDescriptionPolicy;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

/**
 * {@link NiaExportContextAssembler} 산출 — 한 영상(rawSn)의 NIA 어노테이션 문서 조달 결과.
 *
 * <p>{@link #videoContext()} 만으로 프레임 문서를 만들 수 있지만, 조달 <b>중간값</b>(원천 축 개인정보 ·
 * VLM 서술 · 비식별 영상 경로)도 함께 싣는다. 호출자가 그 값을 다시 유도하면 조달 규칙이 두 곳이 되어
 * 조용히 어긋나기 때문이다 — 실제로 산출 경로는 이 두 값을 콘텐츠 해시 입력으로도 쓴다.
 *
 * @param meta          활성 영상 메타 스냅샷 (검수 승인 시점 동결본)
 * @param raw           라이브 원시 영상 행 (부재 가능 — null)
 * @param videoContext  프레임 문서 조립용 컨텍스트
 * @param srcPrivacy    원천 축 개인정보 입력. 파생영상·영상행 부재면 {@link SourcePrivacyMeta#NONE}
 *                      이며 그때 원천 3필드가 두 블록 모두 null 인 것이 <b>정상</b>이다(결손 아님).
 * @param vdDescription {@code video.vd_description} — 조달 규칙 소유자는 {@link VlmDescriptionPolicy}.
 *                      원천이 없으면 null(값을 지어내지 않는다)
 * @param deidVideoPath 비식별 <b>영상</b> 파일 경로(최신 SUCCEEDED 비식별 처리 이력의 적재값).
 *                      미상이면 null — <b>원본 경로로 폴백하지 않는다</b>(CWE-359)
 */
public record NiaExportContext(
        LsDatasetVideoMeta meta,
        LsDataRaw raw,
        VideoExportContext videoContext,
        SourcePrivacyMeta srcPrivacy,
        String vdDescription,
        String deidVideoPath
) {
}
