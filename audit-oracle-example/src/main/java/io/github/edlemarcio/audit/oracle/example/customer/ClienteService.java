package io.github.edlemarcio.audit.oracle.example.customer;

import io.github.edlemarcio.audit.oracle.autoconfigure.annotation.Audited;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ClienteService {

    private final ClienteRepository repository;

    public ClienteService(ClienteRepository repository) {
        this.repository = repository;
    }

    public List<Cliente> listar() {
        return repository.findAll();
    }

    public Optional<Cliente> buscar(Long id) {
        return repository.findById(id);
    }

    @Audited
    public Cliente salvar(Cliente cliente) {
        return repository.save(cliente);
    }

    @Audited
    public void excluir(Long id) {
        repository.deleteById(id);
    }
}
