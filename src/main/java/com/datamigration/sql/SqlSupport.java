package com.datamigration.sql;

import com.datamigration.model.TableTask;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** SQL structure is administrator supplied. This is not a permissions sandbox. */
public final class SqlSupport {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private SqlSupport() {}

    public static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches())
            throw new IllegalArgumentException("Invalid SQL identifier");
        return value;
    }

    public static String table(String value) {
        if (value == null) throw new IllegalArgumentException("Missing target identifier");
        String[] parts = value.split("\\.", -1);
        if (parts.length < 1 || parts.length > 2) throw new IllegalArgumentException("Expected table or schema.table");
        for (String part : parts) identifier(part);
        return value;
    }

    public static String query(TableTask task) {
        try {
            var parsed = CCJSqlParserUtil.parseStatements(task.selectSql()).getStatements();
            if (parsed.size() != 1 || !(parsed.getFirst() instanceof Select))
                throw new IllegalArgumentException("Source SQL must be one SELECT");
            var tokens = tokens(task.selectSql());
            if (!tokens.getFirst().equals("SELECT") || tokens.contains("INTO") || tokens.contains("FOR")
                    || tokens.contains("?") || tokens.contains("NEXTVAL") || tokens.contains("NEXT")
                    || tokens.contains("INSERT") || tokens.contains("UPDATE") || tokens.contains("DELETE")
                    || tokens.contains("MERGE") || tokens.contains("CALL"))
                throw new IllegalArgumentException("Source SQL must be a non-locking SELECT without parameters or sequence advancement");
            var inserts = CCJSqlParserUtil.parseStatements(task.insertSql()).getStatements();
            if (inserts.size() != 1 || !(inserts.getFirst() instanceof Insert insert)
                    || !insert.getTable().getFullyQualifiedName().equalsIgnoreCase(table(task.targetTable())))
                throw new IllegalArgumentException("INSERT must address target-table");
            var writeTokens = tokens(task.insertSql());
            if (!writeTokens.contains("VALUES") || writeTokens.contains("SELECT")
                    || writeTokens.contains("RETURNING") || writeTokens.contains("CONFLICT")
                    || writeTokens.contains("UPDATE"))
                throw new IllegalArgumentException("Only parameterized single-row INSERT VALUES is supported");
            if (!(insert.getSelect() instanceof Values values)
                    || !(values.getExpressions() instanceof ParenthesedExpressionList<?>))
                throw new IllegalArgumentException("INSERT VALUES must contain exactly one row");
            if (writeTokens.stream().filter("?"::equals).count() != task.fields().size())
                throw new IllegalArgumentException("INSERT parameter count differs from fields");
            task.primaryKey().forEach(SqlSupport::identifier);
            return parsed.getFirst().toString();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            // Parser messages can include SQL literals: never propagate them.
            throw new IllegalArgumentException("Cannot parse migration SQL");
        }
    }

    /** Tokenizer for policy checks, ignoring quoted literals, identifiers and comments. */
    static List<String> tokens(String sql) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                for (i++; i < sql.length(); i++) {
                    if (sql.charAt(i) == quote) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i++;
                        else { i++; break; }
                    }
                }
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                result.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else {
                if (c == '?') result.add("?");
                i++;
            }
        }
        return result;
    }

    public static List<String> resolveKeys(ResultSetMetaData metadata, List<String> keys) throws SQLException {
        List<String> actual = new ArrayList<>();
        for (String key : keys) {
            int found = 0;
            String label = null;
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                if (key.equalsIgnoreCase(metadata.getColumnLabel(i))) {
                    found++;
                    label = metadata.getColumnLabel(i);
                    switch (JDBCType.valueOf(metadata.getColumnType(i))) {
                        case BLOB, CLOB, NCLOB, BINARY, VARBINARY, LONGVARBINARY, ARRAY, STRUCT, JAVA_OBJECT, OTHER ->
                            throw new IllegalArgumentException("Unsupported primary-key JDBC type");
                        default -> { }
                    }
                }
            }
            if (found != 1) throw new IllegalArgumentException("Missing or ambiguous primary-key column: " + key);
            actual.add("\"" + label.replace("\"", "\"\"") + "\"");
        }
        return actual;
    }

    public record Range(String sql, List<KeyCodec.Value> parameters) {
        public PreparedStatement prepare(Connection connection, int fetchSize) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                statement.setFetchSize(fetchSize);
                statement.setQueryTimeout(60);
                for (int i = 0; i < parameters.size(); i++) parameters.get(i).bind(statement, i + 1);
                return statement;
            } catch (SQLException exception) {
                statement.close();
                throw exception;
            }
        }
    }

    public static Range range(String select, List<String> keys,
                              List<KeyCodec.Value> lower, List<KeyCodec.Value> upper, boolean keysOnly) {
        List<KeyCodec.Value> parameters = new ArrayList<>();
        String projection = keysOnly ? String.join(", ", keys) : "*";
        StringBuilder sql = new StringBuilder("SELECT ").append(projection)
                .append(" FROM (").append(select).append(") migration_source");
        List<String> conditions = new ArrayList<>();
        if (lower != null) conditions.add(comparison(keys, lower, false, parameters));
        if (upper != null) conditions.add(comparison(keys, upper, true, parameters));
        if (!conditions.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conditions));
        if (!keys.isEmpty()) sql.append(" ORDER BY ").append(String.join(", ", keys));
        return new Range(sql.toString(), List.copyOf(parameters));
    }

    private static String comparison(List<String> keys, List<KeyCodec.Value> values, boolean upper,
                                     List<KeyCodec.Value> parameters) {
        if (keys.size() != values.size()) throw new IllegalArgumentException("Invalid checkpoint key length");
        List<String> alternatives = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            List<String> terms = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                terms.add(keys.get(j) + " = ?");
                parameters.add(values.get(j));
            }
            terms.add(keys.get(i) + (upper ? (i == keys.size() - 1 ? " <= ?" : " < ?") : " > ?"));
            parameters.add(values.get(i));
            alternatives.add("(" + String.join(" AND ", terms) + ")");
        }
        return "(" + String.join(" OR ", alternatives) + ")";
    }
}
