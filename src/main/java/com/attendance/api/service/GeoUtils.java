package com.attendance.api.service;

/** Great-circle distance helpers for geofence checks. */
public final class GeoUtils {

    /** Mean Earth radius in meters (WGS-84 mean). */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoUtils() {
    }

    /**
     * Haversine distance between two coordinates.
     *
     * @return separation in meters
     */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static boolean isWithinRadius(double lat1, double lon1,
                                         double lat2, double lon2, int radiusMeters) {
        return distanceMeters(lat1, lon1, lat2, lon2) <= radiusMeters;
    }
}
