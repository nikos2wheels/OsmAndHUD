package net.osmand.plus.views.mapwidgets.configure.settings;

import static net.osmand.plus.views.mapwidgets.WidgetType.HUD_LANE_GUIDANCE;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import net.osmand.plus.R;
import net.osmand.plus.views.mapwidgets.WidgetType;
import net.osmand.plus.views.mapwidgets.widgets.HudLaneGuidanceWidget;

public class HudLaneGuidanceWidgetInfoFragment extends BaseResizableWidgetSettingFragment {

    private static final String SHOW_MINOR_TURNS = "hud_show_minor_turns";
    private static final String SHOW_EXIT_NUMBERS = "hud_show_exit_numbers";

    private boolean showMinorTurns;
    private boolean showExitNumbers;

    @NonNull
    @Override
    public WidgetType getWidget() {
        return HUD_LANE_GUIDANCE;
    }

    @Override
    protected void initParams(@NonNull Bundle bundle) {
        super.initParams(bundle);
        showMinorTurns = bundle.getBoolean(SHOW_MINOR_TURNS, settings.HUD_SHOW_MINOR_TURNS.getModeValue(appMode));
        showExitNumbers = bundle.getBoolean(SHOW_EXIT_NUMBERS, settings.HUD_SHOW_EXIT_NUMBERS.getModeValue(appMode));
    }

    @Override
    protected void setupMainContent(@NonNull ViewGroup container) {
        View mainContainer = view.findViewById(R.id.main_container);
        if (mainContainer != null) {
            mainContainer.setVisibility(View.VISIBLE);
        }

        View minorTurnsView = getLayoutInflater().inflate(R.layout.widget_preference_with_switch, container, false);
        container.addView(minorTurnsView);
        setupMinorTurnsPref(minorTurnsView);

        View exitNumbersView = getLayoutInflater().inflate(R.layout.widget_preference_with_switch, container, false);
        container.addView(exitNumbersView);
        setupExitNumbersPref(exitNumbersView);
    }

    private void setupMinorTurnsPref(@NonNull View view) {
        TextView title = view.findViewById(R.id.title);
        TextView description = view.findViewById(R.id.description);

        title.setText(R.string.show_minor_turns);
        description.setText(R.string.show_minor_turns_descr);

        CompoundButton compoundButton = view.findViewById(R.id.compound_button);
        compoundButton.setChecked(showMinorTurns);
        compoundButton.setOnCheckedChangeListener((buttonView, isChecked) -> showMinorTurns = isChecked);

        view.setOnClickListener(v -> compoundButton.setChecked(!compoundButton.isChecked()));
        view.setBackground(getPressedStateDrawable());
    }

    private void setupExitNumbersPref(@NonNull View view) {
        TextView title = view.findViewById(R.id.title);
        TextView description = view.findViewById(R.id.description);

        title.setText(R.string.hud_show_exit_numbers);
        description.setVisibility(View.GONE);

        CompoundButton compoundButton = view.findViewById(R.id.compound_button);
        compoundButton.setChecked(showExitNumbers);
        compoundButton.setOnCheckedChangeListener((buttonView, isChecked) -> showExitNumbers = isChecked);

        view.setOnClickListener(v -> compoundButton.setChecked(!compoundButton.isChecked()));
        view.setBackground(getPressedStateDrawable());
    }

    @Override
    protected void onWidgetSizeChanged() {
        if (widgetSizePref != null && selectedWidgetSize != null) {
            widgetSizePref.setModeValue(appMode, selectedWidgetSize);
            if (widgetInfo.widget instanceof HudLaneGuidanceWidget hudWidget) {
                hudWidget.recreateView();
            }
        }
    }

    @Override
    protected void applySettings() {
        if (widgetSizePref != null && selectedWidgetSize != null) {
            widgetSizePref.setModeValue(appMode, selectedWidgetSize);
        }
        super.applySettings();
        settings.HUD_SHOW_MINOR_TURNS.setModeValue(appMode, showMinorTurns);
        settings.HUD_SHOW_EXIT_NUMBERS.setModeValue(appMode, showExitNumbers);
        app.getRoutingHelper().onSettingsChanged(appMode);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SHOW_MINOR_TURNS, showMinorTurns);
        outState.putBoolean(SHOW_EXIT_NUMBERS, showExitNumbers);
    }
}
