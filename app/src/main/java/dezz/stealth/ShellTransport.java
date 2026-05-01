package dezz.stealth;

/**
 * Abstraction for executing shell commands on the device.
 * Implementations: {@link AdbTransport}, {@link TelnetTransport}.
 */
public interface ShellTransport {
    /**
     * Human-readable description for UI, e.g. "telnet android.local:23" or "adb port 5555".
     */
    String describe();

    /**
     * Execute a shell command and return its output.
     * Can be called multiple times on the same transport instance.
     */
    String exec(String command) throws Exception;

    /**
     * Close the connection and release resources.
     */
    void close();
}
