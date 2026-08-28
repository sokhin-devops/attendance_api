package com.attendance.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class GeoUtilsTest {

    @Test
    @DisplayName("distance between identical coordinates is zero")
    void zeroDistanceForSamePoint() {
        assertThat(GeoUtils.distanceMeters(24.8607, 67.0011, 24.8607, 67.0011))
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("Karachi to Lahore is roughly 1030 km")
    void knownLongDistance() {
        double metres = GeoUtils.distanceMeters(24.8607, 67.0011, 31.5204, 74.3587);
        // Published great-circle distance is ~1024-1035 km depending on the reference points.
        assertThat(metres).isBetween(1_000_000.0, 1_060_000.0);
    }

    @Test
    @DisplayName("one degree of latitude is about 111 km")
    void oneDegreeLatitude() {
        double metres = GeoUtils.distanceMeters(0.0, 0.0, 1.0, 0.0);
        assertThat(metres).isBetween(111_000.0, 111_500.0);
    }

    @Test
    @DisplayName("distance is symmetric")
    void symmetric() {
        double ab = GeoUtils.distanceMeters(24.8607, 67.0011, 24.9000, 67.1000);
        double ba = GeoUtils.distanceMeters(24.9000, 67.1000, 24.8607, 67.0011);
        assertThat(ab).isCloseTo(ba, org.assertj.core.data.Offset.offset(0.000001));
    }

    @ParameterizedTest(name = "offset {0},{1} within {2}m radius -> {3}")
    @CsvSource({
            // ~11 m north of the origin point
            "0.0001, 0.0,   50,  true",
            // ~111 m north
            "0.001,  0.0,   50,  false",
            "0.001,  0.0,   150, true",
            // ~1.1 km north
            "0.01,   0.0,   150, false",
    })
    @DisplayName("radius check accepts inside and rejects outside")
    void radiusCheck(double dLat, double dLon, int radius, boolean expected) {
        double baseLat = 24.8607;
        double baseLon = 67.0011;
        boolean actual = GeoUtils.isWithinRadius(
                baseLat + dLat, baseLon + dLon, baseLat, baseLon, radius);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("a point exactly on the boundary counts as inside")
    void boundaryIsInclusive() {
        double baseLat = 0.0;
        double baseLon = 0.0;
        // Find the latitude offset that lands almost exactly 100 m away.
        double target = 100.0;
        double dLat = target / 111_195.0;
        double distance = GeoUtils.distanceMeters(baseLat + dLat, baseLon, baseLat, baseLon);
        assertThat(distance).isCloseTo(target, org.assertj.core.data.Offset.offset(1.0));
        assertThat(GeoUtils.isWithinRadius(baseLat + dLat, baseLon, baseLat, baseLon, 101)).isTrue();
    }
}
