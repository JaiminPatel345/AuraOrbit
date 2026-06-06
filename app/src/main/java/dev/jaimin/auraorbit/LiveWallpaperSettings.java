package dev.jaimin.auraorbit;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.jaimin.auraorbit.ui.AppPickerFragment;
import dev.jaimin.auraorbit.ui.GroupListFragment;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LiveWallpaperSettings.java — Host Activity for AuraOrbit Settings UI
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Architecture:
 *   AppCompatActivity (this) → hosts → MainSettingsFragment (PreferenceFragmentCompat)
 *                                    → navigates → AppPickerFragment (Fragment)
 *                                    → navigates → GroupListFragment → GroupEditFragment
 *
 * All fragment navigation uses {@code R.id.settings_container} as the container and
 * {@code addToBackStack} so the system back button and the action-bar up arrow
 * both pop correctly.
 *
 * {@link DynamicColors} is applied inside {@link #onCreate} (before fragment work)
 * so Material You wallpaper colours overlay the theme on Android 12+ devices.
 *
 * The class name and package must not change — {@code res/xml/wallpaper.xml}
 * points the wallpaper picker's Settings button directly at this component.
 */
public class LiveWallpaperSettings extends AppCompatActivity {

    // ─────────────────────────────────────────────────────────────────────────
    //  Activity lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Apply Material You dynamic colour tokens (documented pattern: before setContentView,
        // after super.onCreate). This overlays the theme with wallpaper-derived colours on
        // Android 12+ devices.
        DynamicColors.applyToActivityIfAvailable(this);

        super.onCreate(savedInstanceState);

        // Inflate the activity layout that owns the MaterialToolbar + settings_container.
        // This replaces the implicit android.R.id.content-only approach so that
        // AppBarLayout.fitsSystemWindows handles the status-bar inset and
        // appbar_scrolling_view_behavior positions the container below the toolbar —
        // fixing the Android 15+ edge-to-edge enforcement issue.
        setContentView(R.layout.activity_settings);

        // Register the MaterialToolbar as the support action bar so that
        // getSupportActionBar(), setTitle(), setDisplayHomeAsUpEnabled(), etc.
        // all work as expected without a decor action bar.
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Apply bottom window inset to the container so list content is not
        // hidden behind the gesture navigation bar (or 3-button nav bar).
        View container = findViewById(R.id.settings_container);
        ViewCompat.setOnApplyWindowInsetsListener(container, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        // Only push the root fragment on a clean launch — the FragmentManager
        // already restores the back stack on config-change (rotation, etc.).
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new MainSettingsFragment())
                    .commit();
        }

        // Show or hide the action-bar up arrow whenever the back stack changes.
        // The arrow is shown as soon as any fragment is added to the back stack
        // (i.e., once the user navigates away from MainSettingsFragment).
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            boolean canGoBack = getSupportFragmentManager().getBackStackEntryCount() > 0;
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(canGoBack);
            }
        });
    }

    /**
     * Handles taps on the action-bar up arrow: pops the fragment back stack
     * instead of finishing the Activity, matching standard Android navigation.
     */
    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return true;
        }
        return super.onSupportNavigateUp();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main Settings Fragment
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Root preferences screen. Inflates {@code res/xml/preferences.xml} and
     * attaches custom click listeners for the three action preferences:
     * <ul>
     *   <li>{@code pref_select_apps}     → navigates to {@link AppPickerFragment}</li>
     *   <li>{@code pref_manage_groups}   → navigates to {@link GroupListFragment}</li>
     *   <li>{@code pref_background_image}→ launches the system photo picker or shows
     *                                      a replace/remove dialog if an image exists</li>
     * </ul>
     *
     * <p>Summaries are refreshed in {@link #onResume} so they always reflect the
     * latest SharedPreferences values even after returning from a sub-fragment.</p>
     */
    public static class MainSettingsFragment extends PreferenceFragmentCompat {

        // ─── Background-save executor ─────────────────────────────────────
        // A single-thread executor so concurrent saves (unlikely but defensive)
        // are serialised and never race each other on disk.
        private ExecutorService executor;

        // ─── Photo picker launcher ────────────────────────────────────────
        // MUST be registered as a field initialiser (i.e. before STARTED state)
        // because ActivityResultContracts requires registration before the
        // fragment reaches STARTED. Field initialiser order puts this before
        // onCreate, satisfying the contract.
        private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
                registerForActivityResult(
                        new ActivityResultContracts.PickVisualMedia(),
                        this::saveBackground   // uri may be null if user cancelled
                );

        // ─────────────────────────────────────────────────────────────────
        //  Fragment lifecycle
        // ─────────────────────────────────────────────────────────────────

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                        @Nullable String rootKey) {
            // Inflate the preference hierarchy. SeekBar, Switch, and List
            // preferences are wired automatically; we only need manual
            // click listeners for the three action preferences below.
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // ─── pref_select_apps → AppPickerFragment ────────────────────
            Preference selectApps = findPreference("pref_select_apps");
            if (selectApps != null) {
                selectApps.setOnPreferenceClickListener(pref -> {
                    navigateTo(new AppPickerFragment());
                    return true;
                });
            }

            // ─── pref_manage_groups → GroupListFragment ───────────────────
            Preference manageGroups = findPreference("pref_manage_groups");
            if (manageGroups != null) {
                manageGroups.setOnPreferenceClickListener(pref -> {
                    navigateTo(new GroupListFragment());
                    return true;
                });
            }

            // ─── pref_background_image → photo picker or replace/remove ──
            Preference bgPref = findPreference("pref_background_image");
            if (bgPref != null) {
                bgPref.setOnPreferenceClickListener(pref -> {
                    handleBackgroundClick();
                    return true;
                });
            }
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // Create the single-thread executor that runs BackgroundStore.saveFromUri
            // off the main thread.
            executor = Executors.newSingleThreadExecutor();
        }

        @Override
        public void onResume() {
            super.onResume();
            // Restore the root title in case a sub-fragment changed it.
            requireActivity().setTitle(R.string.settings_title);
            // Refresh all dynamic summaries.
            updateSummaries();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            // Prevent leaking the executor thread when the fragment is torn down.
            if (executor != null) {
                executor.shutdown();
                executor = null;
            }
        }

        // ─────────────────────────────────────────────────────────────────
        //  Navigation helper
        // ─────────────────────────────────────────────────────────────────

        /**
         * Pushes {@code fragment} onto the back stack using {@code R.id.settings_container}
         * as the container. All fragments in this Activity share this pattern so that
         * the up arrow and hardware back button both pop correctly.
         */
        private void navigateTo(@NonNull androidx.fragment.app.Fragment fragment) {
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, fragment)
                    .addToBackStack(null)
                    .commit();
        }

        // ─────────────────────────────────────────────────────────────────
        //  Background image handling
        // ─────────────────────────────────────────────────────────────────

        /**
         * Called when the user taps the background-image preference.
         *
         * <ul>
         *   <li>If no custom photo is set: launches the photo picker directly.</li>
         *   <li>If a custom photo is set: shows a three-choice dialog —
         *       "Choose new photo" (picker), "Remove photo" (revert to default
         *       gradient), and "Cancel" (dismiss, change nothing).</li>
         * </ul>
         */
        private void handleBackgroundClick() {
            if (BackgroundStore.exists(requireContext())) {
                // Custom photo already set — offer replace, remove, or cancel
                new MaterialAlertDialogBuilder(requireContext())
                        .setItems(new CharSequence[]{
                                getString(R.string.background_choose_new),
                                getString(R.string.background_remove),
                                getString(R.string.background_remove_cancel)
                        }, (dialog, which) -> {
                            if (which == 0) {
                                launchPicker();
                            } else if (which == 1) {
                                // Remove: clear stored file, engine reverts to default gradient
                                BackgroundStore.clear(requireContext());
                                updateSummaries();
                            }
                            // which == 2 is Cancel — dismiss dialog, change nothing
                        })
                        .show();
            } else {
                // No custom photo — go straight to the picker
                launchPicker();
            }
        }

        /**
         * Launches the system photo picker restricted to images only.
         */
        private void launchPicker() {
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        }

        /**
         * Saves the picked image URI to disk on a background thread.
         *
         * <p>The heavy I/O ({@link BackgroundStore#saveFromUri}) runs on
         * {@link #executor}; the result is delivered back to the main thread
         * where we guard with {@link #isAdded()} before touching any UI.</p>
         *
         * @param uri  URI returned by the photo picker; may be {@code null} if
         *             the user cancelled (no-op in that case).
         */
        private void saveBackground(@Nullable Uri uri) {
            if (uri == null) return; // user cancelled

            // Capture application context to avoid leaking the Activity
            // inside the background thread's lambda.
            android.content.Context appCtx =
                    requireContext().getApplicationContext();

            Handler mainHandler = new Handler(Looper.getMainLooper());

            executor.submit(() -> {
                boolean ok = BackgroundStore.saveFromUri(appCtx, uri);
                mainHandler.post(() -> {
                    // Guard: fragment may have been detached while we were saving.
                    if (!isAdded()) return;
                    Toast.makeText(
                            requireContext(),
                            ok ? R.string.toast_background_saved
                               : R.string.toast_background_failed,
                            Toast.LENGTH_SHORT
                    ).show();
                    updateSummaries();
                });
            });
        }

        // ─────────────────────────────────────────────────────────────────
        //  Summary helpers
        // ─────────────────────────────────────────────────────────────────

        /**
         * Refreshes all three dynamic preference summaries:
         * <ol>
         *   <li>Selected-app count</li>
         *   <li>Group count</li>
         *   <li>Background-image state</li>
         * </ol>
         *
         * Called from {@link #onResume} and after any mutation that changes
         * these values (background save/remove).
         */
        private void updateSummaries() {
            android.content.SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(requireContext());

            // ─── Apps summary ─────────────────────────────────────────────
            Preference selectApps = findPreference("pref_select_apps");
            if (selectApps != null) {
                int count = prefs.getStringSet(
                        AppFetcher.PREF_SELECTED_APPS, new HashSet<>()).size();
                selectApps.setSummary(count == 0
                        ? getString(R.string.summary_no_apps)
                        : getString(R.string.summary_apps_selected, count));
            }

            // ─── Groups summary ───────────────────────────────────────────
            Preference manageGroups = findPreference("pref_manage_groups");
            if (manageGroups != null) {
                List<GroupStore.Group> groups = GroupStore.load(prefs);
                int count = groups.size();
                manageGroups.setSummary(count == 0
                        ? getString(R.string.summary_no_groups)
                        : getString(R.string.summary_groups_count, count));
            }

            // ─── Background summary ───────────────────────────────────────
            Preference bgPref = findPreference("pref_background_image");
            if (bgPref != null) {
                bgPref.setSummary(BackgroundStore.exists(requireContext())
                        ? R.string.summary_background_set
                        : R.string.summary_background_not_set);
            }
        }
    }
}
