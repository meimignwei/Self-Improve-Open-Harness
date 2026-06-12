package com.openharness.auth;

/**
 * Abstract authentication flow.
 * Java equivalent of Python's auth/flows.py.
 */
public interface AuthFlow {

    /**
     * Execute the authentication flow, returning a credential or throwing on failure.
     */
    CredentialStorage.StoredCredential authenticate() throws AuthException;

    /**
     * Check if credentials exist and are valid.
     */
    boolean isAuthenticated();

    /**
     * Refresh the credential if it supports it.
     */
    CredentialStorage.StoredCredential refresh() throws AuthException;

    /**
     * Exception thrown by auth flows.
     */
    class AuthException extends Exception {
        public AuthException(String message) { super(message); }
        public AuthException(String message, Throwable cause) { super(message, cause); }
    }
}
