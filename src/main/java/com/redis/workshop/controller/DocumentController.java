package com.redis.workshop.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves static regulation PDFs bundled under classpath:docs/.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @GetMapping("/pdf/{name}")
    public ResponseEntity<Resource> servePdf(@PathVariable String name) {
        String safeName = name.replaceAll("[^a-zA-Z0-9]", "");
        Resource resource = new ClassPathResource("docs/" + safeName + ".pdf");
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + safeName + ".pdf")
                .body(resource);
    }
}
