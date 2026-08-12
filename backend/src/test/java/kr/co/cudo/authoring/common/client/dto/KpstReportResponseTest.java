package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R14 — KPST {@code GET /retrieve_report} 응답 스키마 정합 가드.
 *
 * <p>아래 JSON 은 목서버({@code mock-server/app/routers/deid.py} 의 {@code retrieve_report})가
 * 실제로 만들어 내는 형태를 그대로 옮긴 것이며, 규격서 §22.3.7 응답 필드표와 일치한다.
 * 벤더/목이 스키마를 바꾸면 이 테스트가 먼저 깨진다.
 *
 * <p><b>주의</b> — 목의 {@code prjStatus[]} 에는 {@code prjId} 가 없다(진행조회와 달리 규격 응답
 * 필드표에도 없다). 따라서 데이터셋 매칭은 프로젝트가 아니라 {@code dsStatus[].dsId} 로 한다.
 */
class KpstReportResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 목서버가 반환하는 형태 그대로. */
    private static final String MOCK_JSON = """
            {
              "result": "success",
              "data": {
                "prjCount": 1,
                "prjStatus": [
                  {
                    "progressRate": 100.0,
                    "dsStatus": [
                      {
                        "dsId": 1270,
                        "fileName": "/nas/raw/001.mp4",
                        "faceCount": 12,
                        "lpCount": 3,
                        "totalFrame": 5400,
                        "startTime": "2026-08-11 10:00:00",
                        "endTime": "2026-08-11 10:05:30"
                      }
                    ]
                  }
                ]
              }
            }
            """;

    @Test
    @DisplayName("목서버_응답_스키마를_그대로_역직렬화한다")
    void deserializesMockSchema() throws Exception {
        KpstReportResponse res = MAPPER.readValue(MOCK_JSON, KpstReportResponse.class);

        assertThat(res.result()).isEqualTo("success");
        assertThat(res.data().prjCount()).isEqualTo(1);
        KpstReportResponse.DsStatus ds = res.data().prjStatus().get(0).dsStatus().get(0);
        assertThat(ds.dsId()).isEqualTo(1270L);
        assertThat(ds.fileName()).isEqualTo("/nas/raw/001.mp4");
        assertThat(ds.faceCount()).isEqualTo(12L);
        assertThat(ds.lpCount()).isEqualTo(3L);
        assertThat(ds.totalFrame()).isEqualTo(5400L);
        assertThat(ds.startTime()).isEqualTo("2026-08-11 10:00:00");
        assertThat(ds.endTime()).isEqualTo("2026-08-11 10:05:30");
    }

    @Test
    @DisplayName("완료_프로젝트가_없으면_prjStatus_가_비어도_역직렬화된다")
    void deserializesEmptyResult() throws Exception {
        String json = """
                {"result":"success","data":{"prjCount":0,"prjStatus":[]}}
                """;

        KpstReportResponse res = MAPPER.readValue(json, KpstReportResponse.class);

        assertThat(res.data().prjCount()).isZero();
        assertThat(res.data().prjStatus()).isEmpty();
    }

    @Test
    @DisplayName("규격에_없는_추가_필드와_null_숫자를_수용한다")
    void tolerantToUnknownFieldsAndNulls() throws Exception {
        String json = """
                {"result":"success","data":{"prjCount":null,"prjStatus":[
                  {"prjId":279,"progressRate":null,"vendorExtra":"x","dsStatus":[
                    {"dsId":1270,"faceCount":null,"lpCount":null,"totalFrame":null,
                     "startTime":null,"endTime":"None","unknown":1}]}]}}
                """;

        KpstReportResponse res = MAPPER.readValue(json, KpstReportResponse.class);

        KpstReportResponse.PrjStatus prj = res.data().prjStatus().get(0);
        assertThat(prj.prjId()).isEqualTo(279L);
        assertThat(prj.progressRate()).isNull();
        assertThat(prj.dsStatus().get(0).faceCount()).isNull();
        assertThat(prj.dsStatus().get(0).endTime()).isEqualTo("None");
    }
}
