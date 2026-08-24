package kr.co.cudo.authoring.video.dto;

import java.math.BigDecimal;

/**
 * 인입 back-fill 대상 9컬럼의 <b>채택 결과</b> — 측정값 해석기
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
 * <h3>화소({@code PXL}) 필드가 <b>없는 것이 곧 R3 의 구현체</b>다</h3>
 * <p>화소는 등급 표기(예 {@code 4K})라 표기 규약이 정의돼 있지 않고, 무엇을 넣든 지어낸 값이 되므로
 * (프로젝트 "값을 지어내지 않는다" 원칙) 인자로 넘길 수단조차 두지 않는다. 여기에 {@code pxl} 필드를
 * 추가하는 것은 정책 위반이며 back-fill SQL 의 부재를 고정하는 구조 가드
 * ({@code LsDataIngestWriteGuardTest})와도 어긋난다.
 *
 * <h3>⚠ {@code BIT} 은 더 이상 R3 대상이 아니다 — 색심도가 아니라 <b>비트레이트</b>다</h3>
 * <p>구 정의는 색심도 표기({@code 24bit})였고 그래서 R3 가 두 컬럼을 함께 묶었다. 관제 실측값이
 * <b>bps 정수</b>({@code 2050627})임이 확인돼 컬럼 의미가 재정의됐고(설계 ERD-012), 그 순간
 * "표기 규약이 없어 지어낸 값이 된다"는 R3 의 근거가 <b>BIT 에 한해 소멸</b>했다 — bps 정수는 표기가
 * 모호하지 않고 ffprobe 가 직접 산출한다. 따라서 {@link #bit} 필드는 정책 위반이 아니라 <b>정책 정합</b>
 * 이다. 이 문단을 읽지 않고 "R3 위반"으로 되돌리지 말 것. R3 가 계속 막는 것은 {@code PXL} 하나다.
 *
 * @param vdoLenSec 영상길이(초, 정수) — {@code VDO_LEN_SEC NUMERIC(10)}
 * @param fps       프레임재생속도 표기(예 {@code 29.97}) — {@code FPS VARCHAR(10)}
 * @param vdoCdc    영상코덱(예 {@code h264}) — {@code VDO_CDC VARCHAR(20)}
 * @param wdth      너비(px) — {@code WDTH NUMERIC(10)}
 * @param vrtc      세로(px) — {@code VRTC NUMERIC(10)}
 * @param resl      해상도(예 {@code 1920x1080}, 소문자 x) — {@code RESL VARCHAR(20)}
 * @param frmeCnt   프레임수 — {@code FRME_CNT NUMERIC(10)}
 * @param asprtRt   종횡비(예 {@code 16:9}) — {@code ASPRT_RT VARCHAR(20)}
 * @param bit       비트레이트(bps <b>정수 문자열</b>, 예 {@code 2050627}) — {@code BIT VARCHAR(20)}.
 *                  단위 접미사({@code kbps} 등)를 붙이지 않는다 — 관제가 보내는 표기와 같아야 한 컬럼에
 *                  두 표기가 섞이지 않는다.
 * @req R2, R3
 * @design ERD-012
 */
public record ResolvedIngestMeta(
        BigDecimal vdoLenSec,
        String fps,
        String vdoCdc,
        BigDecimal wdth,
        BigDecimal vrtc,
        String resl,
        BigDecimal frmeCnt,
        String asprtRt,
        String bit) {

    /** 채택된 값이 하나도 없으면 true — 호출자는 UPDATE 자체를 건너뛴다. */
    public boolean isEmpty() {
        return vdoLenSec == null && fps == null && vdoCdc == null && wdth == null
                && vrtc == null && resl == null && frmeCnt == null && asprtRt == null
                && bit == null;
    }
}
