package dev.jaimin.auraorbit.ui;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

import dev.jaimin.auraorbit.WidgetStore;
import dev.jaimin.auraorbit.R;
import dev.jaimin.auraorbit.SphereWidgetProvider;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * WidgetListFragment.java — Scrollable list of all configured widgets
 * ═══════════════════════════════════════════════════════════════════════════════
 */
public class WidgetListFragment extends Fragment {

    private static final String ARG_APPWIDGET_ID = "arg_app_widget_id";
    private int targetAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    private WidgetAdapter adapter;
    private View emptyView;
    private RecyclerView recyclerView;

    public static WidgetListFragment newInstance() {
        return new WidgetListFragment();
    }

    public static WidgetListFragment newInstance(int appWidgetId) {
        WidgetListFragment fragment = new WidgetListFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_APPWIDGET_ID, appWidgetId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            targetAppWidgetId = getArguments().getInt(ARG_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_widget_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        recyclerView = root.findViewById(R.id.widget_list);
        emptyView    = root.findViewById(R.id.empty_view);
        com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fab = root.findViewById(R.id.fab_add);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new WidgetAdapter();
        recyclerView.setAdapter(adapter);

        fab.setOnClickListener(v -> navigateToEdit(null));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (targetAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            requireActivity().setTitle("Select Widget");
        } else {
            requireActivity().setTitle(R.string.title_widgets);
        }
        reloadWidgets();
    }

    private void reloadWidgets() {
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());
        List<WidgetStore.Widget> widgets = WidgetStore.load(prefs);

        adapter.setWidgets(widgets);

        if (widgets.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }
    }

    private void navigateToEdit(@Nullable String widgetName) {
        getParentFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, WidgetEditFragment.newInstance(widgetName, targetAppWidgetId))
                .addToBackStack(null)
                .commit();
    }

    private void selectWidgetForPlacement(String widgetName) {
        if (targetAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        Context context = requireContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString("widget_group_" + targetAppWidgetId, widgetName).commit();

        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        new SphereWidgetProvider().onUpdate(context, appWidgetManager, new int[]{targetAppWidgetId});
        SphereWidgetProvider.updateAllWidgets(context);

        Intent resultValue = new Intent();
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, targetAppWidgetId);
        if (getActivity() != null) {
            getActivity().setResult(android.app.Activity.RESULT_OK, resultValue);
            getActivity().finish();
        }
    }

    private void confirmDelete(@NonNull String widgetName) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.dialog_delete_confirm, widgetName))
                .setMessage(R.string.dialog_delete_confirm_msg)
                .setPositiveButton(R.string.btn_delete_widget, (dialog, which) -> {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                    List<WidgetStore.Widget> freshWidgets = WidgetStore.load(prefs);
                    WidgetStore.delete(freshWidgets, widgetName);
                    WidgetStore.save(prefs, freshWidgets);
                    dev.jaimin.auraorbit.SphereWidgetProvider.updateAllWidgets(requireContext());

                    android.widget.Toast.makeText(requireContext(),
                             R.string.toast_widget_deleted,
                             android.widget.Toast.LENGTH_SHORT).show();

                    reloadWidgets();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private final class WidgetAdapter
            extends RecyclerView.Adapter<WidgetAdapter.VH> {

        private final List<WidgetStore.Widget> items = new ArrayList<>();

        void setWidgets(@NonNull List<WidgetStore.Widget> widgets) {
            items.clear();
            items.addAll(widgets);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_widget, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            WidgetStore.Widget widget = items.get(position);

            android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            boolean hideLogo = prefs.getBoolean("pref_widget_hide_logo_" + widget.name, false);
            boolean isTransparent = prefs.getBoolean("pref_widget_transparent_" + widget.name, false);
            boolean useThemeColor = prefs.getBoolean("pref_widget_use_theme_color_" + widget.name, true);

            if (hideLogo) {
                // Logo hidden — hide everything, show transparent frame
                holder.planetIcon.setVisibility(View.GONE);
                holder.ringIcon.setVisibility(View.GONE);
                holder.customLogo.setVisibility(View.GONE);
                holder.iconFrame.setBackground(null);
            } else if (dev.jaimin.auraorbit.WidgetLogoStore.exists(requireContext(), widget.name)) {
                // Custom logo — hide planet+ring, show logo
                holder.planetIcon.setVisibility(View.GONE);
                holder.ringIcon.setVisibility(View.GONE);
                holder.customLogo.setVisibility(View.VISIBLE);
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(
                        dev.jaimin.auraorbit.WidgetLogoStore.file(requireContext(), widget.name).getAbsolutePath());
                if (bitmap != null) {
                    holder.customLogo.setImageBitmap(bitmap);
                }
                if (isTransparent) {
                    holder.iconFrame.setBackground(null);
                } else {
                    holder.iconFrame.setBackgroundResource(R.drawable.rounded_bg_solid);
                }
            } else {
                // Default logo — show planet + color-tinted ring
                holder.planetIcon.setVisibility(View.VISIBLE);
                holder.ringIcon.setVisibility(View.VISIBLE);
                holder.customLogo.setVisibility(View.GONE);

                // Apply ring color (same logic as SphereWidgetProvider)
                try {
                    if (useThemeColor) {
                        holder.ringIcon.setColorFilter(requireContext().getColor(R.color.widget_theme_color));
                    } else {
                        holder.ringIcon.setColorFilter(Color.parseColor(widget.color));
                    }
                } catch (Exception e) {
                    holder.ringIcon.setColorFilter(Color.WHITE);
                }

                if (isTransparent) {
                    holder.iconFrame.setBackground(null);
                } else {
                    holder.iconFrame.setBackgroundResource(R.drawable.rounded_bg_solid);
                }
            }

            holder.name.setText(widget.name);
            holder.count.setText(getString(R.string.group_member_count,
                    widget.packages.size()));

            holder.itemView.setOnClickListener(v -> {
                if (targetAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(widget.name)
                            .setMessage("Place this widget on your home screen?")
                            .setPositiveButton("Select", (dialog, which) -> {
                                selectWidgetForPlacement(widget.name);
                            })
                            .setNeutralButton("Customize", (dialog, which) -> {
                                navigateToEdit(widget.name);
                            })
                            .setNegativeButton(R.string.btn_cancel, null)
                            .show();
                } else {
                    navigateToEdit(widget.name);
                }
            });
            holder.btnDelete.setOnClickListener(v -> confirmDelete(widget.name));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final View     colorDot;
            final View     iconFrame;
            final android.widget.ImageView planetIcon;
            final android.widget.ImageView ringIcon;
            final android.widget.ImageView customLogo;
            final TextView name;
            final TextView count;
            final android.widget.ImageView btnDelete;

            VH(@NonNull View itemView) {
                super(itemView);
                colorDot  = itemView.findViewById(R.id.widget_color_dot);
                iconFrame = itemView.findViewById(R.id.widget_icon_frame);
                planetIcon = itemView.findViewById(R.id.widget_icon_planet);
                ringIcon  = itemView.findViewById(R.id.widget_icon_ring);
                customLogo = itemView.findViewById(R.id.widget_custom_logo);
                name      = itemView.findViewById(R.id.widget_name);
                count     = itemView.findViewById(R.id.widget_count);
                btnDelete = itemView.findViewById(R.id.btn_delete_widget);
            }
        }
    }
}
