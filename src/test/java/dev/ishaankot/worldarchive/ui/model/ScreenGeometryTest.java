package dev.ishaankot.worldarchive.ui.model;

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
    void centerXSplitsTheRemainingSpaceEvenly() {
        assertEquals(10, ScreenGeometry.centerX(400, 380));
        assertEquals(0, ScreenGeometry.centerX(400, 400));
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
}
