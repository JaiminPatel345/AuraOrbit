package dev.jaimin.auraorbit.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.jaimin.auraorbit.AppFetcher;
import dev.jaimin.auraorbit.SphereWidgetProvider;
import dev.jaimin.auraorbit.R;

public class DashboardFragment extends Fragment {

    private SharedPreferences prefs;
    private TextView tvIconPackStatus;
    private dev.jaimin.auraorbit.IconPackManager iconPackManager;

    private void updateIconPackStatus() {
        if (tvIconPackStatus != null) {
            String current = prefs.getString(dev.jaimin.auraorbit.IconPackManager.PREF_ICON_PACK, null);
            if (current == null || current.isEmpty()) {
                tvIconPackStatus.setText("Default");
            } else {
                PackageManager pm = requireContext().getPackageManager();
                try {
                    String label = pm.getApplicationInfo(current, 0).loadLabel(pm).toString();
                    tvIconPackStatus.setText(label);
                } catch (PackageManager.NameNotFoundException e) {
                    tvIconPackStatus.setText("Unknown");
                }
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // App Version
        TextView tvAppVersion = view.findViewById(R.id.tv_app_version);
        if (tvAppVersion != null) {
            try {
                String versionName = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0).versionName;
                tvAppVersion.setText("v" + versionName);
            } catch (Exception e) {
                tvAppVersion.setText("v3.0.0");
            }
        }

        // Navigation Cards
        MaterialCardView cardPermanentSphere = view.findViewById(R.id.card_permanent_sphere);
        cardPermanentSphere.setOnClickListener(v -> navigateTo(new PermanentSphereFragment()));

        MaterialCardView cardWidgetSphere = view.findViewById(R.id.card_widget_sphere);
        cardWidgetSphere.setOnClickListener(v -> navigateTo(new WidgetListFragment()));

        // Icon Pack
        iconPackManager = dev.jaimin.auraorbit.IconPackManager.getInstance(requireContext());
        tvIconPackStatus = view.findViewById(R.id.tv_icon_pack_status);
        updateIconPackStatus();
        view.findViewById(R.id.btn_icon_pack).setOnClickListener(v -> showIconPackSelector());

        // FPS
        TextView tvFpsValue = view.findViewById(R.id.tv_fps_value);
        String fpsStr = prefs.getString("pref_target_fps", "120");
        tvFpsValue.setText(fpsStr + " FPS");

        view.findViewById(R.id.btn_fps).setOnClickListener(v -> {
            String[] options = {"30 FPS", "60 FPS", "90 FPS", "120 FPS"};
            String[] values = {"30", "60", "90", "120"};
            int checkedItem = 3;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(prefs.getString("pref_target_fps", "120"))) {
                    checkedItem = i;
                    break;
                }
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Target FPS")
                    .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                        prefs.edit().putString("pref_target_fps", values[which]).apply();
                        tvFpsValue.setText(options[which]);
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // GitHub link
        view.findViewById(R.id.btn_github).setOnClickListener(v -> {
            android.content.Intent browserIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/JaiminPatel345/AuraOrbit"));
            startActivity(browserIntent);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.settings_title);
    }

    private void navigateTo(@NonNull Fragment fragment) {
        getParentFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    private void showIconPackSelector() {
        java.util.List<dev.jaimin.auraorbit.IconPackManager.IconPackInfo> packs = dev.jaimin.auraorbit.IconPackManager.getAvailableIconPacks(requireContext());
        String[] options = new String[packs.size() + 1];
        String[] values = new String[packs.size() + 1];
        
        options[0] = "Default";
        values[0] = "";
        
        String current = prefs.getString(dev.jaimin.auraorbit.IconPackManager.PREF_ICON_PACK, "");
        int checkedItem = 0;
        
        for (int i = 0; i < packs.size(); i++) {
            options[i + 1] = packs.get(i).label;
            values[i + 1] = packs.get(i).packageName;
            if (values[i + 1].equals(current)) {
                checkedItem = i + 1;
            }
        }
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select Icon Pack")
                .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                    prefs.edit().putString(dev.jaimin.auraorbit.IconPackManager.PREF_ICON_PACK, values[which]).apply();
                    iconPackManager.loadIconPack(values[which]);
                    updateIconPackStatus();
                    
                    // Clear the icon cache so new icons are loaded
                    try {
                        java.lang.reflect.Field cacheField = AppFetcher.class.getDeclaredField("sIconCache");
                        cacheField.setAccessible(true);
                        java.util.Map cache = (java.util.Map) cacheField.get(null);
                        cache.clear();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    
                    // Update all widgets to reflect the new icon pack
                    SphereWidgetProvider.updateAllWidgets(requireContext());
                    
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
