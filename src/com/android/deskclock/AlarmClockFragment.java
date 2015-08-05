/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.deskclock;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import android.transition.AutoTransition;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CursorAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.deskclock.alarms.AlarmStateManager;
import com.android.deskclock.alarms.TimePickerCompat;
import com.android.deskclock.alarms.TimePickerCompat.OnTimeSetListener;
import com.android.deskclock.events.Events;
import com.android.deskclock.provider.Alarm;
import com.android.deskclock.provider.AlarmInstance;
import com.android.deskclock.provider.DaysOfWeek;
import com.android.deskclock.widget.ActionableToastBar;
import com.android.deskclock.widget.EmptyViewController;
import com.android.deskclock.widget.TextTime;

import java.util.Calendar;
import java.util.HashSet;

/**
 * AlarmClock application.
 */
public final class AlarmClockFragment extends DeskClockFragment implements
        LoaderManager.LoaderCallbacks<Cursor>, View.OnTouchListener, OnTimeSetListener {
    private static final float EXPAND_DECELERATION = 1f;
    private static final float COLLAPSE_DECELERATION = 0.7f;

    private static final int ANIMATION_DURATION = 300;
    private static final int EXPAND_DURATION = 300;
    private static final int COLLAPSE_DURATION = 250;

    private static final int ROTATE_180_DEGREE = 180;

    private static final String KEY_EXPANDED_ID = "expandedId";
    private static final String KEY_RINGTONE_TITLE_CACHE = "ringtoneTitleCache";
    private static final String KEY_DELETED_ALARM = "deletedAlarm";
    private static final String KEY_UNDO_SHOWING = "undoShowing";
    private static final String KEY_PREVIOUS_DAY_MAP = "previousDayMap";
    private static final String KEY_SELECTED_ALARM = "selectedAlarm";

    private static final int REQUEST_CODE_RINGTONE = 1;
    private static final int REQUEST_CODE_PERMISSIONS = 2;
    private static final long INVALID_ID = -1;
    private static final String PREF_KEY_DEFAULT_ALARM_RINGTONE_URI = "default_alarm_ringtone_uri";

    // Use transitions only in API 21+
    private static final boolean USE_TRANSITION_FRAMEWORK = Utils.isLOrLater();

    // This extra is used when receiving an intent to create an alarm, but no alarm details
    // have been passed in, so the alarm page should start the process of creating a new alarm.
    public static final String ALARM_CREATE_NEW_INTENT_EXTRA = "deskclock.create.new";

    // This extra is used when receiving an intent to scroll to specific alarm. If alarm
    // can not be found, and toast message will pop up that the alarm has be deleted.
    public static final String SCROLL_TO_ALARM_INTENT_EXTRA = "deskclock.scroll.to.alarm";

    private ViewGroup mMainLayout;
    private ListView mAlarmsList;
    private AlarmItemAdapter mAdapter;
    private View mEmptyView;
    private View mFooterView;

    private Bundle mRingtoneTitleCache; // Key: ringtone uri, value: ringtone title
    private ActionableToastBar mUndoBar;
    private View mUndoFrame;

    protected Alarm mSelectedAlarm;
    protected long mScrollToAlarmId = INVALID_ID;

    private Loader mCursorLoader = null;

    // Saved states for undo
    private Alarm mDeletedAlarm;
    protected Alarm mAddedAlarm;
    private boolean mUndoShowing;

    private EmptyViewController mEmptyViewController;

    private Interpolator mExpandInterpolator;
    private Interpolator mCollapseInterpolator;

    private Transition mAddRemoveTransition;
    private Transition mRepeatTransition;

    @Override
    public void processTimeSet(int hourOfDay, int minute) {
        if (mSelectedAlarm == null) {
            // If mSelectedAlarm is null then we're creating a new alarm.
            Alarm a = new Alarm();
            a.alert = getDefaultRingtoneUri();
            if (a.alert == null) {
                a.alert = Uri.parse("content://settings/system/alarm_alert");
            }
            a.hour = hourOfDay;
            a.minutes = minute;
            a.enabled = true;

            mAddedAlarm = a;
            asyncAddAlarm(a);
        } else {
            mSelectedAlarm.hour = hourOfDay;
            mSelectedAlarm.minutes = minute;
            mSelectedAlarm.enabled = true;
            mScrollToAlarmId = mSelectedAlarm.id;
            asyncUpdateAlarm(mSelectedAlarm, true, false);
            mSelectedAlarm = null;
        }
    }

    public AlarmClockFragment() {
        // Basic provider required by Fragment.java
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        mCursorLoader = getLoaderManager().initLoader(0, null, this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedState) {
        // Inflate the layout for this fragment
        final View v = inflater.inflate(R.layout.alarm_clock, container, false);

        long expandedId = INVALID_ID;
        Bundle previousDayMap = null;
        if (savedState != null) {
            expandedId = savedState.getLong(KEY_EXPANDED_ID);
            mRingtoneTitleCache = savedState.getBundle(KEY_RINGTONE_TITLE_CACHE);
            mDeletedAlarm = savedState.getParcelable(KEY_DELETED_ALARM);
            mUndoShowing = savedState.getBoolean(KEY_UNDO_SHOWING);
            previousDayMap = savedState.getBundle(KEY_PREVIOUS_DAY_MAP);
            mSelectedAlarm = savedState.getParcelable(KEY_SELECTED_ALARM);
        }

        mExpandInterpolator = new DecelerateInterpolator(EXPAND_DECELERATION);
        mCollapseInterpolator = new DecelerateInterpolator(COLLAPSE_DECELERATION);

        if (USE_TRANSITION_FRAMEWORK) {
            mAddRemoveTransition = new AutoTransition();
            mAddRemoveTransition.setDuration(ANIMATION_DURATION);

            mRepeatTransition = new AutoTransition();
            mRepeatTransition.setDuration(ANIMATION_DURATION / 2);
            mRepeatTransition.setInterpolator(new AccelerateDecelerateInterpolator());
        }

        boolean isLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        View menuButton = v.findViewById(R.id.menu_button);
        if (menuButton != null) {
            if (isLandscape) {
                menuButton.setVisibility(View.GONE);
            } else {
                menuButton.setVisibility(View.VISIBLE);
                setupFakeOverflowMenuButton(menuButton);
            }
        }

        mEmptyView = v.findViewById(R.id.alarms_empty_view);

        mAlarmsList = (ListView) v.findViewById(R.id.alarms_list);

        mUndoBar = (ActionableToastBar) v.findViewById(R.id.undo_bar);
        mUndoFrame = v.findViewById(R.id.undo_frame);
        mUndoFrame.setOnTouchListener(this);

        mMainLayout = (ViewGroup) v.findViewById(R.id.main);
        mFooterView = v.findViewById(R.id.alarms_footer_view);
        mFooterView.setOnTouchListener(this);

        mAdapter = new AlarmItemAdapter(getActivity(), expandedId, previousDayMap, mAlarmsList);
        mEmptyViewController = new EmptyViewController(mMainLayout, mAlarmsList, mEmptyView);

        if (mRingtoneTitleCache == null) {
            mRingtoneTitleCache = new Bundle();
        }

        mAlarmsList.setAdapter(mAdapter);
        mAlarmsList.setVerticalScrollBarEnabled(true);
        mAlarmsList.setOnCreateContextMenuListener(this);

        if (mUndoShowing) {
            showUndoBar();
        }
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();

        final DeskClock activity = (DeskClock) getActivity();
        if (activity.getSelectedTab() == DeskClock.ALARM_TAB_INDEX) {
            setFabAppearance();
            setLeftRightButtonAppearance();
        }

        // Check if another app asked us to create a blank new alarm.
        final Intent intent = getActivity().getIntent();
        if (intent.hasExtra(ALARM_CREATE_NEW_INTENT_EXTRA)) {
            if (intent.getBooleanExtra(ALARM_CREATE_NEW_INTENT_EXTRA, false)) {
                // An external app asked us to create a blank alarm.
                startCreatingAlarm();
            }

            // Remove the CREATE_NEW extra now that we've processed it.
            intent.removeExtra(ALARM_CREATE_NEW_INTENT_EXTRA);
        } else if (intent.hasExtra(SCROLL_TO_ALARM_INTENT_EXTRA)) {
            long alarmId = intent.getLongExtra(SCROLL_TO_ALARM_INTENT_EXTRA, Alarm.INVALID_ID);
            if (alarmId != Alarm.INVALID_ID) {
                mScrollToAlarmId = alarmId;
                if (mCursorLoader != null && mCursorLoader.isStarted()) {
                    // We need to force a reload here to make sure we have the latest view
                    // of the data to scroll to.
                    mCursorLoader.forceLoad();
                }
            }

            // Remove the SCROLL_TO_ALARM extra now that we've processed it.
            intent.removeExtra(SCROLL_TO_ALARM_INTENT_EXTRA);
        }
    }

    private void hideUndoBar(boolean animate, MotionEvent event) {
        if (mUndoBar != null) {
            mUndoFrame.setVisibility(View.GONE);
            if (event != null && mUndoBar.isEventInToastBar(event)) {
                // Avoid touches inside the undo bar.
                return;
            }
            mUndoBar.hide(animate);
        }
        mDeletedAlarm = null;
        mUndoShowing = false;
    }

    private void showUndoBar() {
        final Alarm deletedAlarm = mDeletedAlarm;
        mUndoFrame.setVisibility(View.VISIBLE);
        mUndoBar.show(new ActionableToastBar.ActionClickedListener() {
            @Override
            public void onActionClicked() {
                mAddedAlarm = deletedAlarm;
                mDeletedAlarm = null;
                mUndoShowing = false;

                asyncAddAlarm(deletedAlarm);
            }
        }, 0, getResources().getString(R.string.alarm_deleted), true, R.string.alarm_undo, true);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(KEY_EXPANDED_ID, mAdapter.getExpandedId());
        outState.putBundle(KEY_RINGTONE_TITLE_CACHE, mRingtoneTitleCache);
        outState.putParcelable(KEY_DELETED_ALARM, mDeletedAlarm);
        outState.putBoolean(KEY_UNDO_SHOWING, mUndoShowing);
        outState.putBundle(KEY_PREVIOUS_DAY_MAP, mAdapter.getPreviousDaysOfWeekMap());
        outState.putParcelable(KEY_SELECTED_ALARM, mSelectedAlarm);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ToastMaster.cancelToast();
    }

    @Override
    public void onPause() {
        super.onPause();
        // When the user places the app in the background by pressing "home",
        // dismiss the toast bar. However, since there is no way to determine if
        // home was pressed, just dismiss any existing toast bar when restarting
        // the app.
        hideUndoBar(false, null);
    }

    private void showLabelDialog(final Alarm alarm) {
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        final Fragment prev = getFragmentManager().findFragmentByTag("label_dialog");
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);

        // Create and show the dialog.
        final LabelDialogFragment newFragment =
                LabelDialogFragment.newInstance(alarm, alarm.label, getTag());
        newFragment.show(ft, "label_dialog");
    }

    public void setLabel(Alarm alarm, String label) {
        alarm.label = label;
        asyncUpdateAlarm(alarm, false, true);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return Alarm.getAlarmsCursorLoader(getActivity());
    }

    @Override
    public void onLoadFinished(Loader<Cursor> cursorLoader, final Cursor data) {
        mAdapter.swapCursor(data);
        mEmptyViewController.setEmpty(data.getCount() == 0);
        if (mScrollToAlarmId != INVALID_ID) {
            scrollToAlarm(mScrollToAlarmId);
            mScrollToAlarmId = INVALID_ID;
        }
    }

    /**
     * Scroll to alarm with given alarm id.
     *
     * @param alarmId The alarm id to scroll to.
     */
    private void scrollToAlarm(long alarmId) {
        int alarmPosition = -1;
        for (int i = 0; i < mAdapter.getCount(); i++) {
            long id = mAdapter.getItemId(i);
            if (id == alarmId) {
                alarmPosition = i;
                break;
            }
        }

        if (alarmPosition >= 0) {
            mAdapter.setNewAlarm(alarmId);
            mAlarmsList.smoothScrollToPositionFromTop(alarmPosition, 0);
        } else {
            // Trying to display a deleted alarm should only happen from a missed notification for
            // an alarm that has been marked deleted after use.
            Context context = getActivity().getApplicationContext();
            Toast toast = Toast.makeText(context, R.string.missed_alarm_has_been_deleted,
                    Toast.LENGTH_LONG);
            ToastMaster.setToast(toast);
            toast.show();
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> cursorLoader) {
        mAdapter.swapCursor(null);
    }

    private void launchRingTonePicker(Alarm alarm) {
        mSelectedAlarm = alarm;
        Uri oldRingtone = Alarm.NO_RINGTONE_URI.equals(alarm.alert) ? null : alarm.alert;
        final Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, oldRingtone);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
        startActivityForResult(intent, REQUEST_CODE_RINGTONE);
    }

    private void saveRingtoneUri(Intent intent) {
        Uri uri = intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (uri == null) {
            uri = Alarm.NO_RINGTONE_URI;
        }
        mSelectedAlarm.alert = uri;

        // Save the last selected ringtone as the default for new alarms
        setDefaultRingtoneUri(uri);

        asyncUpdateAlarm(mSelectedAlarm, false, true);

        // If the user chose an external ringtone and has not yet granted the permission to read
        // external storage, ask them for that permission now.
        if (!Utils.hasPermissionToDisplayRingtoneTitle(getActivity(), uri)) {
            final String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE};
            requestPermissions(perms, REQUEST_CODE_PERMISSIONS);
        }
    }

    private Uri getDefaultRingtoneUri() {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity());
        final String ringtoneUriString = sp.getString(PREF_KEY_DEFAULT_ALARM_RINGTONE_URI, null);

        final Uri ringtoneUri;
        if (ringtoneUriString != null) {
            ringtoneUri = Uri.parse(ringtoneUriString);
        } else {
            ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(getActivity(),
                    RingtoneManager.TYPE_ALARM);
        }

        return ringtoneUri;
    }

    private void setDefaultRingtoneUri(Uri uri) {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity());
        if (uri == null) {
            sp.edit().remove(PREF_KEY_DEFAULT_ALARM_RINGTONE_URI).apply();
        } else {
            sp.edit().putString(PREF_KEY_DEFAULT_ALARM_RINGTONE_URI, uri.toString()).apply();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            switch (requestCode) {
                case REQUEST_CODE_RINGTONE:
                    saveRingtoneUri(data);
                    break;
                default:
                    LogUtils.w("Unhandled request code in onActivityResult: " + requestCode);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        // The permission change may alter the cached ringtone titles so clear them.
        // (e.g. READ_EXTERNAL_STORAGE is granted or revoked)
        mRingtoneTitleCache.clear();
    }

    private class AlarmItemAdapter extends CursorAdapter {
        private final Context mContext;
        private final LayoutInflater mFactory;
        private final Typeface mRobotoNormal;
        private final ListView mList;

        private long mExpandedId;
        private ItemHolder mExpandedItemHolder;
        private Bundle mPreviousDaysOfWeekMap = new Bundle();

        private final boolean mHasVibrator;
        private final int mCollapseExpandHeight;

        // Determines the order that days of the week are shown in the UI
        private int[] mDayOrder;

        // A reference used to create mDayOrder
        private final int[] DAY_ORDER = new int[] {
                Calendar.SUNDAY,
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY,
                Calendar.SATURDAY,
        };

        public class ItemHolder {

            // views for optimization
            LinearLayout alarmItem;
            TextTime clock;
            TextView tomorrowLabel;
            CompoundButton onoff;
            TextView daysOfWeek;
            TextView label;
            ImageButton delete;
            View expandArea;
            View summary;
            TextView clickableLabel;
            CheckBox repeat;
            LinearLayout repeatDays;
            CompoundButton[] dayButtons = new CompoundButton[7];
            CheckBox vibrate;
            TextView ringtone;
            View hairLine;
            View arrow;
            View collapseExpandArea;
            View preemptiveDismissCollapsed;
            TextView preemptiveDismissCollapsedButton;
            View preemptiveDismissExpanded;
            TextView preemptiveDismissExpandedButton;

            // Other states
            Alarm alarm;
            AlarmInstance instance;
        }

        // Used for scrolling an expanded item in the list to make sure it is fully visible.
        private long mScrollAlarmId = AlarmClockFragment.INVALID_ID;
        private final Runnable mScrollRunnable = new Runnable() {
            @Override
            public void run() {
                if (mScrollAlarmId != AlarmClockFragment.INVALID_ID) {
                    View v = getViewById(mScrollAlarmId);
                    if (v != null) {
                        Rect rect = new Rect(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
                        mList.requestChildRectangleOnScreen(v, rect, false);
                    }
                    mScrollAlarmId = AlarmClockFragment.INVALID_ID;
                }
            }
        };

        public AlarmItemAdapter(Context context, long expandedId, Bundle previousDaysOfWeekMap,
                ListView list) {
            super(context, null, 0);
            mContext = context;
            mFactory = LayoutInflater.from(context);
            mList = list;

            Resources res = mContext.getResources();

            mRobotoNormal = Typeface.create("sans-serif", Typeface.NORMAL);

            mExpandedId = expandedId;

            if (previousDaysOfWeekMap != null) {
                mPreviousDaysOfWeekMap = previousDaysOfWeekMap;
            }

            mHasVibrator = ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE))
                    .hasVibrator();

            mCollapseExpandHeight = (int) res.getDimension(R.dimen.collapse_expand_height);

            setDayOrder();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (!getCursor().moveToPosition(position)) {
                // May happen if the last alarm was deleted and the cursor refreshed while the
                // list is updated.
                LogUtils.v("couldn't move cursor to position " + position);
                return null;
            }
            View v;
            if (convertView == null) {
                v = newView(mContext, getCursor(), parent);
            } else {
                v = convertView;
            }
            bindView(v, mContext, getCursor());
            return v;
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            final View view = mFactory.inflate(R.layout.alarm_time, parent, false);
            setNewHolder(view);
            return view;
        }

        /**
         * In addition to changing the data set for the alarm list, swapCursor is now also
         * responsible for preparing the transition for any added/removed items.
         */
        @Override
        public synchronized Cursor swapCursor(Cursor cursor) {
            if (USE_TRANSITION_FRAMEWORK && (mAddedAlarm != null || mDeletedAlarm != null)) {
                TransitionManager.beginDelayedTransition(mAlarmsList, mAddRemoveTransition);
            }

            final Cursor c = super.swapCursor(cursor);

            mAddedAlarm = null;
            mDeletedAlarm = null;

            return c;
        }

        private void setDayOrder() {
            // Value from preferences corresponds to Calendar.<WEEKDAY> value
            // -1 in order to correspond to DAY_ORDER indexing
            final int startDay = Utils.getZeroIndexedFirstDayOfWeek(mContext);
            mDayOrder = new int[DaysOfWeek.DAYS_IN_A_WEEK];

            for (int i = 0; i < DaysOfWeek.DAYS_IN_A_WEEK; ++i) {
                mDayOrder[i] = DAY_ORDER[(startDay + i) % 7];
            }
        }

        private ItemHolder setNewHolder(View view) {
            // standard view holder optimization
            final ItemHolder holder = new ItemHolder();
            holder.alarmItem = (LinearLayout) view.findViewById(R.id.alarm_item);
            holder.tomorrowLabel = (TextView) view.findViewById(R.id.tomorrowLabel);
            holder.clock = (TextTime) view.findViewById(R.id.digital_clock);
            holder.onoff = (CompoundButton) view.findViewById(R.id.onoff);
            holder.onoff.setTypeface(mRobotoNormal);
            holder.daysOfWeek = (TextView) view.findViewById(R.id.daysOfWeek);
            holder.label = (TextView) view.findViewById(R.id.label);
            holder.delete = (ImageButton) view.findViewById(R.id.delete);
            holder.summary = view.findViewById(R.id.summary);
            holder.expandArea = view.findViewById(R.id.expand_area);
            holder.hairLine = view.findViewById(R.id.hairline);
            holder.arrow = view.findViewById(R.id.arrow);
            holder.repeat = (CheckBox) view.findViewById(R.id.repeat_onoff);
            holder.clickableLabel = (TextView) view.findViewById(R.id.edit_label);
            holder.repeatDays = (LinearLayout) view.findViewById(R.id.repeat_days);
            holder.collapseExpandArea = view.findViewById(R.id.collapse_expand);
            holder.preemptiveDismissCollapsed = view.findViewById(
                    R.id.preemptive_dismiss_collapsed);
            holder.preemptiveDismissCollapsedButton = (TextView) holder.preemptiveDismissCollapsed
                    .findViewById(R.id.preemptive_dismiss_button);
            holder.preemptiveDismissExpanded = view.findViewById(R.id.preemptive_dismiss_expanded);
            holder.preemptiveDismissExpandedButton = (TextView) holder.preemptiveDismissExpanded
                    .findViewById(R.id.preemptive_dismiss_button);

            // Build button for each day.
            for (int i = 0; i < 7; i++) {
                final CompoundButton dayButton = (CompoundButton) mFactory.inflate(
                        R.layout.day_button, holder.repeatDays, false /* attachToRoot */);
                final int firstDay = Utils.getZeroIndexedFirstDayOfWeek(mContext);
                dayButton.setText(Utils.getShortWeekday(i, firstDay));
                dayButton.setContentDescription(Utils.getLongWeekday(i, firstDay));
                holder.repeatDays.addView(dayButton);
                holder.dayButtons[i] = dayButton;
            }
            holder.vibrate = (CheckBox) view.findViewById(R.id.vibrate_onoff);
            holder.ringtone = (TextView) view.findViewById(R.id.choose_ringtone);

            view.setTag(holder);
            return holder;
        }

        @Override
        public void bindView(final View view, Context context, final Cursor cursor) {
            final Alarm alarm = new Alarm(cursor);
            Object tag = view.getTag();
            if (tag == null) {
                // The view was converted but somehow lost its tag.
                tag = setNewHolder(view);
            }
            final ItemHolder itemHolder = (ItemHolder) tag;
            itemHolder.alarm = alarm;

            // We must unset the listener first because this maybe a recycled view so changing the
            // state would affect the wrong alarm.
            itemHolder.onoff.setOnCheckedChangeListener(null);

            // Hack to workaround b/21459481: the SwitchCompat instance must be detached from
            // its parent in order to avoid running the checked animation, which may get stuck
            // when ListView calls View#jumpDrawablesToCurrentState() on a recycled view.
            if (itemHolder.onoff.isChecked() != alarm.enabled) {
                final ViewGroup onoffParent = (ViewGroup) itemHolder.onoff.getParent();
                final int onoffIndex = onoffParent.indexOfChild(itemHolder.onoff);

                onoffParent.removeView(itemHolder.onoff);
                itemHolder.onoff.setChecked(alarm.enabled);
                onoffParent.addView(itemHolder.onoff, onoffIndex);
            }

            itemHolder.alarmItem.setActivated(false);
            setDigitalTimeAlpha(itemHolder, itemHolder.onoff.isChecked());

            itemHolder.clock.setFormat(mContext,
                    mContext.getResources().getDimensionPixelSize(R.dimen.alarm_label_size));
            itemHolder.clock.setTime(alarm.hour, alarm.minutes);
            itemHolder.clock.setClickable(true);
            itemHolder.clock.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mSelectedAlarm = itemHolder.alarm;
                    showTimeEditDialog(alarm);
                    expandAlarm(itemHolder, true);
                    itemHolder.alarmItem.post(mScrollRunnable);
                }
            });

            final CompoundButton.OnCheckedChangeListener onOffListener =
                    new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                    if (checked != alarm.enabled) {
                        final boolean expanded = isAlarmExpanded(alarm);
                        if (!expanded) {
                            // Only toggle this when alarm is collapsed
                            setDigitalTimeAlpha(itemHolder, checked);
                        }
                        alarm.enabled = checked;
                        asyncUpdateAlarm(alarm, alarm.enabled, false);
                        bindPreemptiveDismissView(itemHolder, expanded);
                    }
                }
            };

            if (itemHolder.alarm.daysOfWeek.isRepeating()) {
                itemHolder.tomorrowLabel.setVisibility(View.GONE);
            } else {
                itemHolder.tomorrowLabel.setVisibility(View.VISIBLE);
                final Resources resources = getResources();
                final String labelText = Alarm.isTomorrow(alarm, Calendar.getInstance()) ?
                        resources.getString(R.string.alarm_tomorrow) :
                        resources.getString(R.string.alarm_today);
                itemHolder.tomorrowLabel.setText(labelText);
            }
            itemHolder.onoff.setOnCheckedChangeListener(onOffListener);

            boolean expanded = isAlarmExpanded(alarm);
            if (expanded) {
                mExpandedItemHolder = itemHolder;
            }
            itemHolder.expandArea.setVisibility(expanded? View.VISIBLE : View.GONE);
            itemHolder.delete.setVisibility(expanded ? View.VISIBLE : View.GONE);
            itemHolder.summary.setVisibility(expanded? View.GONE : View.VISIBLE);
            if (canPreemptivelyDismiss(itemHolder.alarm)) {
                itemHolder.hairLine.setVisibility(View.GONE);
            } else {
                itemHolder.hairLine.setVisibility(expanded ? View.GONE : View.VISIBLE);
            }
            itemHolder.arrow.setRotation(expanded ? ROTATE_180_DEGREE : 0);

            // Add listener on the arrow to enable proper talkback functionality.
            // Avoid setting content description on the entire card.
            itemHolder.arrow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (isAlarmExpanded(alarm)) {
                        // Is expanded, make collapse call.
                        collapseAlarm(itemHolder, true);
                    } else {
                        // Is collapsed, make expand call.
                        expandAlarm(itemHolder, true);
                    }
                }
            });

            // Set the repeat text or leave it blank if it does not repeat.
            final String daysOfWeekStr =
                    alarm.daysOfWeek.toString(context, Utils.getFirstDayOfWeek(context));
            if (daysOfWeekStr != null && daysOfWeekStr.length() != 0) {
                itemHolder.daysOfWeek.setText(daysOfWeekStr);
                itemHolder.daysOfWeek.setContentDescription(alarm.daysOfWeek.toAccessibilityString(
                        context, Utils.getFirstDayOfWeek(context)));
                itemHolder.daysOfWeek.setVisibility(View.VISIBLE);
                itemHolder.daysOfWeek.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        expandAlarm(itemHolder, true);
                        itemHolder.alarmItem.post(mScrollRunnable);
                    }
                });

            } else {
                itemHolder.daysOfWeek.setVisibility(View.GONE);
            }

            if (alarm.label != null && alarm.label.length() != 0) {
                itemHolder.label.setText(alarm.label + "  ");
                itemHolder.label.setVisibility(View.VISIBLE);
                itemHolder.label.setContentDescription(
                        mContext.getResources().getString(R.string.label_description) + " "
                        + alarm.label);
                itemHolder.label.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        expandAlarm(itemHolder, true);
                        itemHolder.alarmItem.post(mScrollRunnable);
                    }
                });
            } else {
                itemHolder.label.setVisibility(View.GONE);
            }

            itemHolder.delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mDeletedAlarm = alarm;
                    asyncDeleteAlarm(alarm);
                }
            });

            if (expanded) {
                expandAlarm(itemHolder, false);
            }

            itemHolder.alarmItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (isAlarmExpanded(alarm)) {
                        collapseAlarm(itemHolder, true);
                    } else {
                        expandAlarm(itemHolder, true);
                    }
                }
            });

            if (canPreemptivelyDismiss(itemHolder.alarm)) {
                itemHolder.instance = new AlarmInstance(cursor, true /* joinedTable */);
            } else {
                itemHolder.instance = null;
            }

            bindPreemptiveDismissView(itemHolder, !isAlarmExpanded(alarm));
            if (canPreemptivelyDismiss(alarm)) {
                final View.OnClickListener preemptiveClickListener = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (itemHolder.instance == null) {
                            LogUtils.e("Uninitialized alarm instance");
                            return;
                        }
                        final Intent dismissIntent = AlarmStateManager.createStateChangeIntent(
                                mContext, AlarmStateManager.ALARM_DISMISS_TAG, itemHolder.instance,
                                AlarmInstance.PREDISMISSED_STATE);
                        mContext.sendBroadcast(dismissIntent);
                        // TODO: pop toast that alarm has been dismissed
                    }
                };
                itemHolder.preemptiveDismissExpandedButton
                        .setOnClickListener(preemptiveClickListener);
                itemHolder.preemptiveDismissCollapsedButton
                        .setOnClickListener(preemptiveClickListener);
            }
        }

        private void bindExpandArea(final ItemHolder itemHolder, final Alarm alarm) {
            // Views in here are not bound until the item is expanded.

            if (alarm.label != null && alarm.label.length() > 0) {
                itemHolder.clickableLabel.setText(alarm.label);
            } else {
                itemHolder.clickableLabel.setText(R.string.label);
            }

            itemHolder.clickableLabel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showLabelDialog(alarm);
                }
            });

            if (itemHolder.alarm.daysOfWeek.isRepeating()) {
                itemHolder.repeat.setChecked(true);
                itemHolder.repeatDays.setVisibility(View.VISIBLE);
            } else {
                itemHolder.repeat.setChecked(false);
                itemHolder.repeatDays.setVisibility(View.GONE);
            }
            itemHolder.repeat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    // Animate the resulting layout changes.
                    if (USE_TRANSITION_FRAMEWORK) {
                        TransitionManager.beginDelayedTransition(mList, mRepeatTransition);
                    }

                    final Calendar now = Calendar.getInstance();
                    final Calendar oldNextAlarmTime = alarm.getNextAlarmTime(now);

                    final boolean checked = ((CheckBox) view).isChecked();
                    if (checked) {
                        // Show days
                        itemHolder.repeatDays.setVisibility(View.VISIBLE);

                        // Set all previously set days
                        // or
                        // Set all days if no previous.
                        final int bitSet = mPreviousDaysOfWeekMap.getInt("" + alarm.id);
                        alarm.daysOfWeek.setBitSet(bitSet);
                        if (!alarm.daysOfWeek.isRepeating()) {
                            alarm.daysOfWeek.setDaysOfWeek(true, mDayOrder);
                        }
                        updateDaysOfWeekButtons(itemHolder, alarm.daysOfWeek);
                    } else {
                        // Hide days
                        itemHolder.repeatDays.setVisibility(View.GONE);

                        // Remember the set days in case the user wants it back.
                        final int bitSet = alarm.daysOfWeek.getBitSet();
                        mPreviousDaysOfWeekMap.putInt("" + alarm.id, bitSet);

                        // Remove all repeat days
                        alarm.daysOfWeek.clearAllDays();
                    }

                    // if the change altered the next scheduled alarm time, tell the user
                    final Calendar newNextAlarmTime = alarm.getNextAlarmTime(now);
                    final boolean popupToast = !oldNextAlarmTime.equals(newNextAlarmTime);

                    asyncUpdateAlarm(alarm, popupToast, false);
                }
            });

            updateDaysOfWeekButtons(itemHolder, alarm.daysOfWeek);
            for (int i = 0; i < 7; i++) {
                final int buttonIndex = i;

                itemHolder.dayButtons[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        final boolean isChecked = itemHolder.dayButtons[buttonIndex].isChecked();

                        final Calendar now = Calendar.getInstance();
                        final Calendar oldNextAlarmTime = alarm.getNextAlarmTime(now);
                        alarm.daysOfWeek.setDaysOfWeek(isChecked, mDayOrder[buttonIndex]);

                        if (isChecked) {
                            turnOnDayOfWeek(itemHolder, buttonIndex);
                        } else {
                            turnOffDayOfWeek(itemHolder, buttonIndex);

                            // See if this was the last day, if so, un-check the repeat box.
                            if (!alarm.daysOfWeek.isRepeating()) {
                                if (USE_TRANSITION_FRAMEWORK) {
                                    // Animate the resulting layout changes.
                                    TransitionManager.beginDelayedTransition(mList, mRepeatTransition);
                                }

                                itemHolder.repeat.setChecked(false);
                                itemHolder.repeatDays.setVisibility(View.GONE);

                                // Set history to no days, so it will be everyday when repeat is
                                // turned back on
                                mPreviousDaysOfWeekMap.putInt("" + alarm.id,
                                        DaysOfWeek.NO_DAYS_SET);
                            }
                        }

                        // if the change altered the next scheduled alarm time, tell the user
                        final Calendar newNextAlarmTime = alarm.getNextAlarmTime(now);
                        final boolean popupToast = !oldNextAlarmTime.equals(newNextAlarmTime);

                        asyncUpdateAlarm(alarm, popupToast, false);
                    }
                });
            }

            if (!mHasVibrator) {
                itemHolder.vibrate.setVisibility(View.INVISIBLE);
            } else {
                itemHolder.vibrate.setVisibility(View.VISIBLE);
                if (!alarm.vibrate) {
                    itemHolder.vibrate.setChecked(false);
                } else {
                    itemHolder.vibrate.setChecked(true);
                }
            }

            itemHolder.vibrate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    alarm.vibrate = ((CheckBox) v).isChecked();
                    asyncUpdateAlarm(alarm, false, true);
                }
            });

            final String ringtone;
            if (Alarm.NO_RINGTONE_URI.equals(alarm.alert)) {
                ringtone = mContext.getResources().getString(R.string.silent_alarm_summary);
            } else {
                ringtone = getRingToneTitle(alarm.alert);
            }
            itemHolder.ringtone.setText(ringtone);
            itemHolder.ringtone.setContentDescription(
                    mContext.getResources().getString(R.string.ringtone_description) + " "
                            + ringtone);
            itemHolder.ringtone.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    launchRingTonePicker(alarm);
                }
            });
        }

        // Sets the alpha of the digital time display. This gives a visual effect
        // for enabled/disabled and expanded/collapsed alarm while leaving the
        // on/off switch more visible
        private void setDigitalTimeAlpha(ItemHolder holder, boolean enabled) {
            float alpha = enabled ? 1f : 0.69f;
            holder.clock.setAlpha(alpha);
        }

        private void updateDaysOfWeekButtons(ItemHolder holder, DaysOfWeek daysOfWeek) {
            HashSet<Integer> setDays = daysOfWeek.getSetDays();
            for (int i = 0; i < 7; i++) {
                if (setDays.contains(mDayOrder[i])) {
                    turnOnDayOfWeek(holder, i);
                } else {
                    turnOffDayOfWeek(holder, i);
                }
            }
        }

        private void turnOffDayOfWeek(ItemHolder holder, int dayIndex) {
            final CompoundButton dayButton = holder.dayButtons[dayIndex];
            dayButton.setChecked(false);
            dayButton.setTextColor(getResources().getColor(R.color.clock_white));
        }

        private void turnOnDayOfWeek(ItemHolder holder, int dayIndex) {
            final CompoundButton dayButton = holder.dayButtons[dayIndex];
            dayButton.setChecked(true);
            dayButton.setTextColor(Utils.getCurrentHourColor());
        }


        /**
         * Does a read-through cache for ringtone titles.
         *
         * @param uri The uri of the ringtone.
         * @return The ringtone title. {@literal null} if no matching ringtone found.
         */
        private String getRingToneTitle(Uri uri) {
            // Try the cache first
            String title = mRingtoneTitleCache.getString(uri.toString());
            if (title == null) {
                // If the user cannot read the ringtone file, insert our own name rather than the
                // ugly one returned by Ringtone.getTitle().
                if (!Utils.hasPermissionToDisplayRingtoneTitle(mContext, uri)) {
                    title = getString(R.string.custom_ringtone);
                } else {
                    // This is slow because a media player is created during Ringtone object creation.
                    final Ringtone ringTone = RingtoneManager.getRingtone(mContext, uri);
                    if (ringTone == null) {
                        LogUtils.i("No ringtone for uri %s", uri.toString());
                        return null;
                    }
                    title = ringTone.getTitle(mContext);
                }

                if (title != null) {
                    mRingtoneTitleCache.putString(uri.toString(), title);
                }
            }
            return title;
        }

        public void setNewAlarm(long alarmId) {
            if (mExpandedId != alarmId) {
                if (mExpandedItemHolder != null) {
                    collapseAlarm(mExpandedItemHolder, true);
                }
                mExpandedId = alarmId;
            }
        }

        /**
         * Expands the alarm for editing.
         *
         * @param itemHolder The item holder instance.
         */
        private void expandAlarm(final ItemHolder itemHolder, boolean animate) {
            // Skip animation later if item is already expanded
            animate &= mExpandedId != itemHolder.alarm.id;

            if (mExpandedItemHolder != null
                    && mExpandedItemHolder != itemHolder
                    && mExpandedId != itemHolder.alarm.id) {
                // Only allow one alarm to expand at a time.
                collapseAlarm(mExpandedItemHolder, animate);
            }

            bindExpandArea(itemHolder, itemHolder.alarm);

            mExpandedId = itemHolder.alarm.id;
            mExpandedItemHolder = itemHolder;

            // Scroll the view to make sure it is fully viewed
            mScrollAlarmId = itemHolder.alarm.id;

            // Save the starting height so we can animate from this value.
            final int startingHeight = itemHolder.alarmItem.getHeight();

            // Set the expand area to visible so we can measure the height to animate to.
            itemHolder.alarmItem.setActivated(true);
            itemHolder.expandArea.setVisibility(View.VISIBLE);
            bindPreemptiveDismissView(itemHolder, false /* collapsed */);
            itemHolder.delete.setVisibility(View.VISIBLE);
            // Show digital time in full-opaque when expanded, even when alarm is disabled
            setDigitalTimeAlpha(itemHolder, true /* enabled */);

            itemHolder.arrow.setContentDescription(getString(R.string.collapse_alarm));

            if (!animate) {
                // Set the "end" layout and don't do the animation.
                itemHolder.arrow.setRotation(ROTATE_180_DEGREE);
                itemHolder.summary.setVisibility(View.GONE);
                itemHolder.hairLine.setVisibility(View.GONE);
                itemHolder.delete.setVisibility(View.VISIBLE);
                return;
            }

            // Mark the alarmItem as having transient state to prevent it from being recycled
            // while it is animating.
            itemHolder.alarmItem.setHasTransientState(true);

            // Add an onPreDrawListener, which gets called after measurement but before the draw.
            // This way we can check the height we need to animate to before any drawing.
            // Note the series of events:
            //  * expandArea is set to VISIBLE, which causes a layout pass
            //  * the view is measured, and our onPreDrawListener is called
            //  * we set up the animation using the start and end values.
            //  * the height is set back to the starting point so it can be animated down.
            //  * request another layout pass.
            //  * return false so that onDraw() is not called for the single frame before
            //    the animations have started.
            final ViewTreeObserver observer = mAlarmsList.getViewTreeObserver();
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    // We don't want to continue getting called for every listview drawing.
                    if (observer.isAlive()) {
                        observer.removeOnPreDrawListener(this);
                    }
                    // Calculate some values to help with the animation.
                    final int endingHeight = itemHolder.alarmItem.getHeight();
                    final int distance = endingHeight - startingHeight;
                    final int collapseHeight = itemHolder.collapseExpandArea.getHeight();

                    // Set the height back to the start state of the animation.
                    itemHolder.alarmItem.getLayoutParams().height = startingHeight;
                    // To allow the expandArea to glide in with the expansion animation, set a
                    // negative top margin, which will animate down to a margin of 0 as the height
                    // is increased.
                    // Note that we need to maintain the bottom margin as a fixed value (instead of
                    // just using a listview, to allow for a flatter hierarchy) to fit the bottom
                    // bar underneath.
                    FrameLayout.LayoutParams expandParams = (FrameLayout.LayoutParams)
                            itemHolder.expandArea.getLayoutParams();
                    expandParams.setMargins(0, -distance, 0, collapseHeight);
                    itemHolder.alarmItem.requestLayout();

                    // Set up the animator to animate the expansion.
                    ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f)
                            .setDuration(EXPAND_DURATION);
                    animator.setInterpolator(mExpandInterpolator);
                    animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animator) {
                            Float value = (Float) animator.getAnimatedValue();

                            // For each value from 0 to 1, animate the various parts of the layout.
                            itemHolder.alarmItem.getLayoutParams().height =
                                    (int) (value * distance + startingHeight);
                            FrameLayout.LayoutParams expandParams = (FrameLayout.LayoutParams)
                                    itemHolder.expandArea.getLayoutParams();
                            expandParams.setMargins(
                                    0, (int) -((1 - value) * distance), 0, collapseHeight);
                            itemHolder.arrow.setRotation(ROTATE_180_DEGREE * value);
                            itemHolder.summary.setAlpha(1 - value);
                            itemHolder.hairLine.setAlpha(1 - value);

                            itemHolder.alarmItem.requestLayout();
                        }
                    });
                    // Set everything to their final values when the animation's done.
                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // Set it back to wrap content since we'd explicitly set the height.
                            itemHolder.alarmItem.getLayoutParams().height =
                                    LayoutParams.WRAP_CONTENT;
                            itemHolder.arrow.setRotation(ROTATE_180_DEGREE);
                            itemHolder.summary.setAlpha(1);
                            itemHolder.hairLine.setAlpha(1);
                            itemHolder.summary.setVisibility(View.GONE);
                            itemHolder.hairLine.setVisibility(View.GONE);
                            itemHolder.delete.setVisibility(View.VISIBLE);
                            itemHolder.alarmItem.setHasTransientState(false);
                        }
                    });
                    animator.start();

                    // Return false so this draw does not occur to prevent the final frame from
                    // being drawn for the single frame before the animations start.
                    return false;
                }
            });
        }

        private boolean isAlarmExpanded(Alarm alarm) {
            return mExpandedId == alarm.id;
        }

        /**
         * Whether the alarm is in a state to show preemptive dismiss. Valid states are SNOOZE_STATE
         * HIGH_NOTIFICATION, and LOW_NOTIFICATION. TODO: firing state?
         */
        private boolean canPreemptivelyDismiss(Alarm alarm) {
            return alarm.instanceState == AlarmInstance.SNOOZE_STATE
                    || alarm.instanceState == AlarmInstance.HIGH_NOTIFICATION_STATE
                    || alarm.instanceState == AlarmInstance.LOW_NOTIFICATION_STATE;
        }

        /**
         * Add the preemptive dismiss button and hairline to the proper view depending on if the
         * alarm view is collapsed or expanded.
         */
        private void bindPreemptiveDismissView(ItemHolder itemHolder, boolean collapsed) {
            // Determine which view is to show and which one is to hide depending on
            // collapse/expand state.
            final View showView = collapsed
                    ? itemHolder.preemptiveDismissCollapsed
                    : itemHolder.preemptiveDismissExpanded;
            final View hideView = collapsed
                    ? itemHolder.preemptiveDismissExpanded
                    : itemHolder.preemptiveDismissCollapsed;

            // Always hide this view regardless of current alarm state.
            hideView.setVisibility(View.GONE);

            // Set visibility of show view depending on alarm state.
            if (!canPreemptivelyDismiss(itemHolder.alarm) || itemHolder.instance == null) {
                showView.setVisibility(View.GONE);
                return;
            }
            showView.setVisibility(View.VISIBLE);

            // Set button text; select button based on collapse/expand state
            final TextView button = collapsed
                    ? itemHolder.preemptiveDismissCollapsedButton
                    : itemHolder.preemptiveDismissExpandedButton;
            final String buttonText =
                    itemHolder.alarm.instanceState == AlarmInstance.SNOOZE_STATE
                            ? mContext.getString(R.string.alarm_alert_snooze_until,
                            AlarmUtils.getAlarmText(mContext, itemHolder.instance, false))
                            : mContext.getString(R.string.alarm_alert_dismiss_now_text);
            button.setText(buttonText);
        }

        private void collapseAlarm(final ItemHolder itemHolder, boolean animate) {
            mExpandedId = AlarmClockFragment.INVALID_ID;
            mExpandedItemHolder = null;

            // Save the starting height so we can animate from this value.
            final int startingHeight = itemHolder.alarmItem.getHeight();

            // Set the expand area to gone so we can measure the height to animate to.
            itemHolder.alarmItem.setActivated(false);
            itemHolder.expandArea.setVisibility(View.GONE);
            setDigitalTimeAlpha(itemHolder, itemHolder.onoff.isChecked());

            itemHolder.arrow.setContentDescription(getString(R.string.expand_alarm));

            if (!animate) {
                // Set the "end" layout and don't do the animation.
                itemHolder.arrow.setRotation(0);
                itemHolder.hairLine.setTranslationY(0);
                if (canPreemptivelyDismiss(itemHolder.alarm)) {
                    itemHolder.hairLine.setVisibility(View.GONE);
                } else {
                    itemHolder.hairLine.setVisibility(View.VISIBLE);
                }
                itemHolder.summary.setAlpha(1);
                itemHolder.summary.setVisibility(View.VISIBLE);
                return;
            }

            bindPreemptiveDismissView(itemHolder, true);

            // Mark the alarmItem as having transient state to prevent it from being recycled
            // while it is animating.
            itemHolder.alarmItem.setHasTransientState(true);

            // Add an onPreDrawListener, which gets called after measurement but before the draw.
            // This way we can check the height we need to animate to before any drawing.
            // Note the series of events:
            //  * expandArea is set to GONE, which causes a layout pass
            //  * the view is measured, and our onPreDrawListener is called
            //  * we set up the animation using the start and end values.
            //  * expandArea is set to VISIBLE again so it can be shown animating.
            //  * request another layout pass.
            //  * return false so that onDraw() is not called for the single frame before
            //    the animations have started.
            final ViewTreeObserver observer = mAlarmsList.getViewTreeObserver();
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (observer.isAlive()) {
                        observer.removeOnPreDrawListener(this);
                    }

                    // Calculate some values to help with the animation.
                    final int endingHeight = itemHolder.alarmItem.getHeight();
                    final int distance = endingHeight - startingHeight;

                    // Re-set the visibilities for the start state of the animation.
                    itemHolder.expandArea.setVisibility(View.VISIBLE);
                    itemHolder.delete.setVisibility(View.GONE);
                    itemHolder.summary.setVisibility(View.VISIBLE);
                    itemHolder.hairLine.setVisibility(
                            canPreemptivelyDismiss(itemHolder.alarm) ? View.GONE : View.VISIBLE);
                    itemHolder.summary.setAlpha(1);

                    // Set up the animator to animate the expansion.
                    ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f)
                            .setDuration(COLLAPSE_DURATION);
                    animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animator) {
                            Float value = (Float) animator.getAnimatedValue();

                            // For each value from 0 to 1, animate the various parts of the layout.
                            itemHolder.alarmItem.getLayoutParams().height =
                                    (int) (value * distance + startingHeight);
                            FrameLayout.LayoutParams expandParams = (FrameLayout.LayoutParams)
                                    itemHolder.expandArea.getLayoutParams();
                            expandParams.setMargins(
                                    0, (int) (value * distance), 0, mCollapseExpandHeight);
                            itemHolder.arrow.setRotation(ROTATE_180_DEGREE * (1 - value));
                            itemHolder.delete.setAlpha(value);
                            itemHolder.summary.setAlpha(value);
                            itemHolder.hairLine.setAlpha(value);

                            itemHolder.alarmItem.requestLayout();
                        }
                    });
                    animator.setInterpolator(mCollapseInterpolator);
                    // Set everything to their final values when the animation's done.
                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // Set it back to wrap content since we'd explicitly set the height.
                            itemHolder.alarmItem.getLayoutParams().height =
                                    LayoutParams.WRAP_CONTENT;

                            FrameLayout.LayoutParams expandParams = (FrameLayout.LayoutParams)
                                    itemHolder.expandArea.getLayoutParams();
                            expandParams.setMargins(0, 0, 0, mCollapseExpandHeight);

                            itemHolder.expandArea.setVisibility(View.GONE);
                            itemHolder.arrow.setRotation(0);
                            itemHolder.alarmItem.setHasTransientState(false);
                        }
                    });
                    animator.start();

                    return false;
                }
            });
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        private View getViewById(long id) {
            for (int i = 0; i < mList.getCount(); i++) {
                View v = mList.getChildAt(i);
                if (v != null) {
                    ItemHolder h = (ItemHolder)(v.getTag());
                    if (h != null && h.alarm.id == id) {
                        return v;
                    }
                }
            }
            return null;
        }

        public long getExpandedId() {
            return mExpandedId;
        }

        public Bundle getPreviousDaysOfWeekMap() {
            return mPreviousDaysOfWeekMap;
        }
    }

    private static AlarmInstance setupAlarmInstance(Context context, Alarm alarm) {
        ContentResolver cr = context.getContentResolver();
        AlarmInstance newInstance = alarm.createInstanceAfter(Calendar.getInstance());
        newInstance = AlarmInstance.addInstance(cr, newInstance);
        // Register instance to state manager
        AlarmStateManager.registerInstance(context, newInstance, true);
        return newInstance;
    }

    private void asyncDeleteAlarm(final Alarm alarm) {
        final Context context = getActivity().getApplicationContext();
        final AsyncTask<Void, Void, Boolean> deleteTask = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... parameters) {
                // Activity may be closed at this point , make sure data is still valid
                if (context == null || alarm == null) {
                    // Nothing to do here, just return.
                    return false;
                }
                Events.sendAlarmEvent(R.string.action_delete, R.string.label_deskclock);
                AlarmStateManager.deleteAllInstances(context, alarm.id);
                return Alarm.deleteAlarm(context.getContentResolver(), alarm.id);
            }

            @Override
            protected void onPostExecute(Boolean deleted) {
                if (deleted) {
                    mUndoShowing = true;
                    mDeletedAlarm = alarm;
                    showUndoBar();
                }
            }
        };
        deleteTask.execute();
    }

    protected void asyncAddAlarm(final Alarm alarm) {
        final Context context = AlarmClockFragment.this.getActivity().getApplicationContext();
        final AsyncTask<Void, Void, AlarmInstance> updateTask =
                new AsyncTask<Void, Void, AlarmInstance>() {
            @Override
            protected AlarmInstance doInBackground(Void... parameters) {
                if (context != null && alarm != null) {
                    Events.sendAlarmEvent(R.string.action_create, R.string.label_deskclock);
                    ContentResolver cr = context.getContentResolver();

                    // Add alarm to db
                    Alarm newAlarm = Alarm.addAlarm(cr, alarm);
                    mScrollToAlarmId = newAlarm.id;

                    // Create and add instance to db
                    if (newAlarm.enabled) {
                        return setupAlarmInstance(context, newAlarm);
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(AlarmInstance instance) {
                if (instance != null) {
                    AlarmUtils.popAlarmSetToast(context, instance.getAlarmTime().getTimeInMillis());
                }
            }
        };
        updateTask.execute();
    }

    /**
     * @param alarm
     * @param popToast
     * @param minorUpdate If the alarm update is superficial and cannot affect the timing of the
     *                    next firing instance (e.g. a label, ringtone, or vibration change).
     */
    protected void asyncUpdateAlarm(final Alarm alarm, final boolean popToast,
                                    final boolean minorUpdate) {
        final Context context = AlarmClockFragment.this.getActivity().getApplicationContext();
        final AsyncTask<Void, Void, AlarmInstance> updateTask =
                new AsyncTask<Void, Void, AlarmInstance>() {
            @Override
            protected AlarmInstance doInBackground(Void ... parameters) {
                Events.sendAlarmEvent(R.string.action_update, R.string.label_deskclock);
                ContentResolver cr = context.getContentResolver();

                if (minorUpdate) {
                    // For minor updates, we don't want to affect any currently snoozed instances.
                    AlarmStateManager.deleteNonSnoozeInstances(context, alarm.id);
                } else {
                    AlarmStateManager.deleteAllInstances(context, alarm.id);
                }

                // Update alarm
                Alarm.updateAlarm(cr, alarm);
                if (alarm.enabled) {
                    return setupAlarmInstance(context, alarm);
                }

                return null;
            }

            @Override
            protected void onPostExecute(AlarmInstance instance) {
                if (popToast && instance != null) {
                    AlarmUtils.popAlarmSetToast(context, instance.getAlarmTime().getTimeInMillis());
                }
            }
        };
        updateTask.execute();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        hideUndoBar(true, event);
        return false;
    }

    @Override
    public void onFabClick(View view){
        hideUndoBar(true, null);
        startCreatingAlarm();
    }

    @Override
    public void setFabAppearance() {
        final DeskClock activity = (DeskClock) getActivity();
        if (mFab == null || activity.getSelectedTab() != DeskClock.ALARM_TAB_INDEX) {
            return;
        }
        mFab.setVisibility(View.VISIBLE);
        mFab.setImageResource(R.drawable.ic_fab_plus);
        mFab.setContentDescription(getString(R.string.button_alarms));
    }

    @Override
    public void setLeftRightButtonAppearance() {
        final DeskClock activity = (DeskClock) getActivity();
        if (mLeftButton == null || mRightButton == null ||
                activity.getSelectedTab() != DeskClock.ALARM_TAB_INDEX) {
            return;
        }
        mLeftButton.setVisibility(View.INVISIBLE);
        mRightButton.setVisibility(View.INVISIBLE);
    }

    private void showTimeEditDialog(Alarm alarm) {
        TimePickerCompat.showTimeEditDialog(this, alarm, DateFormat.is24HourFormat(getActivity()));
    }

    private void startCreatingAlarm() {
        mSelectedAlarm = null;
        TimePickerCompat.showTimeEditDialog(this, null /* alarm */,
                DateFormat.is24HourFormat(getActivity()));
    }
}
