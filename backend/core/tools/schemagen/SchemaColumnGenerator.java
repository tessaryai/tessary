// SPDX-License-Identifier: Apache-2.0
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build-time generator (run via {@code java SchemaColumnGenerator.java <changesDir> <outDir>}
 * at the {@code generate-sources} phase). Parses the Liquibase-formatted-SQL changelogs in
 * filename order and replays their DDL into an in-memory table model, then emits one Java
 * interface per table holding a {@code String} constant per live column. Repositories reference
 * those constants instead of bare column-name literals, so a renamed or dropped column becomes a
 * compile error rather than a runtime failure.
 *
 * <p>No database is started and no Liquibase runtime is used — this is pure text parsing of the
 * checked-in SQL. The changelog files are a protected, read-only input.
 *
 * <p>Scope: it understands the DDL shapes the project's migrations actually use —
 * {@code CREATE TABLE}, {@code DROP TABLE [IF EXISTS]}, {@code ALTER TABLE ... ADD COLUMN},
 * {@code ALTER TABLE ... DROP COLUMN}, {@code ALTER TABLE ... RENAME TO},
 * {@code ALTER TABLE ... RENAME COLUMN ... TO}. Index/constraint/UPDATE/ALTER-COLUMN statements
 * are intentionally ignored (they don't change the column-name set).
 */
public final class SchemaColumnGenerator {

    private static final String DEFAULT_PACKAGE = "ai.tessary.db.schema";

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 3) {
            throw new IllegalArgumentException("usage: SchemaColumnGenerator <changesDir> <outDir> [outPackage]");
        }
        Path changesDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        // Third argument is optional so backend/core/pom.xml's existing two-arg invocation is
        // byte-identical: the paid db module is the first caller to ever pass a third argument
        // (its own output package, ai.tessary.paid.db.schema), and every other caller keeps
        // emitting into the open ai.tessary.db.schema package it always has.
        String outPackage = args.length == 3 ? args[2] : DEFAULT_PACKAGE;

        Map<String, LinkedHashSet<String>> tables = parseAll(changesDir);
        Path pkgDir = outDir.resolve(outPackage.replace('.', '/'));
        Files.createDirectories(pkgDir);

        LinkedHashSet<String> generated = new LinkedHashSet<>();
        int rewritten = 0;
        for (Map.Entry<String, LinkedHashSet<String>> e : tables.entrySet()) {
            String iface = interfaceName(e.getKey());
            if (writeIfChanged(pkgDir.resolve(iface + ".java"), render(outPackage, e.getKey(), e.getValue()))) {
                rewritten++;
            }
            generated.add(iface);
        }
        int pruned = pruneOrphans(pkgDir, generated);
        System.out.println("SchemaColumnGenerator: " + generated.size() + " interfaces in " + pkgDir + " (" + rewritten
                + " rewritten, " + pruned + " pruned)");
    }

    /**
     * Delete generated interfaces for tables the changelog no longer has.
     *
     * <p>Without this the output directory only ever grows, and a table rename leaves BOTH names on
     * disk — {@code SignalColumns} beside {@code ClassifierColumns}. A repository still referencing
     * the dead one then compiles green on the incremental build that produced it and fails only on a
     * clean checkout, which is CI's machine and not the author's. Renames are exactly when this guard
     * carries weight, so it runs on every generation rather than on a clean build.
     *
     * <p>Deleting is safe because this directory is generated output and nothing else writes here:
     * every {@code *.java} in it either came from the loop above or is a leftover from a previous run.
     */
    private static int pruneOrphans(Path pkgDir, LinkedHashSet<String> generated) throws IOException {
        List<Path> orphans;
        try (var stream = Files.list(pkgDir)) {
            orphans = stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !generated.contains(name.substring(0, name.length() - ".java".length()));
                    })
                    .toList();
        }
        for (Path orphan : orphans) {
            Files.delete(orphan);
        }
        return orphans.size();
    }

    /**
     * Write only when the content actually differs, and report whether it did.
     *
     * <p>This runs at {@code generate-sources} on EVERY Maven invocation. Writing unconditionally
     * reset the mtime of all ~95 generated files each time, which invalidated the compiler
     * plugin's staleness check and forced a full recompile of them and everything downstream —
     * about 50 seconds added to every build, including each `task check`. Preserving the mtime of
     * unchanged files is what keeps incremental compilation working.
     */
    private static boolean writeIfChanged(Path target, String body) throws IOException {
        if (Files.exists(target) && body.equals(Files.readString(target, StandardCharsets.UTF_8))) {
            return false;
        }
        Files.writeString(target, body, StandardCharsets.UTF_8);
        return true;
    }

    // ------------------------------------------------------------------ parsing

    private static Map<String, LinkedHashSet<String>> parseAll(Path changesDir) throws IOException {
        // Filenames carry a monotonic NNN- prefix and sort lexicographically into changelog order
        // (the master changelog includes them in that same order), so a sorted directory walk
        // replays the migrations in their applied sequence.
        List<Path> files;
        try (var stream = Files.list(changesDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
        Map<String, LinkedHashSet<String>> tables = new LinkedHashMap<>();
        for (Path f : files) {
            applyFile(tables, Files.readString(f, StandardCharsets.UTF_8), f.getFileName().toString());
        }
        return tables;
    }

    private static void applyFile(Map<String, LinkedHashSet<String>> tables, String raw, String sourceFile) {
        for (String stmt : statements(raw)) {
            applyStatement(tables, stmt, sourceFile);
        }
    }

    /** Strip comments and rollback directives, then split on semicolons. */
    private static List<String> statements(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            // Drop the "--" line comment (Liquibase metadata, rollback directives, and trailing
            // column comments are all "--" prefixed), but only when the "--" is outside a single-
            // quoted string literal so CHECK (... IN ('a','b')) bodies survive intact.
            String code = stripLineComment(line);
            if (code.isBlank()) continue;
            sb.append(code).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String s : sb.toString().split(";", -1)) {
            String norm = s.replaceAll("\\s+", " ").strip();
            norm = normalizePgDump(norm);
            if (!norm.isEmpty()) out.add(norm);
        }
        return out;
    }

    /**
     * Fold the extra syntax a {@code pg_dump} baseline carries (vs the hand-written migrations) into the
     * bare shape the matchers expect: drop double-quoted-identifier quotes, the {@code public.} schema
     * qualifier, and the {@code ONLY} of {@code ALTER TABLE ONLY}. A no-op on the hand-written style
     * (which uses none of these), so both changelog dialects parse through the same patterns.
     */
    private static String normalizePgDump(String stmt) {
        String s = stmt.replace("\"", "");
        s = s.replaceAll("(?i)\\bpublic\\.", "");
        s = s.replaceAll("(?i)^ALTER TABLE ONLY ", "ALTER TABLE ");
        return s;
    }

    private static String stripLineComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '\'') inString = !inString;
            else if (!inString && c == '-' && line.charAt(i + 1) == '-') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)^CREATE TABLE (?:IF NOT EXISTS )?(\\w+)\\s*\\((.*)\\)$");
    private static final Pattern DROP_TABLE = Pattern.compile("(?i)^DROP TABLE (?:IF EXISTS )?(\\w+)$");
    private static final Pattern ADD_COLUMN =
            Pattern.compile("(?i)^ALTER TABLE (\\w+) ADD COLUMN (?:IF NOT EXISTS )?(\\w+)\\b.*$");
    private static final Pattern DROP_COLUMN =
            Pattern.compile("(?i)^ALTER TABLE (\\w+) DROP COLUMN (?:IF EXISTS )?(\\w+)\\b.*$");
    private static final Pattern RENAME_TABLE = Pattern.compile("(?i)^ALTER TABLE (\\w+) RENAME TO (\\w+)$");
    private static final Pattern RENAME_COLUMN =
            Pattern.compile("(?i)^ALTER TABLE (\\w+) RENAME COLUMN (\\w+) TO (\\w+)$");

    private static void applyStatement(Map<String, LinkedHashSet<String>> tables, String stmt, String sourceFile) {
        Matcher m;

        if ((m = CREATE_TABLE.matcher(stmt)).matches()) {
            String table = m.group(1).toLowerCase(Locale.ROOT);
            LinkedHashSet<String> cols = new LinkedHashSet<>();
            for (String col : parseColumnDefs(m.group(2))) cols.add(col);
            tables.put(table, cols); // CREATE replaces (handles 004's drop+recreate)
            return;
        }
        if ((m = DROP_TABLE.matcher(stmt)).matches()) {
            tables.remove(m.group(1).toLowerCase(Locale.ROOT));
            return;
        }
        if ((m = ADD_COLUMN.matcher(stmt)).matches()) {
            requireTable(tables, m.group(1), sourceFile).add(m.group(2).toLowerCase(Locale.ROOT));
            return;
        }
        if ((m = DROP_COLUMN.matcher(stmt)).matches()) {
            requireTable(tables, m.group(1), sourceFile).remove(m.group(2).toLowerCase(Locale.ROOT));
            return;
        }
        if ((m = RENAME_TABLE.matcher(stmt)).matches()) {
            String from = m.group(1).toLowerCase(Locale.ROOT);
            String to = m.group(2).toLowerCase(Locale.ROOT);
            LinkedHashSet<String> existing = tables.remove(from);
            tables.put(to, existing == null ? new LinkedHashSet<>() : existing);
            return;
        }
        if ((m = RENAME_COLUMN.matcher(stmt)).matches()) {
            LinkedHashSet<String> set = requireTable(tables, m.group(1), sourceFile);
            String from = m.group(2).toLowerCase(Locale.ROOT);
            String to = m.group(3).toLowerCase(Locale.ROOT);
            LinkedHashSet<String> rebuilt = new LinkedHashSet<>();
            for (String c : set) rebuilt.add(c.equals(from) ? to : c);
            set.clear();
            set.addAll(rebuilt);
            return;
        }
        // Statements that legitimately don't change the column-name set — CREATE/ALTER/DROP INDEX,
        // ADD/DROP/ALTER CONSTRAINT, ALTER COLUMN (type/default/nullability), and value-only DML
        // (UPDATE/INSERT/DELETE) — leave the model untouched and are deliberately ignored.
        if (IGNORABLE_DDL.matcher(stmt).find()) {
            return;
        }
        // A top-level CREATE TABLE / ALTER TABLE that matched none of the column-affecting patterns
        // above and isn't one of the known-ignorable shapes is unclassifiable: silently skipping it
        // would drift the generated constants from the real schema, defeating the whole point of
        // this generator. Fail the build loudly with the offending statement.
        if (TABLE_DDL.matcher(stmt).find()) {
            throw new IllegalStateException(
                    "unrecognized table DDL statement; cannot classify its effect on the column set: " + stmt);
        }
    }

    /** A CREATE TABLE / ALTER TABLE statement (the shapes whose column effect we must understand). */
    private static final Pattern TABLE_DDL = Pattern.compile("(?i)^(?:CREATE|ALTER) TABLE\\b");

    /**
     * Table-affecting DDL/DML that provably does not change the set of column names: constraint and
     * index operations, in-place column-attribute changes, and value-only DML. These are safe to
     * skip; any other CREATE/ALTER TABLE shape is treated as unclassifiable and fails the build.
     */
    private static final Pattern IGNORABLE_DDL = Pattern.compile(
            "(?i)^(?:"
                    // RENAME sits in this group and not with the RENAME COLUMN / RENAME TO handlers
                    // above: it renames a CONSTRAINT, so the column-name set is untouched. The literal
                    // CONSTRAINT token is what tells the three RENAME forms apart, and the other two are
                    // matched by their own patterns before this one is ever consulted.
                    + "ALTER TABLE \\w+ (?:ADD|DROP|ALTER|VALIDATE|RENAME) CONSTRAINT\\b"
                    + "|ALTER TABLE \\w+ ALTER COLUMN\\b"
                    + "|ALTER TABLE \\w+ (?:ADD|DROP)\\b.*\\b(?:PRIMARY KEY|FOREIGN KEY|UNIQUE|CHECK)\\b"
                    + "|(?:CREATE|DROP|ALTER) (?:UNIQUE )?INDEX\\b"
                    + ")");

    /**
     * The column set for an ADD/DROP/RENAME COLUMN target, or a loud failure when the table was
     * never {@code CREATE}d in this run — silently manufacturing a table entry here (the old
     * {@code computeIfAbsent} behaviour) would let an ALTER against a mistyped or already-partitioned
     * table name mint an interface for a relation that does not exist, and no one would notice until
     * a repository compiled against a phantom column constant. Table names ARE case-sensitive here in
     * the sense that they must already be lower-cased keys — {@link #applyStatement} always
     * lower-cases before calling this, so this is a straight map lookup, not a second normalization.
     */
    private static LinkedHashSet<String> requireTable(
            Map<String, LinkedHashSet<String>> tables, String table, String sourceFile) {
        String key = table.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> cols = tables.get(key);
        if (cols == null) {
            throw new IllegalStateException(
                    "ALTER TABLE references unknown table '" + key + "' (no prior CREATE TABLE seen for it) in "
                            + sourceFile);
        }
        return cols;
    }

    /**
     * Split a CREATE TABLE body on top-level commas (commas inside parens — e.g.
     * {@code CHECK (kind IN ('a','b'))} or {@code PRIMARY KEY (a, b)} — don't separate columns),
     * then take the leading identifier of each definition that isn't a table-level constraint.
     */
    private static List<String> parseColumnDefs(String body) {
        List<String> defs = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                defs.add(body.substring(start, i).strip());
                start = i + 1;
            }
        }
        defs.add(body.substring(start).strip());

        List<String> columns = new ArrayList<>();
        for (String def : defs) {
            if (def.isEmpty()) continue;
            String firstToken = def.split("\\s+", 2)[0];
            String upper = firstToken.toUpperCase(Locale.ROOT);
            // Table-level constraints, not columns.
            if (upper.equals("PRIMARY")
                    || upper.equals("FOREIGN")
                    || upper.equals("UNIQUE")
                    || upper.equals("CHECK")
                    || upper.equals("CONSTRAINT")) {
                continue;
            }
            columns.add(firstToken.toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    // ------------------------------------------------------------------ rendering

    private static String interfaceName(String table) {
        StringBuilder sb = new StringBuilder();
        for (String part : table.split("_")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb + "Columns";
    }

    private static String constName(String column) {
        return column.toUpperCase(Locale.ROOT);
    }

    private static String render(String outPackage, String table, LinkedHashSet<String> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(outPackage).append(";\n\n");
        sb.append("// GENERATED by tools/schemagen/SchemaColumnGenerator.java at the generate-sources phase.\n");
        sb.append("// Source of truth: the Liquibase changelogs under db/changelog/changes/. Do not edit.\n");
        sb.append("public interface ").append(interfaceName(table)).append(" {\n\n");
        sb.append("    String TABLE = \"").append(table).append("\";\n\n");
        for (String col : columns) {
            sb.append("    String ").append(constName(col)).append(" = \"").append(col).append("\";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private SchemaColumnGenerator() {}
}
