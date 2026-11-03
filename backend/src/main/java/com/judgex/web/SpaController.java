package com.judgex.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the built Vue SPA from the same origin as the API.
 * <p>
 * The frontend is compiled to {@code src/main/resources/static} before packaging, so the whole
 * product runs behind a single port. Deep links are forwarded to {@code index.html} and the
 * client-side router takes over.
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/problems", "/problems/**", "/submissions", "/dashboard", "/admin"})
    public String index() {
        return "forward:/index.html";
    }
}
