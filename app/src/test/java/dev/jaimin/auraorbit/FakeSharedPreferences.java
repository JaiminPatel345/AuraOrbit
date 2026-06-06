package dev.jaimin.auraorbit;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * FakeSharedPreferences.java — In-Memory SharedPreferences for Unit Tests
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * A Map-backed implementation of {@link SharedPreferences} used exclusively in
 * host-JVM (Robolectric-free) unit tests. Avoids the Android framework stub that
 * throws {@link UnsupportedOperationException} on every call.
 *
 * ─── Contract ──────────────────────────────────────────────────────────────
 *
 * - {@link #edit()} returns a {@link FakeEditor} whose {@code commit()} and
 *   {@code apply()} both flush staged changes into the backing map immediately.
 * - {@code remove()} and {@code clear()} are honored inside the editor.
 * - Listener registration methods are no-ops (not needed for pure data tests).
 * - Unused primitive getters ({@code getFloat}, {@code getLong}) return their
 *   supplied defaults unchanged.
 */
public class FakeSharedPreferences implements SharedPreferences {

    /** Backing store: key → value (any type that SharedPreferences supports). */
    private final Map<String, Object> store = new HashMap<>();

    // ═══════════════════════════════════════════════════════════════════════
    //  Editor Implementation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * In-memory editor that stages changes in a separate map and flushes them
     * to the backing store on {@code commit()} / {@code apply()}.
     */
    public class FakeEditor implements SharedPreferences.Editor {

        /** Staged changes — merged into {@code store} on commit. */
        private final Map<String, Object> staged = new HashMap<>();

        /** Keys that should be removed from the store on commit. */
        private final java.util.Set<String> removals = new java.util.HashSet<>();

        /** Whether {@link #clear()} was called before any puts. */
        private boolean clearAll = false;

        @Override
        public Editor putString(String key, String value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            // Store a defensive copy so the caller can't mutate our data.
            staged.put(key, values != null ? new java.util.HashSet<>(values) : null);
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            staged.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            removals.add(key);
            staged.remove(key);
            return this;
        }

        @Override
        public Editor clear() {
            clearAll = true;
            staged.clear();
            removals.clear();
            return this;
        }

        /** Flushes all staged changes into the backing store. Always returns true. */
        @Override
        public boolean commit() {
            flush();
            return true;
        }

        /** Flushes all staged changes into the backing store (synchronously here). */
        @Override
        public void apply() {
            flush();
        }

        /** Merges staged changes into the parent store. */
        private void flush() {
            if (clearAll) {
                store.clear();
            } else {
                for (String key : removals) {
                    store.remove(key);
                }
            }
            store.putAll(staged);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SharedPreferences read methods
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public FakeEditor edit() {
        return new FakeEditor();
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(store);
    }

    @Override
    public String getString(String key, String defValue) {
        Object v = store.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        Object v = store.get(key);
        return v instanceof Set ? (Set<String>) v : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object v = store.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = store.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = store.get(key);
        return v instanceof Float ? (Float) v : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = store.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    @Override
    public boolean contains(String key) {
        return store.containsKey(key);
    }

    // ─── Listener registration — no-ops for unit tests ────────────────────

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        // No-op: listener callbacks are not needed for pure data-layer tests.
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        // No-op.
    }
}
