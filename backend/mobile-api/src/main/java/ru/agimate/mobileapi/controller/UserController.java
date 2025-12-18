package ru.agimate.mobileapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.websocket.server.PathParam;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.mobileapi.controller.dto.request.AddConnectionRequest;

import java.util.List;

/**
 * This controller is used to handle dashboard's calls
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Operation(summary = "Returns all connections")
    @GetMapping("/connections")
    public SuccessResponse<List<String>> connections() {
        return SuccessResponse.ok(List.of("one", "two"));
    }

    @Operation(summary = "Add new connection")
    @PostMapping("/connections")
    public SuccessResponse<String> addConnection(
            @RequestBody
            AddConnectionRequest addConnectionRequest
    ) {
        // TODO: implement later
        return SuccessResponse.ok(addConnectionRequest.name());
    }

    @Operation(summary = "Delete a connection")
    @DeleteMapping("/connections/{connectionId}")
    public SuccessResponse<String> deleteConnection(
            @PathParam("connectionId")
            String connectionId
    ) {
        // TODO: implement later
        return SuccessResponse.ok(connectionId);
    }
}
