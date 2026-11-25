package za.co.innaesim.backup.platform;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;

/** Terminal prompts. End of input counts as "no answer", so nothing proceeds unattended. */
public final class Prompter {
    private static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in));

    private Prompter() {}

    /**
     * Asks for one line of input.
     *
     * @param prompt what to show
     * @return the trimmed line, or null at end of input
     */
    public static String line(String prompt) {
        System.out.print(prompt + " ");
        System.out.flush();
        try {
            String line = IN.readLine();
            return line == null ? null : line.strip();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Asks a yes/no question, where anything but an explicit yes means no.
     *
     * @param question what to ask
     * @return true only if the answer was yes
     */
    public static boolean confirm(String question) {
        String answer = line(question + " [y/N]:");
        return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
    }

    /**
     * Asks for a password, hiding the typing when there's a real terminal.
     *
     * @param prompt what to show
     * @return the password, or an empty string at end of input
     */
    public static String password(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword(prompt + " ");
            return password == null ? "" : new String(password);
        }
        String line = line(prompt);
        return line == null ? "" : line;
    }
}
