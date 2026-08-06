package kr.co.cudo.authoring.video.dto;

import java.math.BigDecimal;

/**
 * 인입 back-fill 대상 8컬럼의 <b>채택 결과</b> — 측정값 해석기
 * ({@code upload.service.InternalUploadMetaResolver})가 만들고
 * {@code video.repository.InternalUploadIngestWriter} 가 소비하는 교차 도메인 DTO.
 *
 * <h3>왜 {@code video.dto} 에 있는가 (패키지 방향)</h3>
 * <p>이 레코드는 {@code upload} 가 만들어 {@code video} 로 <b>넘기는</b> 값이다. 생산자 패키지에
 * 두면 수신측({@code video.repository})이 {@code upload.service} 를 import 하게 되어
 * {@code video → upload} <b>역방향 의존</b>이 생긴다. 그래서 이 저장소의 확립된 관례대로
 * <b>수신측 패키지에 정의</b>하고 생산자가 그것을 import 한다({@link InternalUploadIngestCommand}
 * 와 동일한 배치 — 두 DTO 모두 같은 흐름의 입력이라 나란히 둔다).
 *
 * <h3>{@code null} 은 "그 컬럼을 건드리지 않는다"는 뜻이다</h3>
 * <p>0·빈문자열로 대체하지 말 것 — back-fill SQL 은 {@code COALESCE} 로 기존 값을 남기므로
 * {@code null} 인자는 <b>사용자 입력 보존</b>으로 귀결된다(R4). 대용값을 채우면 그 보존이 깨진다.
 *
 * <h3>화소({@code PXL})·색심도({@code BIT}) 필드가 <b>없는 것이 곧 R3 의 구현체</b>다</h3>
 * <p>표기 규약이 정의돼 있지 않아 무엇을 넣든 지어낸 값이 되므로(프로젝트 "값을 지어내지 않는다"
 * 원칙) 인자로 넘길 수단조차 두지 않는다. 여기에 그 필드를 추가하는 것은 정책 위반이며
 * back-fill SQL 의 부재를 고정하는 구조 가드({@code LsDataIngestWriteGuardTest})와도 어긋난다.
 *
 * @param vdoLenSec 영상길이(초, 정수) — {@code VDO_LEN_SEC NUMERIC(10)}
 * @param fps       프레임재생속도 표기(예 {@code 29.97}) — {@code FPS VARCHAR(10)}
 * @param vdoCdc    영상코덱(예 {@code h264}) — {@code VDO_CDC VARCHAR(20)}
 * @param wdth      너비(px) — {@code WDTH NUMERIC(10)}
 * @param vrtc      세로(px) — {@code VRTC NUMERIC(10)}
 * @param resl      해상도(예 {@code 1920x1080}, 소문자 x) — {@code RESL VARCHAR(20)}
 * @param frmeCnt   프레임수 — {@code FRME_CNT NUMERIC(10)}
 * @param asprtRt   종횡비(예 {@code 16:9}) — {@code ASPRT_RT VARCHAR(20)}
 * @req R2, R3
 */
public record ResolvedIngestMeta(
        BigDecimal vdoLenSec,
        String fps,
        String vdoCdc,
        BigDecimal wdth,
        BigDecimal vrtc,
        String resl,
        BigDecimal frmeCnt,
        String asprtRt) {

    /** 채택된 값이 하나도 없으면 true — 호출자는 UPDATE 자체를 건너뛴다. */
    public boolean isEmpty() {
        return vdoLenSec == null && fps == null && vdoCdc == null && wdth == null
                && vrtc == null && resl == null && frmeCnt == null && asprtRt == null;
    }
}
