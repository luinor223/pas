package com.abclogistics.pas.esign;

import com.abclogistics.pas.common.api.ApiResponseAdvice;
import com.abclogistics.pas.esign.controller.http.SigningSessionController;
import com.abclogistics.pas.esign.dto.SigningSessionResponse;
import com.abclogistics.pas.esign.service.SigningSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SigningSessionControllerTest {

    @Test
    void listUsesTheStandardArrayAndPaginationEnvelope() throws Exception {
        SigningSessionService service = mock(SigningSessionService.class);
        SigningSessionResponse session = new SigningSessionResponse(
                UUID.fromString("c1381979-8e1c-457d-8472-2cedfe73a462"),
                "SIG-5",
                "CONTRACT",
                UUID.fromString("d5555555-5555-4555-8555-555555555555"),
                "CTR-2026-0005",
                "Tan Cang Logistics",
                "Do Minh Khoa",
                "khoa.dm@tancang.vn",
                "MockSign",
                "MOCK-9553ec06",
                "SIGNED",
                1,
                null,
                "System Administrator",
                Instant.parse("2026-09-04T18:06:30Z"),
                Instant.parse("2026-09-04T18:06:44Z"),
                Instant.parse("2026-09-04T18:06:28Z")
        );
        when(service.listSessions(isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(session), PageRequest.of(0, 15), 1));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SigningSessionController(service))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new ApiResponseAdvice())
                .build();

        mvc.perform(get("/signing-sessions").param("page", "0").param("size", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sessionNo").value("SIG-5"))
                .andExpect(jsonPath("$.data[0].documentNo").value("CTR-2026-0005"))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(15))
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(1))
                .andExpect(jsonPath("$.data.content").doesNotExist());
    }
}
