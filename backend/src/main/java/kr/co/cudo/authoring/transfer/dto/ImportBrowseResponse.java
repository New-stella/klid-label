package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 이관 대상 위치 <b>탐색</b> 결과 — 폴더 탐색과 영상 파일 탐색이 같은 모양을 쓴다.
 *
 * <h3>왜 두 창구가 같은 응답을 쓰는가</h3>
 * <p>화면이 하는 일이 같기 때문이다 — 목록을 보여 주고, 고른 값을 입력칸에 넣고, 한 단계 위로
 * 올라간다. 담기는 것이 폴더냐 파일이냐만 다르므로 모양을 나누면 화면이 같은 코드를 두 벌 갖는다.
 * 반대로 <b>창구는 반드시 둘</b>이다 — 같은 자리에 종류를 가르는 조건을 달아 동작을 바꾸지 않는다는
 * 규약 때문이며, 그래서 응답을 공유해도 판정이 섞이지 않는다.
 *
 * <h3>{@code path} 는 표기가 아니라 실제로 닿는 자리다</h3>
 * <p>요청이 적어 보낸 표기를 그대로 되돌려 주면, 화면이 그 값을 다시 검사에 넣었을 때 판정이
 * 바로가기를 따라간 뒤의 자리로 갈려 왕복이 어긋난다. 그래서 판정에 <b>실제로 쓴</b> 경로를 싣고,
 * 그 값은 다시 넣어도 같은 판정을 통과한다.
 *
 * <h3>{@code truncated} 를 비워 두지 않는다</h3>
 * <p>담는 개수에 상한이 있는데 잘랐다는 사실을 싣지 않으면 화면이 잘린 목록을 전부인 것으로
 * 보여 준다. 조용한 절단은 "덜 들어온 것"과 "원래 그만큼인 것"을 구분할 수 없게 만든다(CWE-770).
 *
 * @param path      판정에 사용한 실경로. 허용 저장소 루트 목록을 돌려줄 때는 기준 위치가 하나로
 *                  정해지지 않으므로 {@code null}
 * @param parent    한 단계 위 폴더. 그 자리가 <b>허용 저장소 범위를 벗어나면</b> {@code null} 이고,
 *                  그것은 지금 자리가 허용 저장소 루트라는 뜻이다(화면은 그때 루트 목록으로
 *                  돌아간다). 부모가 <b>또 다른 허용 루트인 것은 범위 안이라 비우지 않는다</b> —
 *                  비우면 루트 바로 아래에서 올라갈 길이 사라져 사람이 갇힌다. 범위 밖 위치를
 *                  여기에 실으면 판정이 막은 범위를 응답이 되돌려 준다
 * @param entries   목록. 이름 오름차순으로 정렬된다
 * @param truncated 담는 개수 상한에 걸려 잘렸는지 여부
 * @design API-221
 * @design API-222
 * @design AC-120
 */
@Schema(description = "이관 대상 위치 탐색 결과")
public record ImportBrowseResponse(
        String path,
        String parent,
        List<Entry> entries,
        boolean truncated) {

    /**
     * 목록 1건.
     *
     * @param name 이름(폴더명 또는 파일명)
     * @param path 그 항목의 위치. 다음 단계를 탐색하거나 입력칸에 넣을 때 그대로 쓴다
     */
    @Schema(description = "탐색 목록 항목")
    public record Entry(String name, String path) {
    }
}
