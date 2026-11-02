package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure logic only: no JavaFX toolkit is needed. */
class TreeGesturesTest {
    private static final File A = new File("a.txt"), B = new File("b.txt");
    private TreeGestures gestures;

    @BeforeEach void setUp() { gestures = new TreeGestures(); }

    // ---- double-click detection ----

    @Test void aSinglePressIsNotADoubleClick() {
        assertFalse(gestures.press(A, 1000, 10, 10));
    }

    @Test void twoQuickPressesOnTheSameRowAreADoubleClick() {
        gestures.press(A, 1000, 10, 10);
        assertTrue(gestures.press(A, 1200, 10, 10));
    }

    @Test void theSecondPressMayLandWithinTheGapEdge() {
        gestures.press(A, 1000, 0, 0);
        assertTrue(gestures.press(A, 1000 + TreeGestures.DOUBLE_CLICK_MILLIS, 0, 0));
    }

    @Test void aSlowSecondPressIsANewFirstClick() {
        gestures.press(A, 1000, 0, 0);
        assertFalse(gestures.press(A, 1000 + TreeGestures.DOUBLE_CLICK_MILLIS + 1, 0, 0));
        assertTrue(gestures.press(A, 1000 + TreeGestures.DOUBLE_CLICK_MILLIS + 100, 0, 0), "…which can still be completed");
    }

    @Test void pressesOnDifferentRowsNeverFormADoubleClick() {
        gestures.press(A, 1000, 0, 0);
        assertFalse(gestures.press(B, 1100, 0, 20), "the second press belongs to another file");
        assertTrue(gestures.press(B, 1200, 0, 20), "but a second press on B right after is a double-click");
    }

    @Test void aSameRowDoubleClickIsRecognisedWhenThePointerWobbles() {
        gestures.press(A, 1000, 10, 10);
        assertTrue(gestures.press(A, 1150, 14, 12), "a few pixels of movement between clicks must not matter");
    }

    @Test void aCompletedDoubleClickIsUsedUp() {
        gestures.press(A, 1000, 0, 0);
        assertTrue(gestures.press(A, 1100, 0, 0));
        assertFalse(gestures.press(A, 1200, 0, 0), "a third quick press starts a new sequence");
        assertTrue(gestures.press(A, 1300, 0, 0));
    }

    @Test void equalFilesFromARebuiltTreeStillMatch() {
        gestures.press(new File("src/Main.java"), 1000, 0, 0);
        assertTrue(gestures.press(new File("src/Main.java"), 1100, 0, 0), "rows are compared by value, not identity");
    }

    @Test void pressingOnNothingClearsAPendingClick() {
        gestures.press(A, 1000, 0, 0);
        assertFalse(gestures.press(null, 1050, 0, 300), "empty space");
        assertFalse(gestures.press(A, 1100, 0, 0), "the earlier click no longer counts");
    }

    @Test void resetForgetsThePendingClick() {
        gestures.press(A, 1000, 0, 0);
        gestures.reset();
        assertFalse(gestures.press(A, 1100, 0, 0));
    }

    @Test void aClockThatGoesBackwardsIsNotADoubleClick() {
        gestures.press(A, 5000, 0, 0);
        assertFalse(gestures.press(A, 4000, 0, 0));
    }

    // ---- drag vs click ----

    @Test void noMovementIsNotADrag() {
        gestures.press(A, 0, 100, 100);
        assertFalse(gestures.dragIntended(100, 100));
    }

    @Test void aSlightlyShakyClickIsNotADrag() {
        gestures.press(A, 0, 100, 100);
        assertFalse(gestures.dragIntended(103, 104), "5 px away: still a click");
        assertFalse(gestures.dragIntended(100 + TreeGestures.DRAG_THRESHOLD - 0.1, 100));
    }

    @Test void movingFarEnoughIsADrag() {
        gestures.press(A, 0, 100, 100);
        assertTrue(gestures.dragIntended(100 + TreeGestures.DRAG_THRESHOLD, 100));
        assertTrue(gestures.dragIntended(100, 60), "any direction counts");
        assertTrue(gestures.dragIntended(94, 94), "diagonal travel is measured as a straight line");
    }

    @Test void dragDistanceIsMeasuredFromTheLatestPress() {
        gestures.press(A, 0, 0, 0);
        gestures.press(B, 5000, 200, 200);
        assertFalse(gestures.dragIntended(203, 203));
        assertTrue(gestures.dragIntended(0, 0), "far from the new press point");
    }
}
