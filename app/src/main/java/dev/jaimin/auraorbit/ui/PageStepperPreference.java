package dev.jaimin.auraorbit.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.button.MaterialButton;

import dev.jaimin.auraorbit.R;

/**
 * An inline stepper preference that renders decrement/increment buttons
 * directly in the preference row widget area — no dialog required.
 *
 * <p>The persisted value is an {@code int} in the range [1, 9] stored under
 * the preference's key (default: {@code pref_active_page}).  Each button tap
 * immediately persists the new value and fires the preference-change listener,
 * so the SphereEngine updates in real time without any "Save" step.</p>
 *
 * <p>View recycling: all listeners are set fresh in every
 * {@link #onBindViewHolder} call — never cached across recycles.</p>
 */
public class PageStepperPreference extends Preference {

    private static final int MIN_VALUE = 1;
    private static final int MAX_VALUE = 9;

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructors (all four required for XML inflation)
    // ─────────────────────────────────────────────────────────────────────────

    public PageStepperPreference(@NonNull Context context, AttributeSet attrs,
                                  int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public PageStepperPreference(@NonNull Context context, AttributeSet attrs,
                                  int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public PageStepperPreference(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PageStepperPreference(@NonNull Context context) {
        super(context);
        init();
    }

    private void init() {
        setWidgetLayoutResource(R.layout.preference_page_stepper);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View binding
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        // Disable row-level click so only the + / − buttons act.
        // Keeps accessibility focus on the row for TalkBack traversal.
        holder.itemView.setClickable(false);

        MaterialButton btnDecrement = (MaterialButton) holder.findViewById(R.id.stepper_decrement);
        TextView tvValue            = (TextView)       holder.findViewById(R.id.stepper_value);
        MaterialButton btnIncrement = (MaterialButton) holder.findViewById(R.id.stepper_increment);

        if (btnDecrement == null || tvValue == null || btnIncrement == null) return;

        // Read current persisted value (falls back to 1 if nothing stored yet).
        final int current = getPersistedInt(1);
        tvValue.setText(String.valueOf(current));

        // ── Decrement ──────────────────────────────────────────────────────
        btnDecrement.setOnClickListener(v -> {
            int oldVal = getPersistedInt(1);
            int newVal = Math.max(MIN_VALUE, oldVal - 1);
            if (newVal == oldVal) return; // already at minimum — nothing to do

            // callChangeListener first per Preference contract; abort if rejected.
            if (!callChangeListener(newVal)) return;

            persistInt(newVal);
            tvValue.setText(String.valueOf(newVal));
        });

        // ── Increment ──────────────────────────────────────────────────────
        btnIncrement.setOnClickListener(v -> {
            int oldVal = getPersistedInt(1);
            int newVal = Math.min(MAX_VALUE, oldVal + 1);
            if (newVal == oldVal) return; // already at maximum — nothing to do

            // callChangeListener first per Preference contract; abort if rejected.
            if (!callChangeListener(newVal)) return;

            persistInt(newVal);
            tvValue.setText(String.valueOf(newVal));
        });
    }
}
