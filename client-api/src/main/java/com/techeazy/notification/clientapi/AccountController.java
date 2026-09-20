package com.techeazy.notification.clientapi;

import com.techeazy.notification.clientapi.Dtos.MeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Tag(name = "Account")
public class AccountController {

    @Operation(summary = "Who am I: the client behind the API key and the channels it may use")
    @GetMapping("/me")
    public MeView me(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client) {
        return new MeView(client.id(), client.name(), client.allowedChannels());
    }
}
