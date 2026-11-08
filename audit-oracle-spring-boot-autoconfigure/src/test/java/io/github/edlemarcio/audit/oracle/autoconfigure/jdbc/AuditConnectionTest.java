package io.github.edlemarcio.audit.oracle.autoconfigure.jdbc;

import io.github.edlemarcio.audit.oracle.core.AuditContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuditConnectionTest {

    private final Connection original = mock(Connection.class);
    private final CallableStatement configure = mock(CallableStatement.class);
    private final CallableStatement clear = mock(CallableStatement.class);
    @SuppressWarnings("unchecked")
    private final Consumer<Connection> discard = mock(Consumer.class);

    @BeforeEach
    void prepareConnection() throws SQLException {
        when(original.getAutoCommit()).thenReturn(true);
        when(original.prepareCall(AuditConnection.SET_CONTEXT)).thenReturn(configure);
        when(original.prepareCall(AuditConnection.CLEAR_CONTEXT)).thenReturn(clear);
    }

    @Test
    void devePreservarContextoAteCommitELimparAntesDeDevolverAoPool() throws SQLException {
        final Connection audited = open();
        audited.commit();
        verifyNoInteractions(clear);
        audited.close();

        final var order = inOrder(configure, original, clear);
        order.verify(configure).setString(1, "ana");
        order.verify(configure).setString(2, "2001:db8::1");
        order.verify(configure).setString(3, "condenacoes-interno");
        order.verify(configure).execute();
        order.verify(original).commit();
        order.verify(clear).execute();
        order.verify(original).close();
        verifyNoInteractions(discard);
    }

    @Test
    void deveLimparDepoisDoRollbackESerIdempotenteNoClose() throws SQLException {
        final Connection audited = open();
        audited.rollback();
        audited.close();
        audited.close();
        final var order = inOrder(original, clear);
        order.verify(original).rollback();
        order.verify(clear).execute();
        order.verify(original).close();
        assertThat(audited.isClosed()).isTrue();
    }

    @Test
    void deveDesfazerTransacaoPendenteAntesDaLimpezaNoClose() throws SQLException {
        final Connection audited = open();
        audited.setAutoCommit(false);
        when(original.getAutoCommit()).thenReturn(false);
        audited.createStatement();
        audited.close();

        final var order = inOrder(original, clear);
        order.verify(original).rollback();
        order.verify(original).setAutoCommit(true);
        order.verify(clear).execute();
        order.verify(original).close();
        verifyNoInteractions(discard);
    }

    @Test
    void deveDescartarSeRollbackPendenteFalharSemLimparContexto() throws SQLException {
        final Connection audited = open();
        when(original.getAutoCommit()).thenReturn(false);
        doThrow(new SQLException("rollback falhou")).when(original).rollback();
        assertThatThrownBy(audited::close).isInstanceOf(SQLException.class);
        verifyNoInteractions(clear);
        verify(discard).accept(original);
    }

    @Test
    void deveDescartarConexaoSeConfiguracaoFalhar() throws SQLException {
        final var failure = new SQLException("configuração falhou");
        when(configure.execute()).thenThrow(failure);
        assertThatThrownBy(this::open).isSameAs(failure);
        final var order = inOrder(discard, original);
        order.verify(discard).accept(original);
        order.verify(original).close();
    }

    @Test
    void deveDescartarAntesDoCloseSeLimpezaFalhar() throws SQLException {
        final Connection audited = open();
        final var failure = new SQLException("limpeza falhou");
        when(clear.execute()).thenThrow(failure);
        assertThatThrownBy(audited::close).isSameAs(failure);
        final var order = inOrder(discard, original);
        order.verify(discard).accept(original);
        order.verify(original).close();
        assertThat(audited.isClosed()).isTrue();
    }

    @Test
    void naoDeveReciclarConexaoQuandoDescarteEAbortFalharem() throws SQLException {
        final Connection audited = open();
        when(clear.execute()).thenThrow(new SQLException("limpeza"));
        doThrow(new IllegalStateException("descarte")).when(discard).accept(original);
        doThrow(new SQLException("abort")).when(original).abort(any());
        assertThatThrownBy(audited::close).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(2));
        verify(original, never()).close();
    }

    @Test
    void devePreservarExcecoesJdbcEInterfaceDaConexao() throws SQLException {
        final Connection audited = open();
        final var failure = new SQLException("comando de negócio");
        when(original.createStatement()).thenThrow(failure);
        assertThatThrownBy(audited::createStatement).isSameAs(failure);
        assertThat(audited.unwrap(Connection.class)).isSameAs(audited);
        assertThat(audited.isWrapperFor(Connection.class)).isTrue();
        assertThat(audited.equals(audited)).isTrue();
    }

    private Connection open() throws SQLException {
        return AuditConnection.configure(original, new AuditContext("ana", "2001:db8::1", "condenacoes-interno"), discard);
    }
}
