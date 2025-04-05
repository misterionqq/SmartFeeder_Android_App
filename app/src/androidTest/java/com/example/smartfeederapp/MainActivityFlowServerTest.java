package com.example.smartfeederapp;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.fail;

import android.view.View;
import android.widget.AutoCompleteTextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.IdlingResourceTimeoutException;
import androidx.test.espresso.PerformException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.matcher.BoundedMatcher;
import androidx.test.espresso.matcher.RootMatchers;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.util.Collections;

/**
 * Instrumented tests interacting with a local server.
 * NOTE: Assumes the server at SERVER_ADDRESS is running and configured
 *       to return a list of videos and an empty list of feeders.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MainActivityFlowServerTest {

    private static final String SERVER_ADDRESS = "192.168.2.41:5000";
    private static final String EXPECTED_NO_FEEDERS_TEXT = "Error loading feeders: 404";
    private static final long WAIT_TIMEOUT = 5000;
    private static final long POLLING_INTERVAL = 500;

    @Rule
    public ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    private static boolean setupPerformed = false;

    @Before
    public void setup() {
        if (!setupPerformed) {
            performServerSetup();
            setupPerformed = true;
        }
    }


    private void performServerSetup() {
        onView(withId(R.id.btnSettings)).perform(click());
        onView(withId(R.id.settings_layout)).check(matches(isDisplayed()));

        onView(withId(R.id.etServerAddressSettings))
                .perform(replaceText(SERVER_ADDRESS), closeSoftKeyboard());
        onView(withId(R.id.etServerAddressSettings))
                .check(matches(withText(SERVER_ADDRESS)));

        onView(withId(R.id.btnConnectAndSave)).perform(click());

        waitFor(1000);
        pressBack();
        onView(withId(R.id.main)).check(matches(isDisplayed()));
    }

    @Test
    public void A_testNavigateToSettingsAndBack() {
        onView(withId(R.id.btnSettings)).perform(click());
        onView(withId(R.id.settings_layout)).check(matches(isDisplayed()));

        onView(withId(R.id.etServerAddressSettings)).check(matches(withText(SERVER_ADDRESS)));
        pressBack();
        onView(withId(R.id.main)).check(matches(isDisplayed()));
    }


    @Test
    public void B_testMainActivityButtonsDisplayed() {
        onView(withId(R.id.btnSettings)).check(matches(isDisplayed()));
        onView(withId(R.id.btnRefreshFeeders)).check(matches(isDisplayed()));
        onView(withId(R.id.btnLoadVideos)).check(matches(isDisplayed()));
        onView(withId(R.id.btnRequestStream)).check(matches(isDisplayed()));
        onView(withId(R.id.tilFeederId)).check(matches(isDisplayed()));
        onView(withId(R.id.rvVideoList)).check(matches(isDisplayed()));
    }

    @Test
    public void C_testStreamControlsInitiallyHidden() {
        onView(withId(R.id.tvStreamTitle)).check(matches(not(isDisplayed())));
        onView(withId(R.id.streamPlayerView)).check(matches(not(isDisplayed())));
        onView(withId(R.id.btnStopStream)).check(matches(not(isDisplayed())));
    }

    @Test
    public void D_testLoadVideos_populatesRecyclerView() {
        onView(withId(R.id.btnLoadVideos)).perform(click());

        onView(withId(R.id.rvVideoList))
                .perform(waitForRecyclerViewData(WAIT_TIMEOUT));

        onView(withId(R.id.rvVideoList)).check(matches(hasItemCountGreaterThan(0)));
    }

    @Test
    public void E_testClickVideoItem_showsPlayer() {
        onView(withId(R.id.btnLoadVideos)).perform(click());
        onView(withId(R.id.rvVideoList))
                .perform(waitForRecyclerViewData(WAIT_TIMEOUT));

        try {
            onView(withId(R.id.rvVideoList))
                    .perform(actionOnItemAtPosition(0, click()));

            onView(withId(R.id.playerView)).check(matches(isDisplayed()));

            onView(withId(R.id.streamPlayerView)).check(matches(not(isDisplayed())));

        } catch (PerformException e) {
            fail("Could not click item at position 0. Ensure server provides videos and Load Videos works.");
        }
    }
/*
    @Test
    public void F_testClickDownloadButton_showsStartingDownloadToast() {

        onView(withId(R.id.btnLoadVideos)).perform(click());
        onView(withId(R.id.rvVideoList))
                .perform(waitForRecyclerViewData(WAIT_TIMEOUT));

        try {

            onView(withId(R.id.rvVideoList))
                    .perform(actionOnItemAtPosition(0, clickChildViewWithId(R.id.btnDownloadVideo)));



            final View[] decorView = new View[1];
            activityRule.getScenario().onActivity(activity -> decorView[0] = activity.getWindow().getDecorView());


            onView(withText(startsWith("Starting download:")))
                    .inRoot(RootMatchers.withDecorView(not(is(decorView[0]))))
                    .check(matches(isDisplayed()));

            waitFor(500);

        } catch (PerformException e) {
            fail("Could not perform click on download button at position 0. Ensure server provides videos and Load Videos works. Error: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("An error occurred during download button or Toast check.");
            e.printStackTrace();
            fail("Exception during test execution: " + e.getMessage());
        }
    }

    @Test
    public void G_testRefreshFeeders_showsNoFeedersMessage() {

        onView(withId(R.id.btnRefreshFeeders)).perform(click());


        waitFor(1500);


        onView(withId(R.id.actvFeederId)).check(matches(withText(EXPECTED_NO_FEEDERS_TEXT)));
    }

    @Test
    public void H_testRequestStream_failsAndControlsHidden() {


        onView(withId(R.id.btnRefreshFeeders)).perform(click());
        waitFor(500);
        onView(withId(R.id.actvFeederId)).check(matches(withText(EXPECTED_NO_FEEDERS_TEXT)));


        onView(withId(R.id.btnRequestStream)).perform(click());


        waitFor(1000);


        onView(withId(R.id.tvStreamTitle)).check(matches(not(isDisplayed())));
        onView(withId(R.id.streamPlayerView)).check(matches(not(isDisplayed())));
        onView(withId(R.id.btnStopStream)).check(matches(not(isDisplayed())));
==
    }*/

    @NonNull
    public static ViewAction clickChildViewWithId(final int id) {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() { return ViewMatchers.isEnabled();}
            @Override
            public String getDescription() { return "Click on a child view with specified id.";}
            @Override
            public void perform(UiController uiController, View view) {
                View v = view.findViewById(id);
                if (v != null) { v.performClick(); }
                else { throw new PerformException.Builder().withActionDescription(this.getDescription()).withViewDescription(view.toString()).withCause(new RuntimeException("No view found with ID " + id + " in item view")).build(); }
            }
        };
    }

    private void waitFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static ViewAction waitForRecyclerViewData(final long timeout) {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isAssignableFrom(RecyclerView.class);
            }

            @Override
            public String getDescription() {
                return "wait for data to appear in RecyclerView up to " + timeout + " ms";
            }

            @Override
            public void perform(UiController uiController, View view) {
                uiController.loopMainThreadUntilIdle();
                final long startTime = System.currentTimeMillis();
                final long endTime = startTime + timeout;
                final RecyclerView recyclerView = (RecyclerView) view;

                do {
                    if (recyclerView.getAdapter() != null && recyclerView.getAdapter().getItemCount() > 0) {
                        return;
                    }
                    uiController.loopMainThreadForAtLeast(POLLING_INTERVAL);
                } while (System.currentTimeMillis() < endTime);

                throw new PerformException.Builder()
                        .withActionDescription(getDescription())
                        .withViewDescription(view.toString())
                        .withCause(new IdlingResourceTimeoutException(Collections.singletonList("No data appeared in RecyclerView within " + timeout + " ms")))
                        .build();
            }
        };
    }

    public static Matcher<View> hasItemCountGreaterThan(final int count) {
        return new BoundedMatcher<View, RecyclerView>(RecyclerView.class) {
            @Override
            public void describeTo(Description description) {
                description.appendText("has item count greater than: " + count);
            }

            @Override
            protected boolean matchesSafely(RecyclerView recyclerView) {
                RecyclerView.Adapter adapter = recyclerView.getAdapter();
                return adapter != null && adapter.getItemCount() > count;
            }
        };
    }
}