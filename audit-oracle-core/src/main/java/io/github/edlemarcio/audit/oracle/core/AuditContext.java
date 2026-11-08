package io.github.edlemarcio.audit.oracle.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public record AuditContext(String user, String ip, String application) {

    public static final String UNAVAILABLE_IP = "0.0.0.0";

    public AuditContext {
        validateText(user, "Audit user", 64);
        validateText(application, "Audit application", 48);
        validateIp(ip);
    }

    private static void validateText(final String value, final String field, final int maxBytes) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " invalid or longer than " + maxBytes + " bytes");
        }
    }

    private static void validateIp(final String ip) {
        if (ip == null || ip.length() > 45) {
            throw new IllegalArgumentException("Invalid audit IP");
        }
        if (ip.contains(":")) {
            // Only parse IPv6 literals, without resolving DNS names.
            if (!ip.matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException("Invalid audit IP");
            }
            try {
                InetAddress.getByName(ip);
                return;
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid audit IP", e);
            }
        }
        if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            throw new IllegalArgumentException("Invalid audit IP");
        }
        for (final String part : ip.split("\\.")) {
            if (Integer.parseInt(part) > 255) {
                throw new IllegalArgumentException("Invalid audit IP");
            }
        }
    }
}
