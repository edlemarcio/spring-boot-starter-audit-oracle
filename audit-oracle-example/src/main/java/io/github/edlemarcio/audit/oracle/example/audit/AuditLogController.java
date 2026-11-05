package io.github.edlemarcio.audit.oracle.example.audit;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuditLogController {

    private final AuditLogQueryRepository repository;

    public AuditLogController(AuditLogQueryRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/auditoria")
    public String consultar(Model model) {
        model.addAttribute("eventos", repository.findLatest());
        return "auditoria";
    }
}
