package com.example.smartfeederapp;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.clearText;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition;

import static org.hamcrest.Matchers.not;


import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented tests for basic UI flow starting from MainActivity.
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityFlowTest {

    @Rule
    public ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void testNavigateToSettingsAndBack() {
        onView(withId(R.id.btnSettings)).perform(click());

        onView(withId(R.id.settings_layout)).check(matches(isDisplayed()));

        pressBack();

        onView(withId(R.id.main)).check(matches(isDisplayed()));
    }

    @Test
    public void testSettingsInteraction() {
        final String testServerAddress = "10.0.2.2:9999";

        onView(withId(R.id.btnSettings)).perform(click());
        onView(withId(R.id.settings_layout)).check(matches(isDisplayed()));

        onView(withId(R.id.etServerAddressSettings))
                .perform(clearText(), typeText(testServerAddress), closeSoftKeyboard());

        onView(withId(R.id.etServerAddressSettings))
                .check(matches(withText(testServerAddress)));

        onView(withId(R.id.btnConnectAndSave)).perform(click());

        pressBack();
        onView(withId(R.id.main)).check(matches(isDisplayed()));
    }

    @Test
    public void testMainActivityButtonsDisplayed() {
        onView(withId(R.id.btnSettings)).check(matches(isDisplayed()));
        onView(withId(R.id.btnRefreshFeeders)).check(matches(isDisplayed()));
        onView(withId(R.id.btnLoadVideos)).check(matches(isDisplayed()));
        onView(withId(R.id.btnRequestStream)).check(matches(isDisplayed()));
        onView(withId(R.id.tilFeederId)).check(matches(isDisplayed()));
        onView(withId(R.id.rvVideoList)).check(matches(isDisplayed()));
    }

    @Test
    public void testLoadVideosAndClickItem() {
        onView(withId(R.id.btnLoadVideos)).perform(click());

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        onView(withId(R.id.rvVideoList)).check(matches(isDisplayed()));

        try {
            onView(withId(R.id.rvVideoList))
                    .perform(actionOnItemAtPosition(0, click()));

            onView(withId(R.id.playerView)).check(matches(isDisplayed()));

        } catch (Exception e) {
            System.out.println("Could not click item at position 0.");
            e.printStackTrace();
        }
    }

    @Test
    public void testStreamControlsInitiallyHidden() {
        onView(withId(R.id.tvStreamTitle)).check(matches(not(isDisplayed())));
        onView(withId(R.id.streamPlayerView)).check(matches(not(isDisplayed())));
        onView(withId(R.id.btnStopStream)).check(matches(not(isDisplayed())));
    }
}