// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The plain words a triage or RCA page shows for one tool call, from its server, tool and input: a done form for the
 * step list, a present form for the live line, and a "Could not" form for a call that failed. Words carry names the
 * input gave (a path, a pattern, a short sha), never tool output, and never a value shaped like a token. Pure.
 */
public final class StepWords {

    /** The MCP server of the platform's own tools. */
    public static final String PLATFORM_SERVER = "tessary-evals";

    /** {@code get_finding_evidence}'s page size when the call names none. */
    private static final long EVIDENCE_PAGE = 100;

    private static final int PATTERN_CHARS = 40;

    private static final int PATH_CHARS = 120;

    private static final int TRACE_CHARS = 16;

    private static final int SHA_CHARS = 7;

    private static final int ID_CHARS = 8;

    private static final int NOTE_CHARS = 160;

    /** The end of a whole sentence or line: a newline, or a stop the next character proves is not a decimal point. */
    private static final Pattern WHOLE = Pattern.compile("\n|[.!?](?=\\s)");

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+");

    private static final Pattern MARKDOWN_LEAD = Pattern.compile("^(?:#{1,6}\\s+|[-*+>]\\s*|\\d+[.)]\\s+)+");

    private static final Pattern MARKDOWN_MARKS = Pattern.compile("\\*\\*|__|`|\\*");

    private static final Pattern CLOSING = Pattern.compile("[.,;:]+$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Where the sandbox's work dir sits: E2B, then docker. */
    private static final List<String> WORK_DIRS = List.of("/home/user/", "/work/");

    private static final Pattern KEY_PREFIX = Pattern.compile("(?<![A-Za-z0-9])(?:tsy_|sk-|ghs_|ghp_)");

    /** A long run with a digit in it; a long snake_case name has none. */
    private static final Pattern LONG_RUN = Pattern.compile("[A-Za-z0-9_\\-]{32,}");

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{7,64}");

    private static final Set<String> SCRIPT_RUNNERS = Set.of("python", "python3", "node", "sh", "bash");

    private static final Set<String> FILE_READERS = Set.of("cat", "head", "tail", "less");

    private static final Set<String> TEST_RUNNERS = Set.of("pytest", "tox", "jest", "vitest");

    private static final Set<String> PACKAGE_TOOLS =
            Set.of("pip", "pip3", "npm", "pnpm", "yarn", "uv", "poetry", "go", "cargo", "mvn");

    private static final Pattern FILE_NAME = Pattern.compile("[\\w./-]+");

    private static final Set<String> INSTALLS = Set.of("install", "add", "sync");

    private static final Set<String> CUT_FLAGS = Set.of("-d", "-f", "-c", "-F", "-v");

    /** Flags that take the next word as their value, per command. */
    private static final Set<String> HEAD_FLAGS = Set.of("-n", "-c");

    private static final Set<String> GREP_FLAGS = Set.of("-A", "-B", "-C", "-m", "-g", "-t", "-f");

    private static final Set<String> PATTERN_FLAGS = Set.of("-e", "--regexp");

    /** The platform's reads of one object by its {@code id}, and what the object is called. */
    private static final Map<String, String> BY_ID = Map.of(
            "get_finding", "finding",
            "get_case", "case",
            "get_session", "session",
            "get_conversation", "conversation");

    private static final Set<String> SILENT = Set.of("todowrite", "todoread");

    /** Done, present and "Could not" forms of one phrase. */
    public record Words(String done, String doing, String could) {}

    /**
     * Per classifier, so a step can say "flagged answers" where the evidence holds answers.
     *
     * @param roleNouns evidence role to the noun its rows are called @param roleTotals evidence role to its row count
     */
    public record Context(Map<String, String> roleNouns, Map<String, Long> roleTotals) {
        public static final Context NONE = new Context(Map.of(), Map.of());
    }

    /** Evidence rows read so far per role in one run; a page counts only once its call ended well. */
    public static final class RowCursor {
        private final Map<String, Long> read = new HashMap<>();
        private final Map<String, Page> pending = new HashMap<>();

        private record Page(String role, long rows) {}

        private long read(String role) {
            return read.getOrDefault(role, 0L);
        }

        private void paging(String callId, String role, long rows) {
            pending.put(callId, new Page(role, rows));
        }

        public void ended(String callId, boolean ok) {
            Page page = pending.remove(callId);
            if (page != null && ok) read.merge(page.role(), page.rows(), Long::sum);
        }
    }

    private static final Map<String, String> DEFAULT_NOUNS = Map.of(
            "witness", "flagged rows",
            "member", "the flagged population",
            "exemplar", "examples",
            "changepoint", "change points");

    /** The platform's read-only tools, worded as the paid authoring chat words them. */
    private static final Map<String, Words> PLATFORM = Map.ofEntries(
            Map.entry("get_project", phrase("Read", "Reading", "read", "the project")),
            Map.entry("list_call_sites", phrase("Listed", "Listing", "list", "the call sites")),
            Map.entry("list_sessions", phrase("Listed", "Listing", "list", "recent sessions")),
            Map.entry("list_traces", phrase("Listed", "Listing", "list", "traces")),
            Map.entry("list_spans", phrase("Searched", "Searching", "search", "the steps of the traces")),
            Map.entry("list_findings", phrase("Listed", "Listing", "list", "the findings")),
            Map.entry("list_cases", phrase("Listed", "Listing", "list", "the cases")),
            Map.entry("get_finding", phrase("Read", "Reading", "read", "a finding")),
            Map.entry("get_case", phrase("Read", "Reading", "read", "a case")),
            Map.entry("get_session", phrase("Read", "Reading", "read", "a session")),
            Map.entry("get_conversation", phrase("Read", "Reading", "read", "a conversation")),
            Map.entry("describe_dataset", phrase("Read", "Reading", "read", "which fields the trace queries take")),
            Map.entry("query_count", phrase("Counted", "Counting", "count", "the matching traces")),
            Map.entry("query_timeseries", phrase("Counted", "Counting", "count", "the matching traces over time")),
            Map.entry("query_facets", phrase("Broke", "Breaking", "break", "the matching traces down by a field")),
            Map.entry("query_search", phrase("Searched", "Searching", "search", "the traces")));

    private static final Map<String, Words> DOSSIER = Map.of(
            "finding.md", phrase("Read", "Reading", "read", "the finding and its numbers"),
            "method.md", phrase("Read", "Reading", "read", "how the classifier measures"),
            "tools.md", phrase("Read", "Reading", "read", "the tool guide"));

    private StepWords() {}

    /** Null for a call the page shows no step for. */
    public static @Nullable Words of(SessionEvent.ToolStart start, Context ctx) {
        return of(start, ctx, new RowCursor());
    }

    /** Null for a call the page shows no step for. An evidence page is counted on {@code cursor}. */
    public static @Nullable Words of(SessionEvent.ToolStart start, Context ctx, RowCursor cursor) {
        String tool = start.tool();
        String server = start.server();
        JsonNode input = parse(start.inputSummary());
        if (server == null) {
            if (SILENT.contains(tool)) return null;
            return own(tool, input);
        }
        if (PLATFORM_SERVER.equals(server)) return platform(start.callId(), tool, input, ctx, cursor);
        return used(tool);
    }

    /**
     * The agent's newest whole sentence or line in plain text: no markdown, no token-shaped word, no closing period,
     * cut at a word to {@link #NOTE_CHARS}. Null while the text holds no whole sentence or line with words in it.
     */
    static @Nullable String note(String text) {
        Matcher whole = WHOLE.matcher(text);
        int end = 0;
        while (whole.find()) end = whole.end();
        String last = null;
        for (String line : text.substring(0, end).split("\n")) {
            for (String sentence : SENTENCE_BREAK.split(line)) {
                String plain = plain(sentence);
                if (plain != null) last = plain;
            }
        }
        return last;
    }

    /** True once a line starts an object or a code fence: the agent is writing its answer, not narrating. */
    static boolean answerShaped(String text) {
        return text.lines().map(String::strip).anyMatch(l -> l.startsWith("{") || l.startsWith("```"));
    }

    private static @Nullable String plain(String sentence) {
        String s = MARKDOWN_LEAD.matcher(sentence.strip()).replaceFirst("");
        s = unlinked(s);
        s = MARKDOWN_MARKS.matcher(s).replaceAll("");
        StringBuilder kept = new StringBuilder();
        for (String word : s.strip().split("\\s+")) {
            if (safe(word) == null) continue;
            if (!kept.isEmpty()) kept.append(' ');
            kept.append(word);
        }
        s = kept.toString();
        if (s.length() > NOTE_CHARS) {
            int cut = s.lastIndexOf(' ', NOTE_CHARS);
            s = s.substring(0, cut > 0 ? cut : NOTE_CHARS);
        }
        s = CLOSING.matcher(s).replaceFirst("");
        return s.chars().anyMatch(Character::isLetter) ? s : null;
    }

    public static String failed(Words w) {
        return "Could not " + w.could();
    }

    private static Words own(String tool, @Nullable JsonNode input) {
        return switch (tool) {
            case "read" -> read(text(input, "filePath"));
            case "write", "edit" -> {
                String path = shown(text(input, "filePath"));
                yield phrase("Wrote", "Writing", "write", path == null ? "a file" : path);
            }
            case "bash" -> bash(text(input, "command"));
            case "grep" -> grep(text(input, "pattern"), text(input, "path"));
            case "glob" -> {
                String pattern = safe(text(input, "pattern"));
                yield pattern == null
                        ? phrase("Listed", "Listing", "list", "the files")
                        : phrase("Listed", "Listing", "list", "the files matching " + pattern);
            }
            case "list" -> phrase("Listed", "Listing", "list", place(text(input, "path")));
            default -> used(tool);
        };
    }

    private static Words read(@Nullable String filePath) {
        String work = filePath == null ? null : workPath(filePath);
        if (work != null && work.startsWith("dossier/")) {
            Words known = DOSSIER.get(work.substring("dossier/".length()));
            if (known != null) return known;
        }
        String path = shown(filePath);
        return phrase("Read", "Reading", "read", path == null ? "a file" : path);
    }

    private static Words bash(@Nullable String command) {
        Words generic = phrase("Ran", "Running", "run", "a command");
        if (command == null) return generic;
        List<String> words = firstCommand(command);
        if (words.isEmpty()) return generic;
        String name = words.getFirst();
        List<String> args = words.subList(1, words.size());
        boolean counts = command.contains("uniq -c");
        if (FILE_READERS.contains(name)) {
            String path = lastOperand(args, HEAD_FLAGS, 0);
            return path == null ? generic : read(path);
        }
        if ("sed".equals(name) || "perl".equals(name)) {
            String path = lastOperand(args, Set.of("-e"), "sed".equals(name) ? 1 : 0);
            if (path == null) return generic;
            boolean inPlace = args.stream().anyMatch(a -> a.matches("-[a-z]*i.*"));
            return inPlace ? phrase("Edited", "Editing", "edit", place(path)) : read(path);
        }
        if ("jq".equals(name)) {
            String path = lastOperand(args, Set.of(), 1);
            return path == null ? generic : read(path);
        }
        if ("ls".equals(name) || "find".equals(name) || "tree".equals(name)) {
            List<String> operands = operands(args, Set.of());
            return phrase("Listed", "Listing", "list", place(operands.isEmpty() ? null : operands.getFirst()));
        }
        if ("grep".equals(name) || "rg".equals(name)) return searched(args);
        if ("wc".equals(name)) {
            String path = shown(lastOperand(args, Set.of(), 0));
            return path == null ? generic : phrase("Counted", "Counting", "count", "the lines in " + path);
        }
        if ("awk".equals(name) || "cut".equals(name)) {
            String path = shown(lastOperand(args, CUT_FLAGS, "awk".equals(name) ? 1 : 0));
            if (counts) return counted(path);
            return phrase("Pulled", "Pulling", "pull", "columns from " + (path == null ? "the output" : path));
        }
        if ("sort".equals(name) || "uniq".equals(name)) {
            String path = shown(lastOperand(args, Set.of("-k", "-t"), 0));
            return counts ? counted(path) : phrase("Sorted", "Sorting", "sort", path == null ? "the output" : path);
        }
        if (SCRIPT_RUNNERS.contains(name)) return script(name, args, generic);
        if ("git".equals(name)) return git(words, generic);
        if (TEST_RUNNERS.contains(name) || (PACKAGE_TOOLS.contains(name) && args.contains("test"))) {
            return phrase("Ran", "Running", "run", "the tests");
        }
        if (PACKAGE_TOOLS.contains(name) && args.stream().anyMatch(INSTALLS::contains)) {
            return phrase("Installed", "Installing", "install", "packages");
        }
        List<String> operands = operands(args, Set.of());
        String first = operands.isEmpty() ? null : shown(operands.getFirst());
        return switch (name) {
            case "diff", "cmp" -> {
                List<String> files = operands.stream()
                        .filter(o -> FILE_NAME.matcher(o).matches())
                        .toList();
                String one = files.size() == 2 ? shown(files.get(0)) : null;
                String other = files.size() == 2 ? shown(files.get(1)) : null;
                yield one == null || other == null
                        ? phrase("Compared", "Comparing", "compare", "two files")
                        : phrase("Compared", "Comparing", "compare", one + " and " + other);
            }
            case "curl", "wget" -> phrase("Fetched", "Fetching", "fetch", "a web address");
            case "mkdir" -> phrase("Made", "Making", "make", "a folder");
            case "touch" -> phrase("Made", "Making", "make", first == null ? "a file" : first);
            case "cp" -> phrase("Copied", "Copying", "copy", first == null ? "a file" : first);
            case "mv" -> phrase("Moved", "Moving", "move", first == null ? "a file" : first);
            case "rm" -> phrase("Removed", "Removing", "remove", first == null ? "a file" : first);
            case "echo", "printf" -> phrase("Printed", "Printing", "print", "a note");
            case "sqlite3", "psql", "duckdb", "mysql" -> phrase("Queried", "Querying", "query", "a database");
            case "for", "while" -> phrase("Ran", "Running", "run", "a loop");
            default -> generic;
        };
    }

    /** A markdown link {@code [text](url)} keeps only its text; a bracket that opens no link stays as written. */
    private static String unlinked(String s) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (int open = s.indexOf('['); open >= 0; open = s.indexOf('[', open + 1)) {
            int mid = s.indexOf(']', open);
            int close = mid < 0 || !s.startsWith("(", mid + 1) ? -1 : s.indexOf(')', mid + 2);
            if (close < 0) continue;
            out.append(s, at, open).append(s, open + 1, mid);
            at = close + 1;
            open = close;
        }
        return out.append(s.substring(at)).toString();
    }

    private static Words counted(@Nullable String path) {
        return phrase("Counted", "Counting", "count", path == null ? "the values" : "the values in " + path);
    }

    /** A script runner names the script it ran, the tests a {@code -m pytest} ran, or the kind of inline script. */
    private static Words script(String name, List<String> args, Words generic) {
        int module = args.indexOf("-m");
        if (module >= 0 && module + 1 < args.size() && TEST_RUNNERS.contains(args.get(module + 1))) {
            return phrase("Ran", "Running", "run", "the tests");
        }
        for (String word : args) {
            if (word.startsWith("-") || !looksLikePath(word)) continue;
            String path = shown(word);
            return path == null ? generic : phrase("Ran", "Running", "run", path);
        }
        String kind = name.startsWith("python") ? "Python" : "node".equals(name) ? "Node" : "shell";
        return phrase("Ran", "Running", "run", "a short " + kind + " script");
    }

    /**
     * The words of the first command, unquoted, past any {@code cd <dir>} that leads it: {@code cd <dir> && git ...} is
     * how an agent most often reaches the clone.
     */
    private static List<String> firstCommand(String command) {
        List<List<String>> commands = commands(command);
        int at = 0;
        while (at + 1 < commands.size() && "cd".equals(commands.get(at).getFirst())) at++;
        return commands.isEmpty() ? List.of() : commands.get(at);
    }

    /** Splits on unquoted whitespace and on unquoted {@code && || ; | newline}; drops empty commands. */
    private static List<List<String>> commands(String command) {
        List<List<String>> commands = new ArrayList<>();
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else word.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c) && c != '\n') {
                inWord = endWord(words, word, inWord);
            } else if (c == '&' || c == '|' || c == ';' || c == '\n') {
                inWord = endWord(words, word, inWord);
                if (!words.isEmpty()) commands.add(words);
                words = new ArrayList<>();
            } else {
                word.append(c);
                inWord = true;
            }
        }
        endWord(words, word, inWord);
        if (!words.isEmpty()) commands.add(words);
        return commands;
    }

    private static boolean endWord(List<String> words, StringBuilder word, boolean inWord) {
        if (inWord) words.add(word.toString());
        word.setLength(0);
        return false;
    }

    /** The words that are not flags, nor the value of a flag that takes one. */
    private static List<String> operands(List<String> args, Set<String> valueFlags) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (valueFlags.contains(a)) i++;
            else if (!a.startsWith("-")) out.add(a);
        }
        return out;
    }

    /** The last operand, once the first {@code skip} of them (a sed script, a jq filter) are passed. */
    private static @Nullable String lastOperand(List<String> args, Set<String> valueFlags, int skip) {
        List<String> operands = operands(args, valueFlags);
        return operands.size() > skip ? operands.getLast() : null;
    }

    /** grep and rg: the pattern is {@code -e}'s value or the first operand, and the path the operand after it. */
    private static Words searched(List<String> args) {
        String pattern = null;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (PATTERN_FLAGS.contains(a) && i + 1 < args.size()) pattern = args.get(++i);
            else if (GREP_FLAGS.contains(a)) i++;
            else if (!a.startsWith("-")) rest.add(a);
        }
        if (pattern == null && !rest.isEmpty()) pattern = rest.removeFirst();
        return grep(pattern, rest.isEmpty() ? null : rest.getFirst());
    }

    private static boolean looksLikePath(String word) {
        return word.contains("/") || word.matches(".*\\.(py|js|mjs|cjs|sh)$");
    }

    private static Words git(List<String> words, Words generic) {
        int at = 1;
        while (at + 1 < words.size() && "-C".equals(words.get(at))) at += 2;
        if (at >= words.size()) return generic;
        String sub = words.get(at);
        List<String> args = words.subList(at + 1, words.size()).stream()
                .filter(w -> !w.startsWith("-"))
                .toList();
        return switch (sub) {
            case "log" -> phrase("Read", "Reading", "read", "the commit history");
            case "status" -> phrase("Checked", "Checking", "check", "the repository's changes");
            case "blame" -> {
                String path = args.isEmpty() ? null : shown(args.getLast());
                yield phrase("Read", "Reading", "read", "the history of " + (path == null ? "a file" : path));
            }
            case "show" -> {
                String sha = args.isEmpty() ? null : sha(args.getFirst());
                yield sha == null
                        ? phrase("Read", "Reading", "read", "a commit")
                        : phrase("Read", "Reading", "read", "commit " + sha);
            }
            case "diff" -> {
                List<String> shas = args.stream()
                        .flatMap(a -> List.of(a.split("\\.{2,3}")).stream())
                        .map(StepWords::sha)
                        .toList();
                yield shas.size() == 2 && shas.get(0) != null && shas.get(1) != null
                        ? phrase("Compared", "Comparing", "compare", shas.get(0) + " and " + shas.get(1))
                        : phrase("Compared", "Comparing", "compare", "two versions");
            }
            default -> generic;
        };
    }

    private static @Nullable String sha(String word) {
        return SHA.matcher(word).matches() ? word.substring(0, SHA_CHARS) : null;
    }

    private static Words grep(@Nullable String pattern, @Nullable String path) {
        String scope = scope(path);
        String shown = safe(pattern);
        if (shown == null) return phrase("Searched", "Searching", "search", scope);
        if (shown.length() > PATTERN_CHARS) shown = shown.substring(0, PATTERN_CHARS);
        return phrase("Searched", "Searching", "search", scope + " for \"" + shown + "\"");
    }

    private static String scope(@Nullable String path) {
        String work = path == null ? "" : workPath(path);
        if (work.equals("repo") || work.startsWith("repo/")) return "the repository";
        if (work.equals("dossier") || work.startsWith("dossier/")) return "the dossier";
        return "the files";
    }

    private static String place(@Nullable String path) {
        String work = path == null ? "" : workPath(path);
        if (work.isEmpty() || work.equals(".")) return "the files";
        if (work.equals("repo")) return "the repository";
        if (work.equals("dossier")) return "the dossier";
        String shown = shown(path);
        return shown == null ? "the files" : shown;
    }

    private static Words platform(String callId, String tool, @Nullable JsonNode input, Context ctx, RowCursor cursor) {
        return switch (tool) {
            case "get_finding_evidence" -> evidence(callId, input, ctx, cursor);
            case "get_trace" -> {
                String trace = traceId(input);
                yield trace == null
                        ? phrase("Opened", "Opening", "open", "a trace")
                        : phrase("Opened", "Opening", "open", "trace " + trace);
            }
            case "get_span" -> {
                String trace = traceId(input);
                yield trace == null
                        ? phrase("Opened", "Opening", "open", "a span")
                        : phrase("Opened", "Opening", "open", "a span of trace " + trace);
            }
            default -> {
                String noun = BY_ID.get(tool);
                String id = noun == null ? null : shortId(text(input, "id"));
                yield id == null
                        ? PLATFORM.getOrDefault(tool, used(tool))
                        : phrase("Read", "Reading", "read", noun + " " + id);
            }
        };
    }

    private static @Nullable String shortId(@Nullable String id) {
        if (id == null) return null;
        return safe(id.length() > ID_CHARS ? id.substring(0, ID_CHARS) : id);
    }

    private static @Nullable String traceId(@Nullable JsonNode input) {
        String id = text(input, "trace_id");
        if (id == null) return null;
        return safe(id.length() > TRACE_CHARS ? id.substring(0, TRACE_CHARS) : id);
    }

    /** Rows a to b, where a follows the rows already read and b stops at the role's total. */
    private static Words evidence(String callId, @Nullable JsonNode input, Context ctx, RowCursor cursor) {
        Words generic = phrase("Read", "Reading", "read", "the evidence");
        if (input == null) return generic;
        if (input.path("count_only").asBoolean(false)) return phrase("Counted", "Counting", "count", "the evidence");
        String role = text(input, "role");
        if (role == null) return generic;
        if ("baseline".equals(role)) return phrase("Read", "Reading", "read", "the reference window");
        String noun = ctx.roleNouns().getOrDefault(role, DEFAULT_NOUNS.getOrDefault(role, "the evidence"));
        long limit =
                input.path("limit").canConvertToLong() && input.path("limit").asLong() > 0
                        ? input.path("limit").asLong()
                        : EVIDENCE_PAGE;
        Long total = ctx.roleTotals().get(role);
        long from = cursor.read(role) + 1;
        if (total != null && from > total) return phrase("Read", "Reading", "read", noun);
        long to = total == null ? from - 1 + limit : Math.min(from - 1 + limit, total);
        cursor.paging(callId, role, to - from + 1);
        String range = noun + " " + from + " to " + to;
        return new Words("Read " + range + (total == null ? "" : " of " + total), "Reading " + range, "read " + range);
    }

    private static Words used(String tool) {
        String name = safe(tool);
        return phrase("Used", "Using", "use", name == null ? "a tool" : name);
    }

    private static Words phrase(String done, String doing, String could, String rest) {
        return new Words(done + " " + rest, doing + " " + rest, could + " " + rest);
    }

    /** The path relative to the sandbox's work dir, as the agent may have written it either way. */
    private static String workPath(String path) {
        String p = path.strip();
        for (String dir : WORK_DIRS) {
            if (p.startsWith(dir)) return p.substring(dir.length());
            if (p.equals(dir.substring(0, dir.length() - 1))) return "";
        }
        return p.startsWith("./") ? p.substring(2) : p;
    }

    /** A path as a step shows it: under the work dir, without the clone's {@code repo/}, its tail when long. */
    private static @Nullable String shown(@Nullable String path) {
        if (path == null || path.isBlank()) return null;
        String p = workPath(path);
        if (p.startsWith("repo/")) p = p.substring("repo/".length());
        if (p.isEmpty()) return null;
        if (p.length() > PATH_CHARS) p = "..." + p.substring(p.length() - PATH_CHARS + 3);
        return safe(p);
    }

    private static @Nullable String safe(@Nullable String value) {
        if (value == null || value.isBlank() || KEY_PREFIX.matcher(value).find()) return null;
        Matcher run = LONG_RUN.matcher(value);
        while (run.find()) {
            if (run.group().chars().anyMatch(Character::isDigit)) return null;
        }
        return value;
    }

    private static @Nullable String text(@Nullable JsonNode input, String field) {
        if (input == null) return null;
        JsonNode v = input.path(field);
        return v.isTextual() ? v.textValue() : null;
    }

    /** Null for input the launcher cut or that is not an object. */
    private static @Nullable JsonNode parse(String inputSummary) {
        try {
            JsonNode node = MAPPER.readTree(inputSummary);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
