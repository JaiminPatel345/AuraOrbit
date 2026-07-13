import os
import re

replacements = {
    "GroupStore": "WidgetStore",
    "groupName": "widgetName",
    "Group": "Widget",
    "group": "widget",
    "Groups": "Widgets",
    "groups": "widgets",
    "GroupListFragment": "WidgetListFragment",
    "GroupEditFragment": "WidgetEditFragment",
    "fragment_group_list": "fragment_widget_list",
    "fragment_group_edit": "fragment_widget_edit",
    "row_group": "row_widget",
    "widgetName_id": "widgetName_id", # prevent over-replacing if necessary
    "app_group": "app_widget",
    "widget_group_": "widget_id_", # originally widget_group_, let's make it widget_id_ or just keep widget_widget_ (no, widget_widget_ is bad). let's use widget_id_
}

# The above dict is a bit too naive.
# Let's do exact phrase replacements to avoid breaking generic "group" (like ViewGroup, RadioGroup).
exact_replacements = [
    ("GroupStore", "WidgetStore"),
    ("GroupListFragment", "WidgetListFragment"),
    ("GroupEditFragment", "WidgetEditFragment"),
    ("fragment_group_list", "fragment_widget_list"),
    ("fragment_group_edit", "fragment_widget_edit"),
    ("row_group_member", "row_widget_member"),
    ("row_group", "row_widget"),
    ("widget_group_", "widget_id_"),
    ("pref_manage_groups", "pref_manage_widgets"),
    ("pref_create_group", "pref_create_widget"),
    ("Manage Groups", "Manage Widgets"),
    ("Create Group", "Create Widget"),
    ("Group Name", "Widget Name"),
    ("Edit Group", "Edit Widget"),
    ("Group Color", "Widget Color"),
    ("Apps in this group", "Apps in this widget"),
    ("Select apps for this group", "Select apps for this widget"),
    ("Group deleted", "Widget deleted"),
    ("No groups yet", "No widgets yet"),
    ("New Group", "New Widget"),
    ("App Grouping", "Widget Settings"),
    ("groupName", "widgetName"),
    ("packageToGroup", "packageToWidget"),
    ("groupId", "widgetId"),
    ("addGroup", "addWidget"),
    ("deleteGroup", "deleteWidget"),
    ("group_name_input", "widget_name_input"),
    ("group_color_input", "widget_color_input"),
    ("group_apps_list", "widget_apps_list"),
    ("group_card", "widget_card"),
    ("group_name", "widget_name"),
    ("group_apps_summary", "widget_apps_summary"),
    ("group_menu", "widget_menu"),
    ("action_delete_group", "action_delete_widget"),
    ("add_group", "add_widget"),
    ("groupLogo", "widgetLogo"),
]

def process_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()
    
    new_content = content
    for old, new in exact_replacements:
        new_content = new_content.replace(old, new)
        
    # some tricky ones
    new_content = re.sub(r'\bList<Group>\b', 'List<Widget>', new_content)
    new_content = re.sub(r'\bMap<String, Group>\b', 'Map<String, Widget>', new_content)
    new_content = re.sub(r'\bGroup\b', 'Widget', new_content) # Be careful, this might hit ViewGroup if not word bound! Wait, \bGroup\b doesn't match ViewGroup (it's ViewGroup). So \bGroup\b is safe!
    
    # Replace "group" with "widget" only when it's standalone or specific
    # We shouldn't globally replace "group" because of things like CameraGroupStrategy, etc.
    new_content = re.sub(r'\bgroup\b', 'widget', new_content)
    new_content = re.sub(r'\bgroups\b', 'widgets', new_content)
    new_content = re.sub(r'\bGroups\b', 'Widgets', new_content)

    if new_content != content:
        with open(filepath, 'w') as f:
            f.write(new_content)
        print(f"Updated {filepath}")

for root, _, files in os.walk('app/src/main'):
    for file in files:
        if file.endswith(('.java', '.xml', '.md')):
            process_file(os.path.join(root, file))
