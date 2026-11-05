package io.github.edlemarcio.audit.oracle.example.customer;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/clientes")
public class ClienteController {

    private final ClienteService service;

    public ClienteController(ClienteService service) {
        this.service = service;
    }

    @GetMapping
    public String listar(Model model) {
        model.addAttribute("clientes", service.listar());
        model.addAttribute("cliente", new Cliente("", "", ""));
        return "clientes";
    }

    @PostMapping
    public String salvar(@ModelAttribute Cliente cliente, RedirectAttributes redirectAttributes) {
        service.salvar(cliente);
        redirectAttributes.addFlashAttribute("mensagem", "Cliente salvo com sucesso.");
        return "redirect:/clientes";
    }

    @PostMapping("/{id}/excluir")
    public String excluir(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        service.excluir(id);
        redirectAttributes.addFlashAttribute("mensagem", "Cliente excluído com sucesso.");
        return "redirect:/clientes";
    }

    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Long id, Model model) {
        Cliente cliente = service.buscar(id).orElseThrow();
        model.addAttribute("cliente", cliente);
        model.addAttribute("clientes", service.listar());
        return "clientes";
    }
}
