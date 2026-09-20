package com.techeazy.notification.clientapi;

import com.techeazy.notification.clientapi.Dtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/templates")
@Tag(name = "Templates")
public class TemplateController {

    private final ClientTemplateService templates;

    public TemplateController(ClientTemplateService templates) {
        this.templates = templates;
    }

    @Operation(summary = "List the templates you can use: your own (editable) and the shared ones (read-only)")
    @GetMapping
    public List<TemplateView> list(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client) {
        return templates.list(client);
    }

    @Operation(summary = "Get one template")
    @GetMapping("/{id}")
    public TemplateView get(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client, @PathVariable UUID id) {
        return templates.get(client, id);
    }

    @Operation(summary = "Create a template of your own",
            description = "Send with it by passing its name as templateName. Your template takes precedence over a shared one of the same name.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView create(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                               @Valid @RequestBody TemplateInput in) {
        return templates.create(client, in);
    }

    @Operation(summary = "Edit one of your templates",
            description = "Requests already accepted keep the content they were accepted with; the edit applies to new requests only.")
    @PutMapping("/{id}")
    public TemplateView update(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                               @PathVariable UUID id, @Valid @RequestBody TemplateInput in) {
        return templates.update(client, id, in);
    }

    @Operation(summary = "Delete one of your templates", description = "Requests already accepted are not affected.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client, @PathVariable UUID id) {
        templates.delete(client, id);
    }

    @Operation(summary = "Preview content with sample values",
            description = "Nothing is stored or sent. Unresolved placeholders stay visible and are listed in missingVariables.")
    @PostMapping("/preview")
    public PreviewView preview(@Valid @RequestBody PreviewRequest req) {
        return templates.preview(req);
    }
}
