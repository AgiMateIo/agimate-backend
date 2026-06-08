package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to issue a trigger-log discovery probe code")
public record IssueProbeRequest(
        @Schema(description = "If true (default), triggers carrying this probe code are saved to trigger_logs but NOT delivered to agents. If false, normal routing applies (useful for diagnosing delivery).")
        Boolean blockDelivery
) {}
