package com.attendance.api.exception;

/** Maps to 403 - the device was outside the location's geofence at check-in. */
public class GeofenceViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final double distanceMeters;
    private final int allowedRadiusMeters;

    public GeofenceViolationException(double distanceMeters, int allowedRadiusMeters) {
        super(String.format(
                "Outside geofence: you are %.0f m from the location, which allows %d m.",
                distanceMeters, allowedRadiusMeters));
        this.distanceMeters = distanceMeters;
        this.allowedRadiusMeters = allowedRadiusMeters;
    }

    public double getDistanceMeters() {
        return distanceMeters;
    }

    public int getAllowedRadiusMeters() {
        return allowedRadiusMeters;
    }
}
