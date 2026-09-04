package com.abclogistics.pas.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseAdviceTest {

    private final ApiResponseAdvice advice = new ApiResponseAdvice();
    private final ObjectMapper mapper = new ObjectMapper();

    private Object wrap(Object body) {
        return advice.beforeBodyWrite(body, null, null, null, null, null);
    }

    @Test
    void wrapsSingleResourceInDataWithoutMeta() {
        Object out = wrap(new Dto("vi"));
        String json = mapper.writeValueAsString(out);
        assertThat(json).isEqualTo("{\"data\":{\"name\":\"vi\"}}");
    }

    @Test
    void wrapsPageWithPaginationMeta() {
        var page = new PageImpl<>(List.of(new Dto("a"), new Dto("b")), PageRequest.of(0, 20), 137);
        Object out = wrap(page);
        String json = mapper.writeValueAsString(out);
        assertThat(json).contains("\"data\":[{\"name\":\"a\"},{\"name\":\"b\"}]");
        assertThat(json).contains("\"meta\":{\"page\":0,\"size\":20,\"totalElements\":137,\"totalPages\":7}");
    }

    @Test
    void leavesErrorBodyUntouched() {
        ApiError err = ApiError.of(404, "Not Found", "gone", "/x");
        assertThat(wrap(err)).isSameAs(err);
    }

    @Test
    void errorFactoryProvidesAStableCodeAndRedactsUuidPathSegments() {
        ApiError err = ApiError.of(403, "Forbidden", "ACCESS_DENIED", "Not allowed",
                "/contracts/50000000-0000-4000-8000-000000000001/attachments");

        assertThat(err.code()).isEqualTo("ACCESS_DENIED");
        assertThat(err.path()).isEqualTo("/contracts/{id}/attachments");
    }

    @Test
    void doesNotDoubleWrap() {
        ApiResponse<Dto> already = ApiResponse.of(new Dto("x"));
        assertThat(wrap(already)).isSameAs(already);
    }

    @Test
    void passesBinaryBodiesThrough() {
        byte[] bytes = {1, 2, 3};
        assertThat(wrap(bytes)).isSameAs(bytes);
    }

    record Dto(String name) { }
}
