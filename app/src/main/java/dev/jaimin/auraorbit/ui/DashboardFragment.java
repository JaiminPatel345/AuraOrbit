package dev.jaimin.auraorbit.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
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

import dev.jaimin.auraorbit.R;
import dev.jaimin.auraorbit.IconPackManager;

public class DashboardFragment extends Fragment {

    private SharedPreferences prefs;
    private TextView tvIconPackStatus;

    private void updateIconPackStatus() {
        if (tvIconPackStatus != null) {
            String current = prefs.getString(IconPackManager.PREF_ICON_PACK, null);
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
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = requireContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        MaterialCardView cardPermanentSphere = view.findViewById(R.id.card_permanent_sphere);
        MaterialCardView cardWidgetsSphere = view.findViewById(R.id.card_widgets_sphere);

        // Navigation
        cardPermanentSphere.setOnClickListener(v -> {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new PermanentSphereFragment())
                    .addToBackStack(null)
                    .commit();
        });

        cardWidgetsSphere.setOnClickListener(v -> {
            getParentFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new WidgetListFragment())
                    .addToBackStack(null)
                    .commit();
        });

        // Icon Pack
        tvIconPackStatus = view.findViewById(R.id.tv_icon_pack_status);
        View btnIconPack = view.findViewById(R.id.btn_icon_pack);
        if (btnIconPack != null) {
            btnIconPack.setOnClickListener(v -> showIconPackSelector());
            updateIconPackStatus();
        }

        // FPS
        TextView tvFpsValue = view.findViewById(R.id.tv_fps_value);
        int currentFps = prefs.getInt("pref_target_fps", 60);
        if (tvFpsValue != null) tvFpsValue.setText(currentFps + " FPS");

        View btnFps = view.findViewById(R.id.btn_fps);
        if (btnFps != null) {
            btnFps.setOnClickListener(v -> {
                String[] options = {"30 FPS", "60 FPS", "90 FPS", "120 FPS"};
                int[] values = {30, 60, 90, 120};
                int current = prefs.getInt("pref_target_fps", 60);
                int checkedItem = 1;
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == current) {
                        checkedItem = i;
                        break;
                    }
                }

                new MaterialAlertDialogBuilder(ctx)
                        .setTitle("Select Target FPS")
                        .setSingleChoiceItems(options, checkedItem, (dialog, which) -> {
                            prefs.edit().putInt("pref_target_fps", values[which]).apply();
                            if (tvFpsValue != null) tvFpsValue.setText(values[which] + " FPS");
                            dialog.dismiss();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        // Footer links
        View btnGithub = view.findViewById(R.id.btn_github);
        if (btnGithub != null) {
            btnGithub.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/JaiminPatel345/AuraOrbit"));
                startActivity(intent);
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateIconPackStatus();
    }

    private void showIconPackSelector() {
        java.util.List<IconPackManager.IconPackInfo> packs = IconPackManager.getAvailableIconPacks(requireContext());
        String[] options = new String[packs.size() + 1];
        String[] values = new String[packs.size() + 1];
        
        options[0] = "Default";
        values[0] = "";
        
        String current = prefs.getString(IconPackManager.PREF_ICON_PACK, "");
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
                    prefs.edit().putString(IconPackManager.PREF_ICON_PACK, values[which]).apply();
                    updateIconPackStatus();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
