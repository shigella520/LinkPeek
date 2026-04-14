package io.github.shigella520.linkpeek.server.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;

@Controller
public class DashboardController {
    @GetMapping("/")
    @Hidden
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/dashboard"))
                .build();
    }

    @GetMapping("/dashboard")
    @Hidden
    public ResponseEntity<Resource> dashboard() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/dashboard/index.html"));
    }
}
