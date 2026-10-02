/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.adminapi;

import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.error.ErrorCodeView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

/**
 * The error dictionary (ADR-031) on the admin API, so the {@code docs} link in its error responses resolves on the
 * host that answered. Served without a session, like the client API's, because the dictionary holds nothing secret
 * and a refusal to sign in links to it too.
 */
@RestController
@RequestMapping(ErrorCatalogueController.PATH)
public class ErrorCatalogueController {

    public static final String PATH = "/api/admin/errors";

    @GetMapping
    public List<ErrorCodeView> all() {
        return Arrays.stream(ErrorCode.values()).map(ErrorCodeView::of).toList();
    }

    @GetMapping("/{errorIdOrCode}")
    public ErrorCodeView one(@PathVariable String errorIdOrCode) {
        return ErrorCode.findByErrorIdOrCode(errorIdOrCode)
                .map(ErrorCodeView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No error code " + errorIdOrCode));
    }
}
