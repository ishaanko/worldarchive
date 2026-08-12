package dev.ishaanko.worldarchive.ui.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScreenGeometryTest {
    @Test
    void contentWidthClampsToMinimumOnNarrowScreens() {
        assertEquals(180, ScreenGeometry.contentWidth(150, 180, 620, 20));
    }

    @Test
    void contentWidthClampsToMaximumOnWideScreens() {
        assertEquals(620, ScreenGeometry.contentWidth(4000, 180, 620, 20));
    }

    @Test
    void contentWidthSubtractsMarginInTheUnclampedMiddle() {
        assertEquals(380, ScreenGeometry.contentWidth(400, 180, 620, 20));
    }

    @Test
    void contentWidthHandlesExactBoundaryValues() {
        assertEquals(180, ScreenGeometry.contentWidth(200, 180, 620, 20));
        assertEquals(620, ScreenGeometry.contentWidth(640, 180, 620, 20));
    }

    @Test
    void contentWidthClampsToMinimumOneBelowTheCrossover() {
        assertEquals(180, ScreenGeometry.contentWidth(199, 180, 620, 20));
        assertEquals(181, ScreenGeometry.contentWidth(201, 180, 620, 20));
    }

    @Test
    void contentWidthSubtractsTheMarginOnceNotTwice() {
        assertEquals(276, ScreenGeometry.contentWidth(300, 180, 620, 24));
    }

    @Test
    void contentWidthClampsToMinimumOnDegenerateScreens() {
        assertEquals(240, ScreenGeometry.contentWidth(0, 240, 450, 24));
    }

    @Test
    void centerXSplitsTheRemainingSpaceEvenly() {
        assertEquals(10, ScreenGeometry.centerX(400, 380));
        assertEquals(0, ScreenGeometry.centerX(400, 400));
    }

    @Test
    void centerXTruncatesAnOddRemainderTowardTheLeft() {
        assertEquals(10, ScreenGeometry.centerX(401, 380));
        assertEquals(0, ScreenGeometry.centerX(181, 180));
    }

    @Test
    void centerXReturnsZeroWhenContentIsWiderThanTheScreen() {
        assertEquals(0, ScreenGeometry.centerX(180, 180));
        assertEquals(-10, ScreenGeometry.centerX(160, 180));
    }

    @Test
    void anchorBottomSwitchesAtTheExactCrossover() {
        assertEquals(108, ScreenGeometry.anchorBottom(108, 172, 64));
        assertEquals(109, ScreenGeometry.anchorBottom(108, 173, 64));
    }

    @Test
    void anchorBottomStaysAboveMinimumOnShortScreens() {
        assertEquals(108, ScreenGeometry.anchorBottom(108, 150, 64));
    }

    @Test
    void anchorBottomTracksTheBottomOnTallScreens() {
        assertEquals(436, ScreenGeometry.anchorBottom(108, 500, 64));
    }

    @Test
    void anchorMiddleStaysAboveMinimumOnShortScreens() {
        assertEquals(12, ScreenGeometry.anchorMiddle(12, 100, -72));
    }

    @Test
    void anchorMiddleTracksTheMiddleOnTallScreens() {
        assertEquals(428, ScreenGeometry.anchorMiddle(12, 1000, -72));
    }

    @Test
    void anchorMiddleHalvesOddHeightsBeforeApplyingTheOffset() {
        assertEquals(428, ScreenGeometry.anchorMiddle(12, 1001, -72));
        assertEquals(429, ScreenGeometry.anchorMiddle(12, 1002, -72));
    }

    @Test
    void anchorMiddleSwitchesAtTheExactCrossover() {
        assertEquals(12, ScreenGeometry.anchorMiddle(12, 168, -72));
        assertEquals(13, ScreenGeometry.anchorMiddle(12, 170, -72));
    }

    @Test
    void anchorMiddleAcceptsPositiveOffsetsBelowTheMiddle() {
        assertEquals(276, ScreenGeometry.anchorMiddle(12, 500, 26));
    }
}
