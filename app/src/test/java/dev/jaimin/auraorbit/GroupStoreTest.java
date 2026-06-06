package dev.jaimin.auraorbit;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GroupStoreTest {

    private static GroupStore.Group g(String name, String color, String... pkgs) {
        GroupStore.Group group = new GroupStore.Group(name, color);
        group.packages.addAll(new LinkedHashSet<>(List.of(pkgs)));
        return group;
    }

    @Test public void serializeParseRoundTrip() {
        List<GroupStore.Group> in = new ArrayList<>(List.of(
                g("Social", "#7F77DD", "com.whatsapp", "com.instagram.android"),
                g("Work", "#1D9E75", "com.slack")));
        List<GroupStore.Group> out = GroupStore.parse(GroupStore.serialize(in));
        assertEquals(2, out.size());
        assertEquals("Social", out.get(0).name);
        assertEquals("#7F77DD", out.get(0).color);
        assertEquals(Set.of("com.whatsapp", "com.instagram.android"), out.get(0).packages);
        assertEquals(Set.of("com.slack"), out.get(1).packages);
    }

    @Test public void parseGarbageReturnsEmpty() {
        assertTrue(GroupStore.parse(null).isEmpty());
        assertTrue(GroupStore.parse("").isEmpty());
        assertTrue(GroupStore.parse("not json").isEmpty());
        assertTrue(GroupStore.parse("{\"a\":1}").isEmpty());
    }

    @Test public void upsertCreatesGroup() {
        List<GroupStore.Group> groups = new ArrayList<>();
        assertTrue(GroupStore.upsert(groups, null, "Games", "#D85A30",
                Set.of("com.supercell.clashofclans")));
        assertEquals(1, groups.size());
        assertEquals("Games", groups.get(0).name);
    }

    @Test public void upsertRejectsDuplicateNameCaseInsensitive() {
        List<GroupStore.Group> groups = new ArrayList<>(List.of(g("Social", "#7F77DD")));
        assertFalse(GroupStore.upsert(groups, null, "sOcIaL", "#1D9E75", Set.of()));
        assertEquals(1, groups.size());
    }

    @Test public void upsertRenameKeepsIdentity() {
        List<GroupStore.Group> groups = new ArrayList<>(
                List.of(g("Social", "#7F77DD", "com.whatsapp")));
        assertTrue(GroupStore.upsert(groups, "Social", "Chat", "#D4537E",
                Set.of("com.whatsapp")));
        assertEquals(1, groups.size());
        assertEquals("Chat", groups.get(0).name);
        assertEquals("#D4537E", groups.get(0).color);
    }

    @Test public void upsertEnforcesSingleMembership() {
        List<GroupStore.Group> groups = new ArrayList<>(List.of(
                g("Social", "#7F77DD", "com.whatsapp", "com.telegram"),
                g("Work", "#1D9E75")));
        assertTrue(GroupStore.upsert(groups, "Work", "Work", "#1D9E75",
                Set.of("com.whatsapp")));
        Map<String, GroupStore.Group> map = GroupStore.packageToGroup(groups);
        assertEquals("Work", map.get("com.whatsapp").name);
        assertEquals("Social", map.get("com.telegram").name);
        assertEquals(Set.of("com.telegram"), GroupStore.find(groups, "Social").packages);
    }

    @Test public void deleteRemovesGroup() {
        List<GroupStore.Group> groups = new ArrayList<>(
                List.of(g("Social", "#7F77DD", "com.whatsapp")));
        assertTrue(GroupStore.delete(groups, "social"));   // case-insensitive
        assertTrue(groups.isEmpty());
        assertFalse(GroupStore.delete(groups, "Social"));  // already gone
    }

    @Test public void saveLoadThroughPrefs() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        List<GroupStore.Group> in = new ArrayList<>(List.of(g("Social", "#7F77DD", "a.b")));
        GroupStore.save(prefs, in);
        List<GroupStore.Group> out = GroupStore.load(prefs);
        assertEquals(1, out.size());
        assertEquals(Set.of("a.b"), out.get(0).packages);
    }

    @Test public void loadMigratesLegacySchemaOnce() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit()
                .putStringSet("groups_list", new HashSet<>(Set.of("Social")))
                .putString("group_Social_color", "#FF6B6B")
                .putStringSet("group_Social_apps", new HashSet<>(Set.of("com.whatsapp")))
                .commit();
        List<GroupStore.Group> out = GroupStore.load(prefs);
        assertEquals(1, out.size());
        assertEquals("Social", out.get(0).name);
        assertEquals("#FF6B6B", out.get(0).color);
        assertEquals(Set.of("com.whatsapp"), out.get(0).packages);
        // legacy keys gone, JSON written
        assertFalse(prefs.contains("groups_list"));
        assertFalse(prefs.contains("group_Social_color"));
        assertFalse(prefs.contains("group_Social_apps"));
        assertTrue(prefs.contains(GroupStore.PREF_GROUPS_JSON));
    }
}
