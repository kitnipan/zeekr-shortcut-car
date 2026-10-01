package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InfoBarBlinkTest {

    @Test
    public void lampTogglesEveryHalfPeriod() {
        assertTrue(InfoBarRenderer.lampOn(0L));
        assertFalse(InfoBarRenderer.lampOn(InfoBarRenderer.BLINK_HALF_MS));
        assertTrue(InfoBarRenderer.lampOn(InfoBarRenderer.BLINK_HALF_MS * 2));
    }

    @Test
    public void onlyAnActiveSignalFlashes() {
        assertFalse(InfoBarRenderer.flashing(VehicleState.empty()));
        assertTrue(InfoBarRenderer.flashing(
                VehicleState.empty().edit().turnSignal(VehicleState.TURN_LEFT).build()));
        assertTrue(InfoBarRenderer.flashing(
                VehicleState.empty().edit().hazard(true).build()));
        assertFalse(InfoBarRenderer.flashing(
                VehicleState.empty().edit().turnSignal(VehicleState.TURN_NONE).hazard(false).build()));
    }
}
