package ca.ubc.cs317.dict.net;

import ca.ubc.cs317.dict.exception.DictConnectionException;
import ca.ubc.cs317.dict.model.Database;
import ca.ubc.cs317.dict.model.Definition;
import ca.ubc.cs317.dict.model.MatchingStrategy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;

import static ca.ubc.cs317.dict.net.Status.readStatus;
import static ca.ubc.cs317.dict.util.DictStringParser.splitAtoms;

/**
 * Created by Jonatan on 2017-09-09.
 */
public class DictionaryConnection {

    private static final int DEFAULT_PORT = 2628;

    private Socket socket;
    private BufferedReader input;
    private PrintWriter output;

    private Map<String, Database> databaseMap = new LinkedHashMap<String, Database>();

    /** Establishes a new connection with a DICT server using an explicit host and port number, and handles initial
     * welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @param port Port number used by the DICT server
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host, int port) throws DictConnectionException {
        try {
            socket = new Socket(host, port);
            input = new BufferedReader(
                new InputStreamReader(socket.getInputStream())
            );
            output = new PrintWriter(socket.getOutputStream(), true);
            Status initial = readStatus(input);
            if (initial.getStatusCode() != 220) {
                throw new DictConnectionException();
            }
        } catch (Exception e) {
            throw new DictConnectionException(e.getMessage());
        }
    }

    /** Establishes a new connection with a DICT server using an explicit host, with the default DICT port number, and
     * handles initial welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host) throws DictConnectionException {
        this(host, DEFAULT_PORT);
    }

    /** Sends the final QUIT message and closes the connection with the server. This function ignores any exception that
     * may happen while sending the message, receiving its reply, or closing the connection.
     *
     */
    public synchronized void close() {
        output.println("QUIT");
        try {
            socket.close();
        } catch (IOException e) {
            // do nothing
        }
    }

    /** Requests and retrieves all definitions for a specific word.
     *
     * @param word The word whose definition is to be retrieved.
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 definitions in the first database that has a definition for the word should be used
     *                 (database '!').
     * @return A collection of Definition objects containing all definitions returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Definition> getDefinitions(String word, Database database) throws DictConnectionException {
        Collection<Definition> set = new ArrayList<>();
        getDatabaseList(); // Ensure the list of databases has been populated

        String message = "DEFINE " + database.getName() + " " + "\"" + word + "\"";
        output.println(message);
        Status initialStatus = readStatus(input);

        if (initialStatus.getStatusCode() != 150) throw new DictConnectionException();

        String[] firstResponse = initialStatus.getDetails().split(" ", 2);
        int numDefinitions = Integer.parseInt(firstResponse[0]);

        for (int i = 0; i < numDefinitions; i++) {
            Status currStatus = readStatus(input);
            if (currStatus.getStatusCode() != 151) throw new DictConnectionException();

            Definition df = new Definition(word, database);
            String def;
            try {
                def = input.readLine();
            } catch (IOException e) {
                throw new DictConnectionException();
            }
            while (!def.equals(".")) {
                try {
                    df.appendDefinition(def);
                    def = input.readLine();
                } catch (IOException e) {
                    throw new DictConnectionException();
                }
            }
            set.add(df);
        }

        return set;
    }

    /** Requests and retrieves a list of matches for a specific word pattern.
     *
     * @param word     The word whose definition is to be retrieved.
     * @param strategy The strategy to be used to retrieve the list of matches (e.g., prefix, exact).
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 matches in the first database that has a match for the word should be used (database '!').
     * @return A set of word matches returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<String> getMatchList(String word, MatchingStrategy strategy, Database database) throws DictConnectionException {
        Set<String> set = new LinkedHashSet<>();

        String message = "MATCH " + database.getName() + " " + strategy.getName() + " " + "\"" + word + "\"";
        output.println(message);

        Status initialStatus = readStatus(input);
        switch (initialStatus.getStatusCode()) {
            case 550:
                throw new DictConnectionException("Invalid database");
            case 551:
                throw new DictConnectionException("Invalid strategy");
            case 552:
                return set;
            case 152:
                String def = "";
                try {
                    def = input.readLine();
                } catch (IOException e) {
                    //
                }
                while (!def.equals(".")) {
                    try {
                        String[] resp = splitAtoms(def);
                        set.add(resp[1]);
                        def = input.readLine();
                    } catch (IOException e) {
                        //
                    }
                }
                Status finalResponse = readStatus(input);
                if (finalResponse.getStatusCode() != 250) throw new DictConnectionException();
                break;
            default:
                throw new DictConnectionException("INVALID RESPONSE");
        }

        return set;
    }

    /** Requests and retrieves a list of all valid databases used in the server. In addition to returning the list, this
     * method also updates the local databaseMap field, which contains a mapping from database name to Database object,
     * to be used by other methods (e.g., getDefinitionMap) to return a Database object based on the name.
     *
     * @return A collection of Database objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Database> getDatabaseList() throws DictConnectionException {

        if (!databaseMap.isEmpty()) return databaseMap.values();

        output.println("SHOW DB");
        Status response = readStatus(input);

        if (response.getStatusCode() != 110) throw new DictConnectionException();

        String next;
        try {
            next = input.readLine();
        } catch (IOException e) {
            throw new DictConnectionException();
        }

        while (!next.equals(".")) {
            try {
                String[] in = splitAtoms(next);
                Database db = new Database(in[0], in[1]);
                databaseMap.put(db.getName(), db);
                next = input.readLine();
            } catch (IOException e) {
                throw new DictConnectionException();
            }
        }

        Status finalResponse = readStatus(input);
        if (finalResponse.getStatusCode() != 250) throw new DictConnectionException();

        return databaseMap.values();
    }

    /** Requests and retrieves a list of all valid matching strategies supported by the server.
     *
     * @return A set of MatchingStrategy objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<MatchingStrategy> getStrategyList() throws DictConnectionException {
        Set<MatchingStrategy> set = new LinkedHashSet<>();

        output.println("SHOW STRAT");
        Status response = readStatus(input);
        if (response.getStatusCode() != 111) throw new DictConnectionException();

        String next;
        try {
            next = input.readLine();
        } catch (IOException e) {
            throw new DictConnectionException();
        }
        while (!next.equals(".")) {
            try {
                String[] comps = splitAtoms(next);
                set.add(new MatchingStrategy(comps[0], comps[1]));
                next = input.readLine();
            } catch (IOException e) {
                throw new DictConnectionException();
            }
        }

        Status finish = readStatus(input);
        if (finish.getStatusCode() != 250) throw new DictConnectionException();

        return set;
    }

}
