package org.telegram.messenger.zenlygram.tasks.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Test-only {@link TasksSql}: the production SQL strings run on a real on-disk SQLite file through
 * sqlite_bridge.py (host stdlib SQLite, not Android SQLite). Mirrors the Android adapter's contract: query
 * arguments must be non-null Strings, no nested transactions, and the fence is re-checked before COMMIT.
 */
final class PySqlite implements TasksSql {
    private final Process process;
    private final Writer in;
    private final BufferedReader out;
    private boolean inTransaction;
    volatile boolean fenced;
    /** When &gt;= 0: the fence closes after this many more statements (logout landing mid-transaction). */
    int fenceAfter = -1;

    PySqlite(String bridge, String path) {
        try {
            process = new ProcessBuilder("python3", "-u", bridge, path).redirectErrorStream(false).start();
            in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("bridge", e);
        }
    }

    private JsonObject call(JsonObject request) {
        try {
            in.write(request.toString());
            in.write('\n');
            in.flush();
            String line = out.readLine();
            if (line == null) throw new IllegalStateException("bridge closed");
            JsonObject response = JsonParser.parseString(line).getAsJsonObject();
            if (!response.get("ok").getAsBoolean()) throw new IllegalStateException("sqlite: " + response.get("error").getAsString());
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("bridge", e);
        }
    }

    private static JsonElement encode(Object value) {
        JsonObject cell = new JsonObject();
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String) cell.addProperty("s", (String) value);
        else if (value instanceof Long || value instanceof Integer) cell.addProperty("i", value.toString());
        else if (value instanceof byte[]) cell.addProperty("b", Base64.getEncoder().encodeToString((byte[]) value));
        else throw new IllegalArgumentException("bind_type " + value.getClass());
        return cell;
    }

    private static Object decode(JsonElement cell) {
        if (cell.isJsonNull()) return null;
        JsonObject value = cell.getAsJsonObject();
        if (value.has("s")) return value.get("s").getAsString();
        if (value.has("i")) return Long.parseLong(value.get("i").getAsString());
        return Base64.getDecoder().decode(value.get("b").getAsString());
    }

    private JsonObject statement(String op, String sql, Object[] args) {
        if (fenced) throw new ClosedException();
        if (fenceAfter == 0) {
            fenceAfter = -1;
            fenced = true;
            throw new ClosedException();
        }
        if (fenceAfter > 0) fenceAfter--;
        JsonObject request = new JsonObject();
        request.addProperty("op", op);
        request.addProperty("sql", sql);
        JsonArray values = new JsonArray();
        for (Object arg : args) values.add(encode(arg));
        request.add("args", values);
        return call(request);
    }

    private void control(String op) {
        JsonObject request = new JsonObject();
        request.addProperty("op", op);
        call(request);
    }

    @Override public void exec(String sql, Object... args) {
        statement("exec", sql, args);
    }

    @Override public int update(String sql, Object... args) {
        return statement("update", sql, args).get("changes").getAsInt();
    }

    @Override public List<Object[]> query(String sql, String... args) {
        for (String arg : args) if (arg == null) throw new IllegalArgumentException("Android rawQuery cannot bind null");
        JsonArray rows = statement("query", sql, args).getAsJsonArray("rows");
        ArrayList<Object[]> result = new ArrayList<>();
        for (JsonElement row : rows) {
            JsonArray cells = row.getAsJsonArray();
            Object[] values = new Object[cells.size()];
            for (int i = 0; i < values.length; i++) values[i] = decode(cells.get(i));
            result.add(values);
        }
        return result;
    }

    @Override public <T> T transaction(Work<T> work) {
        if (fenced) throw new ClosedException();
        if (inTransaction) throw new IllegalStateException("nested transaction");
        control("begin");
        inTransaction = true;
        boolean committed = false;
        try {
            T result = work.run();
            if (fenced) throw new ClosedException();
            control("commit");
            committed = true;
            return result;
        } finally {
            inTransaction = false;
            if (!committed) control("rollback");
        }
    }

    /** Simulates process death: no COMMIT for an open transaction, the file stays. */
    void close() {
        try {
            if (inTransaction) control("rollback");
            control("close");
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
