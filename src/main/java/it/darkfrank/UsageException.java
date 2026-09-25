package it.darkfrank;

/**
 * Errore dovuto a parametri, configurazione o stato locale forniti dall'utente:
 * viene mostrato solo il messaggio, senza stack trace.
 */
public class UsageException extends RuntimeException {

    public UsageException(String message) {
        super(message);
    }

    public UsageException(String message, Throwable cause) {
        super(message, cause);
    }
}
