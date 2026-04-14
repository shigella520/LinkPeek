package io.github.shigella520.linkpeek.server.web;

import io.github.shigella520.linkpeek.core.error.MetadataNotFoundException;
import io.github.shigella520.linkpeek.server.render.HtmlPageRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class PreviewExceptionHandler {
    private final HtmlPageRenderer htmlPageRenderer;

    public PreviewExceptionHandler(HtmlPageRenderer htmlPageRenderer) {
        this.htmlPageRenderer = htmlPageRenderer;
    }

    @ExceptionHandler(MetadataNotFoundException.class)
    public ResponseEntity<String> handleMetadataNotFound(MetadataNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlPageRenderer.renderError("Preview Not Found", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlPageRenderer.renderError("Bad Request", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.TEXT_HTML)
                .body(htmlPageRenderer.renderError("Upstream Error", exception.getMessage()));
    }
}
