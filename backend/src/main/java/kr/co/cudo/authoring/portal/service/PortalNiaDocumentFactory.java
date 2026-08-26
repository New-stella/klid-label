package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.dataset.export.json.AnnotationSource;
import kr.co.cudo.authoring.dataset.export.json.NiaAnnotationDoc;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 포털 산출물의 프레임 어노테이션 문서를 만드는 <b>유일한 진입점</b> — 산출 종류를 비식별로 고정한다.
 *
 * <h3>왜 별도 진입점인가 (fail-closed)</h3>
 * <p>공유 빌더({@link NiaJsonBuilder#build})는 산출 종류({@link ExportKind})를 <b>자유 파라미터</b>로
 * 받는다. 포털이 {@link ExportKind#ORIGINAL} 을 넘기면 그 순간 문서의 경로 필드
 * ({@code dataset.src_path} · {@code video.filename})에 <b>원본(비식별 이전) 영상의 절대경로와
 * 파일명</b>이 실려 나간다. 포털은 외부 채널이라 이는 원본 폴백 금지(AC-034)를 정면으로 위반하며,
 * 파일을 담지 않아도 경로만으로 원본 위치가 외부에 노출된다(CWE-359).
 *
 * <p>"호출부에서 잘 넘기면 된다"에 기대지 않는다 — 이 저장소가 반복해서 뚫린 형태다. 그래서 포털
 * 경로가 <b>산출 종류를 넘길 수 있는 표면 자체를 없앤다</b>: 이 컴포넌트의 공개 메서드에는 그
 * 파라미터가 없고, 값은 {@link #PORTAL_EXPORT_KIND} 하나로 고정된다.
 *
 * <p>상수가 나중에 바뀌어도 조용히 새지 않도록 <b>실행 시점에도 한 번 더</b> 확인한다. 정적 회귀
 * 가드({@code PortalNiaExportKindGuardTest})가 ①이 상수 값 ②포털 패키지 전체에
 * {@link ExportKind#ORIGINAL} 참조 0건 ③공유 빌더를 <b>타입으로</b> 언급하는 파일이 이 클래스 하나뿐임을
 * 함께 고정한다.
 *
 * @design API-203, AC-034
 */
@Component
@RequiredArgsConstructor
public class PortalNiaDocumentFactory {

    /**
     * 포털 산출물의 산출 종류 — <b>비식별 고정</b>. 포털은 비식별본만 서빙한다는 불변 규칙(AC-034)의
     * 코드 표현이며, 다른 값으로 바꾸면 아래 실행 시점 확인과 정적 가드가 함께 실패한다.
     */
    public static final ExportKind PORTAL_EXPORT_KIND = ExportKind.DEIDENTIFIED;

    private final NiaJsonBuilder niaJsonBuilder;

    /**
     * 한 프레임의 자기완결 어노테이션 문서를 만든다 — 검수 승인 산출물과 <b>같은 빌더·같은 판정</b>이라
     * 좌표 해석이 두 산출물에서 갈리지 않는다.
     *
     * @param ctx     영상 단위 컨텍스트({@code NiaExportContextAssembler} 산출)
     * @param frame   대상 프레임(데이터마트 프레임 — 포털도 그대로 쓴다)
     * @param sources 그 프레임에 확정된 라벨의 최소 입력(병합 판정을 마친 목록)
     */
    public NiaAnnotationDoc build(VideoExportContext ctx, LsDataSrc frame, List<AnnotationSource> sources) {
        if (PORTAL_EXPORT_KIND != ExportKind.DEIDENTIFIED) {
            // 도달하면 상수가 바뀐 것이다 — 원본 경로를 실어 내보내느니 산출을 멈춘다(fail-closed).
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "다운로드 자료를 만들지 못했습니다.");
        }
        return niaJsonBuilder.build(ctx, frame, sources, PORTAL_EXPORT_KIND);
    }
}
