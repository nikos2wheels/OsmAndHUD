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

    private static final String SHOW_EXIT_NUMBERS = "hud_show_exit_numbers";
    private static final String SHOW_NEXT_TURN = "hud_show_next_turn";

    private boolean showExitNumbers;
    private boolean showNextTurn;

    @NonNull
    @Override
    public WidgetType getWidget() {
        return HUD_LANE_GUIDANCE;
    }

    @Override
    protected void initParams(@NonNull Bundle bundle) {
        super.initParams(bundle);
        showExitNumbers = bundle.getBoolean(SHOW_EXIT_NUMBERS, settings.HUD_SHOW_EXIT_NUMBERS.getModeValue(appMode));
        showNextTurn = bundle.getBoolean(SHOW_NEXT_TURN, settings.HUD_SHOW_NEXT_TURN.getModeValue(appMode));
    }

    @Override
    protected void setupMainContent(@NonNull ViewGroup container) {
        // Ensure the settings section is visible in the base layout
        View mainContainer = view.findViewById(R.id.main_container);
        if (mainContainer != null) {
            mainContainer.setVisibility(View.VISIBLE);
        }

        View nextTurnView = getLayoutInflater().inflate(R.layout.widget_preference_with_switch, container, false);
        container.addView(nextTurnView);
        setupNextTurnPref(nextTurnView);

        // Inflate without attaching to root to ensure we get a fresh View instance
        View exitNumbersView = getLayoutInflater().inflate(R.layout.widget_preference_with_switch, container, false);
        container.addView(exitNumbersView);
        setupExitNumbersPref(exitNumbersView);
    }

    private void setupNextTurnPref(@NonNull View view) {
        TextView title = view.findViewById(R.id.title);
        TextView description = view.findViewById(R.id.description);

        title.setText(R.string.hud_show_next_turn);
        description.setVisibility(View.GONE);

        CompoundButton compoundButton = view.findViewById(R.id.compound_button);
        compoundButton.setChecked(showNextTurn);
        compoundButton.setOnCheckedChangeListener((buttonView, isChecked) -> showNextTurn = isChecked);

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
        settings.HUD_SHOW_EXIT_NUMBERS.setModeValue(appMode, showExitNumbers);
        settings.HUD_SHOW_NEXT_TURN.setModeValue(appMode, showNextTurn);
        app.getRoutingHelper().onSettingsChanged(appMode);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SHOW_EXIT_NUMBERS, showExitNumbers);
        outState.putBoolean(SHOW_NEXT_TURN, showNextTurn);
    }
}
