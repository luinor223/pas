package com.abclogistics.pas.billing.controller.http;

import com.abclogistics.pas.billing.service.StatementService;
import com.abclogistics.pas.common.error.GlobalExceptionHandler;
import com.abclogistics.pas.common.error.UnprocessableEntityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatementControllerErrorTest {

    private StatementService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StatementService.class);
        mvc = MockMvcBuilders.standaloneSetup(new StatementController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingPeriodReturnsActionable422InsteadOfInternalError() throws Exception {
        when(service.calculate(any())).thenThrow(new UnprocessableEntityException(
                "BILLING_PERIOD_NOT_FOUND",
                "Billing period 2026-06 does not exist. "
                        + "Create it in Volume Records before calculating a statement.",
                "Operations volume lookup failed with NOT_FOUND"));

        mvc.perform(post("/payment-statements/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractId\":\"d1111111-1111-4111-8111-111111111111\","
                                + "\"periodCode\":\"2026-06\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BILLING_PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        "Billing period 2026-06 does not exist. "
                                + "Create it in Volume Records before calculating a statement."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("NOT_FOUND"))));
    }
}
