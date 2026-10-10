package com.codesage.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * When the React build is bundled into the backend image (single-container deployment, e.g. Render),
 * client-side routes are forwarded to index.html so deep links and page reloads work.
 * In the docker-compose setup nginx serves the SPA and these routes are never reached.
 */
@Controller
public class SpaForwardingController {

    @GetMapping({"/", "/login", "/dashboard", "/settings", "/repositories", "/repositories/{id}", "/reviews",
            "/reviews/{id}", "/reviews/{id}/compare"})
    public String forward() {
        return "forward:/index.html";
    }
}
