package net.osmand.plus.views.mapwidgets.widgets;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.NextDirectionInfo;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.data.AnnounceTimeDistances;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;
import net.osmand.plus.settings.enums.WidgetSize;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.utils.OsmAndFormatterParams;
import net.osmand.plus.views.TurnPathHelper;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;
import net.osmand.plus.views.mapwidgets.LanesDrawable;
import net.osmand.plus.views.mapwidgets.MapWidgetInfo;
import net.osmand.plus.views.mapwidgets.WidgetType;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;
import net.osmand.plus.views.mapwidgets.widgetinterfaces.ISupportWidgetResizing;
import net.osmand.plus.views.mapwidgets.widgetstates.ResizableWidgetState;
import net.osmand.data.RotatedTileBox;
import net.osmand.Location;
import net.osmand.router.TurnType;

import java.util.Arrays;
import java.util.List;

public class HudLaneGuidanceWidget extends MapWidget implements ISupportWidgetResizing {

    private ImageView imageView;
    private View distanceContainer;
    private TextView distanceNumText;
    private TextView distanceUnitText;
    private View sideDistanceContainer;
    private TextView sideDistanceNumText;
    private TextView sideDistanceUnitText;
    private TextView exitText;
    private HudLanesDrawable lanesDrawable;
    private HudTurnDrawable turnDrawable;
    private AnnounceTimeDistances timeDistances;
    private final ResizableWidgetState widgetState;

    private float initialTouchY;
    private float initialY;
    private boolean isDragging = false;

    public HudLaneGuidanceWidget(@NonNull MapActivity mapActivity, @Nullable String customId, @Nullable WidgetsPanel panel) {
        super(mapActivity, WidgetType.HUD_LANE_GUIDANCE, customId, panel);
        widgetState = new ResizableWidgetState(mapActivity.getApp(), customId, WidgetType.HUD_LANE_GUIDANCE, WidgetSize.MEDIUM);
        updateDrawables();
    }

    private void updateDrawables() {
        WidgetSize size = getWidgetSizePref().get();
        float scale = switch (size) {
            case SMALL -> 0.7f;
            case LARGE -> 1.25f;
            default -> 1.0f;
        };

        float density = mapActivity.getMapView().getScaleCoefficient();
        float strokeWidth = 1.5f * density * scale;
        
        float baseLaneSize = mapActivity.getResources().getDimensionPixelSize(R.dimen.widget_turn_lane_size);
        float laneSize = baseLaneSize * scale;
        float minDelta = mapActivity.getResources().getDimensionPixelSize(R.dimen.widget_turn_lane_min_delta) * scale;
        float margin = mapActivity.getResources().getDimensionPixelSize(R.dimen.widget_turn_lane_margin) * scale;

        lanesDrawable = new HudLanesDrawable(mapActivity, strokeWidth, laneSize, minDelta, margin, laneSize);
        turnDrawable = new HudTurnDrawable(mapActivity, (int)laneSize, strokeWidth);
        
        if (imageView != null) {
            imageView.setImageDrawable(null);
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.hud_lane_guidance_widget;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void setupView(@NonNull View view) {
        super.setupView(view);
        imageView = view.findViewById(R.id.hud_guidance_image);
        distanceContainer = view.findViewById(R.id.hud_guidance_dist_container);
        distanceNumText = view.findViewById(R.id.hud_guidance_dist_num);
        distanceUnitText = view.findViewById(R.id.hud_guidance_dist_unit);

        sideDistanceContainer = view.findViewById(R.id.hud_guidance_dist_container_side);
        sideDistanceNumText = view.findViewById(R.id.hud_guidance_dist_num_side);
        sideDistanceUnitText = view.findViewById(R.id.hud_guidance_dist_unit_side);

        exitText = view.findViewById(R.id.hud_guidance_exit_text);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            float[] hdrMatrix = new float[] {
                100.0f, 0, 0, 0, 0,
                0, 100.0f, 0, 0, 0,
                0, 0, 100.0f, 0, 0,
                0, 0, 0, 1.0f, 0
            };
            android.graphics.ColorMatrixColorFilter filter = new android.graphics.ColorMatrixColorFilter(hdrMatrix);
            view.setRenderEffect(android.graphics.RenderEffect.createColorFilterEffect(filter));
        }

        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN -> {
                    initialTouchY = event.getRawY();
                    initialY = v.getY();
                    isDragging = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    float deltaY = event.getRawY() - initialTouchY;
                    if (Math.abs(deltaY) > 10 || isDragging) {
                        isDragging = true;
                        float newY = initialY + deltaY;
                        v.setY(newY);
                        View parent = (View) v.getParent();
                        if (parent != null) {
                            float screenHeight = parent.getHeight();
                            if (screenHeight > 0) {
                                getVerticalPositionPref().set(newY / screenHeight);
                            }
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        v.performClick();
                    }
                    return isDragging;
                }
            }
            return false;
        });

        updateWidgetSize();
        loadSavedPosition();
    }

    private void loadSavedPosition() {
        View v = getView();
        Float savedY = getVerticalPositionPref().get();
        v.post(() -> {
            View parent = (View) v.getParent();
            if (parent != null) {
                float screenHeight = parent.getHeight();
                if (screenHeight > 0) {
                    if (savedY != null && savedY >= 0) {
                        v.setY(savedY * screenHeight);
                    } else {
                        android.graphics.PointF mapRatio = mapActivity.getMapViewTrackingUtilities().getMapDisplayPositionManager().getMapRatio();
                        float displayPosY = mapRatio.y * screenHeight;
                        float ydpi = mapActivity.getResources().getDisplayMetrics().ydpi;
                        float mm40Px = (40.0f / 25.4f) * ydpi;
                        float defaultY = Math.max(0, displayPosY - mm40Px);
                        v.setY(defaultY);
                    }
                }
            }
        });
    }

    private CommonPreference<Float> getVerticalPositionPref() {
        boolean portrait = mapActivity.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
        return portrait ? settings.HUD_LANE_GUIDANCE_Y : settings.HUD_LANE_GUIDANCE_Y_LANDSCAPE;
    }

    private void updateWidgetSize() {
        if (imageView == null) return;
        WidgetSize size = getWidgetSizePref().get();
        float scale = switch (size) {
            case SMALL -> 0.7f;
            case LARGE -> 1.25f;
            default -> 1.0f;
        };
        if (distanceNumText != null) distanceNumText.setTextSize(22 * scale);
        if (distanceUnitText != null) distanceUnitText.setTextSize(14 * scale);
        if (sideDistanceNumText != null) sideDistanceNumText.setTextSize(22 * scale);
        if (sideDistanceUnitText != null) sideDistanceUnitText.setTextSize(14 * scale);
        if (exitText != null) exitText.setTextSize(18 * scale);
        updateDrawables();
        updateInfo(getView(), null);
    }

    @Override
    public void updateInfo(@NonNull View view, @Nullable DrawSettings drawSettings) {
        int imminent = -1;
        int[] lanes = null;
        int distance = 0;
        TurnType turnType = null;
        String exitNumber = null;
        NextDirectionInfo laneInfo = null;
        NextDirectionInfo turnInfo = null;

        boolean followingMode = routingHelper.isFollowingMode()
                || app.getLocationProvider().getLocationSimulation().isRouteAnimating();
        boolean calculated = routingHelper.isRouteCalculated();

        if (followingMode && calculated && !routingHelper.isDeviatedFromRoute()) {
            laneInfo = routingHelper.getNextRouteDirectionInfo(new NextDirectionInfo(), false);
            if (laneInfo != null && laneInfo.directionInfo != null) {
                TurnType ltt = laneInfo.directionInfo.getTurnType();
                if (timeDistances == null || timeDistances.getAppMode() != routingHelper.getAppMode()) {
                    timeDistances = new AnnounceTimeDistances(routingHelper.getAppMode(), getMyApplication());
                }
                boolean showMinor = settings.HUD_SHOW_MINOR_TURNS.getModeValue(routingHelper.getAppMode());
                if (ltt != null && (showMinor || !timeDistances.tooFarToDisplayLanes(ltt, laneInfo.distanceTo))) {
                    lanes = ltt.getLanes();
                    if (lanes != null && lanes.length > 0) {
                        imminent = laneInfo.imminent;
                        distance = laneInfo.distanceTo;
                        turnType = ltt;
                    }
                }
            }

            turnInfo = routingHelper.getNextRouteDirectionInfo(new NextDirectionInfo(), true);
            if (turnInfo != null && turnInfo.directionInfo != null) {
                if ((lanes == null || lanes.length == 0) && settings.HUD_SHOW_NEXT_TURN.getModeValue(routingHelper.getAppMode())) {
                    turnType = turnInfo.directionInfo.getTurnType();
                    distance = turnInfo.distanceTo;
                    imminent = turnInfo.imminent;
                }

                if (settings.HUD_SHOW_EXIT_NUMBERS.getModeValue(routingHelper.getAppMode())) {
                    boolean samePoint = laneInfo != null && Math.abs(laneInfo.distanceTo - turnInfo.distanceTo) < 10;
                    if (samePoint || lanes == null || lanes.length == 0) {
                        net.osmand.plus.routing.CurrentStreetName streetName = new net.osmand.plus.routing.CurrentStreetName(turnInfo, true);
                        if (!net.osmand.util.Algorithms.isEmpty(streetName.exitRef)) {
                            exitNumber = streetName.exitRef;
                        } else if (turnInfo.directionInfo != null && turnInfo.directionInfo.getTurnType() != null) {
                            int exit = turnInfo.directionInfo.getTurnType().getExitOut();
                            if (exit > 0) exitNumber = String.valueOf(exit);
                        }
                    }
                }
            }
        }

        boolean showLanes = lanes != null && lanes.length > 0;
        boolean showTurn = !showLanes && turnType != null;
        boolean visible = followingMode && calculated && (showLanes || showTurn);

        if (visible && showTurn) {
            RouteCalculationResult route = routingHelper.getRoute();
            if (route != null && turnInfo != null && turnInfo.directionInfo != null) {
                Location turnLoc = route.getLocationFromRouteDirection(turnInfo.directionInfo);
                Location carLoc = routingHelper.getLastProjection();
                if (carLoc == null) {
                    carLoc = app.getLocationProvider().getLastKnownLocation();
                }

                if (turnLoc != null && carLoc != null) {
                    RotatedTileBox tileBox = mapActivity.getMapView().getCurrentRotatedTileBox();
                    if (tileBox != null) {
                        // 1. Screen coordinates for Turn
                        float turnPixY = tileBox.getPixYFromLatLon(turnLoc.getLatitude(), turnLoc.getLongitude());
                        float turnPixX = tileBox.getPixXFromLatLon(turnLoc.getLatitude(), turnLoc.getLongitude());

                        // 2. Screen coordinates for Car
                        float carPixY = tileBox.getPixYFromLatLon(carLoc.getLatitude(), carLoc.getLongitude());
                        float carPixX = tileBox.getPixXFromLatLon(carLoc.getLatitude(), carLoc.getLongitude());

                        // 3. Screen coordinates for Widget Center-Top
                        float widgetY = 0;
                        float widgetX = tileBox.getPixWidth() / 2f;
                        Float savedYRatio = getVerticalPositionPref().get();
                        View root = mapActivity.findViewById(android.R.id.content);
                        if (savedYRatio != null && savedYRatio >= 0 && root != null) {
                            widgetY = savedYRatio * root.getHeight();
                        } else {
                            widgetY = view.getY();
                        }

                        // 4. Compare Total (Straight-Line) Screen Distances
                        double distCarToTurn = Math.sqrt(Math.pow(carPixX - turnPixX, 2) + Math.pow(carPixY - turnPixY, 2));
                        double distCarToWidget = Math.sqrt(Math.pow(carPixX - widgetX, 2) + Math.pow(carPixY - widgetY, 2));

                        if (distCarToTurn <= distCarToWidget) {
                            visible = false;
                        }
                    }
                }
            }
        }

        if (visible) {
            net.osmand.plus.utils.FormattedValue fv = OsmAndFormatter.getFormattedDistanceValue(distance, app, OsmAndFormatterParams.USE_LOWER_BOUNDS);
            boolean distNextToArrows = settings.HUD_LANE_DIST_NEXT_TO_ARROWS.get();

            if (distNextToArrows) {
                if (sideDistanceContainer != null) {
                    sideDistanceContainer.setVisibility(View.VISIBLE);
                    if (sideDistanceNumText != null) sideDistanceNumText.setText(fv.value);
                    if (sideDistanceUnitText != null) sideDistanceUnitText.setText(fv.unit);
                }
                if (distanceContainer != null) {
                    distanceContainer.setVisibility(View.GONE);
                }
            } else {
                if (distanceContainer != null) {
                    distanceContainer.setVisibility(View.VISIBLE);
                    if (distanceNumText != null) distanceNumText.setText(fv.value);
                    if (distanceUnitText != null) distanceUnitText.setText(fv.unit);
                }
                if (sideDistanceContainer != null) {
                    sideDistanceContainer.setVisibility(View.GONE);
                }
            }

            imageView.setImageDrawable(null);
            if (showLanes) {
                updateLanes(lanes, imminent);
                imageView.setImageDrawable(lanesDrawable);
            } else {
                updateTurn(turnType, imminent);
                imageView.setImageDrawable(turnDrawable);
            }

            if (exitNumber != null) {
                exitText.setVisibility(View.VISIBLE);
                exitText.setText(exitNumber);
            } else {
                exitText.setVisibility(View.GONE);
            }
        } else {
            imageView.setImageDrawable(null);
            if (distanceContainer != null) distanceContainer.setVisibility(View.GONE);
            if (sideDistanceContainer != null) sideDistanceContainer.setVisibility(View.GONE);
            if (exitText != null) exitText.setVisibility(View.GONE);
        }
        
        if (view.getLayoutParams() != null) {
            view.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
            view.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        view.requestLayout();
        imageView.requestLayout();
        imageView.invalidate();
        updateVisibility(visible);
    }

    private void updateLanes(int[] lanes, int imminent) {
        lanesDrawable.lanes = lanes;
        lanesDrawable.imminent = imminent == 0;
        lanesDrawable.updateBounds();
    }

    private void updateTurn(TurnType turnType, int imminent) {
        if (turnDrawable.getTurnType() != turnType || turnDrawable.getTurnImminent() != imminent) {
            turnDrawable.setTurnType(turnType);
            turnDrawable.setTurnImminent(imminent);
        }
    }

    @Override
    public boolean allowResize() {
        return true;
    }

    @NonNull
    @Override
    public OsmandPreference<WidgetSize> getWidgetSizePref() {
        return widgetState.getWidgetSizePref();
    }

    @Override
    protected void recreateViewInternal() {
        updateWidgetSize();
    }

    @Override
    public void onPanelAppearanceChanged(@NonNull net.osmand.plus.views.mapwidgets.appearance.ResolvedPanelAppearance appearance) {
        super.onPanelAppearanceChanged(appearance);
        updateWidgetSize();
        loadSavedPosition();
    }

    protected boolean shouldHide() {
        return net.osmand.plus.routepreparationmenu.MapRouteInfoMenu.chooseRoutesVisible ||
                net.osmand.plus.routepreparationmenu.MapRouteInfoMenu.followTrackVisible;
    }

    @Override
    public boolean updateVisibility(boolean visible) {
        boolean show = visible && !shouldHide();
        boolean updatedVisibility = super.updateVisibility(show);

        ViewGroup specialContainer = mapActivity.findViewById(R.id.hud_lane_guidance_container);
        if (specialContainer != null) {
            if (show) {
                View v = getView();
                if (v.getParent() != specialContainer) {
                    if (v.getParent() != null) {
                        ((ViewGroup) v.getParent()).removeView(v);
                    }
                    specialContainer.removeAllViews();
                    
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, 
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
                    v.setLayoutParams(lp);
                    
                    specialContainer.addView(v);
                } else if (v.getLayoutParams() instanceof FrameLayout.LayoutParams lp) {
                    if (lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
                        v.setLayoutParams(lp);
                    }
                }

                loadSavedPosition();
            } else {
                specialContainer.removeAllViews();
            }
        }

        return updatedVisibility;
    }

    @Override
    public void detachView(@NonNull WidgetsPanel widgetsPanel, @NonNull List<MapWidgetInfo> widgets, @NonNull ApplicationMode mode) {
        super.detachView(widgetsPanel, widgets, mode);
        ViewGroup specialContainer = mapActivity.findViewById(R.id.hud_lane_guidance_container);
        if (specialContainer != null) {
            specialContainer.removeView(getView());
        }
    }

    @Override
    public void attachView(@NonNull ViewGroup container, @NonNull WidgetsPanel panel, @NonNull List<MapWidget> followingWidgets) {
        if (mapActivity.findViewById(R.id.hud_lane_guidance_container) == null) {
            super.attachView(container, panel, followingWidgets);
        }
    }

    private static class HudLanesDrawable extends LanesDrawable {
        private final float strokeWidth;
        public HudLanesDrawable(@NonNull Context ctx, float strokeWidth, float size, float imgMinDeltaPx, float imgMarginPx, float laneSizePx) {
            super(ctx, strokeWidth, size, imgMinDeltaPx, imgMarginPx, laneSizePx);
            this.strokeWidth = strokeWidth;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                paintBlack.setColor(net.osmand.plus.views.layers.RouteLayer.createHdrWhiteColor(100.0f));
            } else {
                paintBlack.setColor(ContextCompat.getColor(ctx, R.color.HUD_nav_arrow_stroke_color));
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return (int) (super.getIntrinsicWidth() + strokeWidth * 2);
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) (size + strokeWidth * 2);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (lanes != null && lanes.length > 0) {
                canvas.save();
                canvas.translate(strokeWidth, strokeWidth);
                for (int i = 0; i < lanes.length; i++) {
                    if ((lanes[i] & 1) == 1) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            paintRouteDirection.setColor(net.osmand.plus.views.layers.RouteLayer.createHdrWhiteColor(100.0f));
                        } else {
                            paintRouteDirection.setColor(ContextCompat.getColor(ctx, R.color.HUD_nav_arrow));
                        }
                    } else {
                        paintRouteDirection.setColor(ContextCompat.getColor(ctx, R.color.HUD_nav_arrow_distant));
                    }
                    paintSecondTurn.setColor(ContextCompat.getColor(ctx, R.color.HUD_nav_arrow_distant));

                    int turnType = TurnType.getPrimaryTurn(lanes[i]);
                    int secondTurnType = TurnType.getSecondaryTurn(lanes[i]);
                    int thirdTurnType = TurnType.getTertiaryTurn(lanes[i]);

                    RectF imgBounds = new RectF();
                    Path thirdTurnPath = null;
                    Path secondTurnPath = null;
                    Path firstTurnPath = null;

                    if (thirdTurnType > 0) {
                        Path p = TurnPathHelper.getPathFromTurnType(turnType, secondTurnType, thirdTurnType,
                                TurnPathHelper.THIRD_TURN, size, leftSide, true);
                        if (p != null) {
                            RectF b = new RectF();
                            p.computeBounds(b, true);
                            if (!b.isEmpty()) {
                                imgBounds.set(b);
                                thirdTurnPath = p;
                            }
                        }
                    }
                    if (secondTurnType > 0) {
                        Path p = TurnPathHelper.getPathFromTurnType(turnType, secondTurnType, thirdTurnType,
                                TurnPathHelper.SECOND_TURN, size, leftSide, true);
                        if (p != null) {
                            RectF b = new RectF();
                            p.computeBounds(b, true);
                            if (!b.isEmpty()) {
                                if (imgBounds.isEmpty()) imgBounds.set(b);
                                else imgBounds.union(b);
                                secondTurnPath = p;
                            }
                        }
                    }
                    Path p = TurnPathHelper.getPathFromTurnType(turnType, secondTurnType, thirdTurnType,
                            TurnPathHelper.FIRST_TURN, size, leftSide, true);
                    if (p != null) {
                        RectF b = new RectF();
                        p.computeBounds(b, true);
                        if (!b.isEmpty()) {
                            if (imgBounds.isEmpty()) imgBounds.set(b);
                            else imgBounds.union(b);
                            firstTurnPath = p;
                        }
                    }

                    if (firstTurnPath != null || secondTurnPath != null || thirdTurnPath != null) {
                        if (i == 0) {
                            imgBounds.set(imgBounds.left - 2, imgBounds.top, imgBounds.right + 2, imgBounds.bottom);
                            canvas.translate(-imgBounds.left, 0);
                        } else {
                            canvas.translate(-laneHalfSize, 0);
                        }

                        if (thirdTurnPath != null) canvas.drawPath(thirdTurnPath, paintBlack);
                        if (secondTurnPath != null) canvas.drawPath(secondTurnPath, paintBlack);
                        if (firstTurnPath != null) canvas.drawPath(firstTurnPath, paintBlack);

                        if (thirdTurnPath != null) canvas.drawPath(thirdTurnPath, paintSecondTurn);
                        if (secondTurnPath != null) canvas.drawPath(secondTurnPath, paintSecondTurn);
                        if (firstTurnPath != null) canvas.drawPath(firstTurnPath, paintRouteDirection);

                        canvas.translate(laneHalfSize + delta, 0);
                    }
                }
                canvas.restore();
            }
        }
    }

    private static class HudTurnDrawable extends TurnPathHelper.RouteDrawable {
        private final Context ctx;
        private final int size;
        private final float strokeWidth;
        private TurnType turnType;
        private int imminent;

        public HudTurnDrawable(@NonNull MapActivity activity, int size, float strokeWidth) {
            super(activity, false);
            this.ctx = activity;
            this.size = size;
            this.strokeWidth = strokeWidth;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                paintRouteDirection.setColor(net.osmand.plus.views.layers.RouteLayer.createHdrWhiteColor(100.0f));
                paintRouteDirectionOutlay.setColor(net.osmand.plus.views.layers.RouteLayer.createHdrWhiteColor(100.0f));
            } else {
                paintRouteDirectionOutlay.setColor(ContextCompat.getColor(activity, R.color.HUD_nav_arrow_stroke_color));
            }
            paintRouteDirectionOutlay.setStrokeWidth(strokeWidth);
        }

        public void setTurnType(TurnType turnType) {
            this.turnType = turnType;
            setRouteType(turnType);
        }

        public void setTurnImminent(int turnImminent) {
            this.imminent = turnImminent;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                paintRouteDirection.setColor(net.osmand.plus.views.layers.RouteLayer.createHdrWhiteColor(100.0f));
            } else if (turnImminent == 0) {
                paintRouteDirection.setColor(ContextCompat.getColor(ctx, R.color.HUD_nav_arrow_imminent));
            } else {
                paintRouteDirection.setColor(ContextCompat.getColor(ctx, R.color.HUD_nav_arrow));
            }
            invalidateSelf();
        }

        public TurnType getTurnType() {
            return turnType;
        }

        public int getTurnImminent() {
            return imminent;
        }

        @Override
        public int getIntrinsicWidth() {
            return (int) (size + strokeWidth * 2);
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) (size + strokeWidth * 2);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.save();
            canvas.translate(strokeWidth, strokeWidth);
            super.draw(canvas);
            canvas.restore();
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(size / 72f, size / 72f);
            p.transform(m, dp);
            pOutlay.transform(m, dpOutlay);
        }
    }
}
